package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.build.BuildBlockGate;
import com.walpro.autobuilder.core.deliberation.DeliberationDecision;
import com.walpro.autobuilder.core.deliberation.DeliberationMode;
import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.core.deliberation.DeliberationService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.DerivedFacts;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalReadout;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.observability.behaviour.BehaviourEvent;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Signal-driven execution:
 * - Rank coordinators by fitness (signal strength * recency + base priority + eval bias + jitter)
 * - Execute if canExecute()
 * - Reinforce/attenuate signal based on observed progress
 */
@Slf4j
@RequiredArgsConstructor
public class AdaptiveExecutionEngine {

    private static final double W_SIGNAL = 2.0;     // weight for signal strength
    private static final double W_RECENCY = 1.0;    // weight for signal recency (age penalty)
    private static final double W_PRIORITY = 1.0;   // weight for coordinator's base priority(context)
    private static final double W_EVALBIAS = 0.1;   // mild global steering from evaluation bias
    private static final double EXPLORATION_JITTER = 0.01;

    private static final double SUCCESS_REINFORCE = 1.0;
    private static final double FAIL_ATTENUATION = 0.90;

    private final List<ExecutionAgent> executionAgents;

    private final DeliberationService deliberationService;
    private final DeliberationExecutionGate deliberationGate;
    private final ExecutionEligibilityExplainer executionEligibilityExplainer;

    /** T5-H: optional behaviour trace logger. Setter so the @Bean factory method does not need a new arg. */
    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    /** T1-DT: optional — only present when {@code autobuilder.build.enabled=true}. */
    @Setter
    @Autowired(required = false)
    private BuildBlockGate buildBlockGate;

    /** T4-J Phase 2: optional tuning-store hook for the reinforce-default decay (see below). */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.tuning.TuningParameterStore tuningParameterStore;

    protected double explorationJitter = EXPLORATION_JITTER;

    private final Random rnd = new Random();

    public CycleContextData runCycle(CycleContextData context) {
        // T1-DL: skip deliberation entirely if a halt signal is already present
        if (DerivedFacts.goalExhausted(context) || context.hasSignal(BasicSignals.SYSTEM_PAUSED)) {
            log.info("Skipping deliberation: halt signal already present in context (GOAL_EXHAUSTED/SYSTEM_PAUSED).");
            return context;
        }

        // 0) Pass-header: Deliberate once per pass and apply proposed signals
        DeliberationDecision dd = deliberationService.deliberate(context);
        context = dd.context();
        DeliberationResult dr = dd.result();
        context = applyProposedSignals(context, dr);
        context = context.withDeliberationResult(dr);

        DeliberationMode mode = dr != null ? dr.mode() : DeliberationMode.UNDIRECTED;

        // guards "one execution per role per cycle"
        final Set<String> executedRoles = new HashSet<>();
        // roles skipped by canExecute=false in the fitness-ranked pass; eligible for a second-pass re-check
        final Set<String> blockedByCanExecute = new HashSet<>();

        if (shouldEndCycle(context)) return context;

        // T1-DT: while the build is broken, guarantee a BUILD_FIX subgoal exists before agent
        // ranking so evolution/merge against it become eligible in the same cycle. Without this,
        // the gate would block every role and the cycle would end with no progress.
        if (buildBlockGate != null) {
            buildBlockGate.ensureBuildFixSubgoalExists(context);
            // T2-EF: redirect on a defect dominating the run's residual probe failures across
            // subgoals — independent of the post-merge build-broken gate above (the channel
            // T1-EC made dead), driven by RecurringDefectTracker on the live pre-merge channel.
            buildBlockGate.ensureRecurringDefectRedirection(context);
        }

        // 1) Signal-driven ordering
        List<ExecutionAgent> eligibleByMode = executionAgents.stream()
                .filter(a -> deliberationGate.allows(mode, a.role()))
                .toList();

        if (eligibleByMode.isEmpty()) {
            log.info("DeliberationMode={} → no eligible roles. Ending pass.", mode);
            return context;
        }

        final CycleContextData snapshot = context;

        List<ExecutionAgent> order = eligibleByMode.stream()
                .sorted(Comparator.comparingDouble(
                        (ExecutionAgent agent) -> -fitnessFor(agent, snapshot))) // descending fitness
                .toList();

        log.debug("Signal-driven order this pass: {}", order.stream().map(ExecutionAgent::role).toList());

        if (behaviourLogger != null) {
            List<BehaviourEvent.AgentRanking> rankings = order.stream()
                    .map(a -> new BehaviourEvent.AgentRanking(a.role(), fitnessFor(a, snapshot), fitnessBreakdown(a, snapshot)))
                    .toList();
            behaviourLogger.agentFitnessRanked(snapshot.cycleName(), rankings);
            // T4-BO: the causality-preserving snapshot for signal->action correlation — taken
            // HERE, before any agent in this pass executes, so a signal that changes as a side
            // effect of running an agent can never be misread as having "caused" that agent.
            behaviourLogger.signalSnapshot(snapshot.cycleName(), extractSignalSnapshot(snapshot));
        }

        // 2) Execute in fitness order with signal reinforcement/attenuation
        for (ExecutionAgent agent : order) {
            if (shouldEndCycle(context)) {
                break;
            }
            String role = agent.role();
            if (!executedRoles.add(role)) {
                log.debug("Skipping role={} (already executed this cycle)", role);
                if (behaviourLogger != null) behaviourLogger.agentSkipped(context.cycleName(), role, "already_executed_this_cycle");
                continue;
            }

            if (!executionEligibilityExplainer.canExecute(agent.role(), context)) {
                String reason = "can_execute_false";
                if (behaviourLogger != null) {
                    // T1-DT: surface build_blocked specifically so the trace explains why feature
                    // work was skipped while the build was red.
                    List<String> blockers = executionEligibilityExplainer.explainBlockers(agent.role(), context);
                    if (blockers.contains("build_blocked")) {
                        reason = "build_blocked";
                    }
                    behaviourLogger.agentSkipped(context.cycleName(), role, reason);
                }
                blockedByCanExecute.add(role);
                continue;
            }

            log.info("Executing coordinator (role={})", role);
            if (behaviourLogger != null) behaviourLogger.agentSelected(context.cycleName(), role, fitnessFor(agent, context));
            CycleContextData before = context;
            context = agent.execute(context);

            if (role.equals("analysis") && analysisUnlockedExecutableWork(before, context)) {
                log.info("Analysis produced executable subgoals — exiting REPLAN mode");

                context = context
                        .consumeRequest(BasicSignals.REPLAN_REQUESTED)
                        .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.10, "AnalysisUnlock"))
                        .withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 0.0, 0.10, "AnalysisUnlock"));
//                        .withDeliberationResult(new DeliberationResult(
//                                DeliberationMode.UNDIRECTED, // leave gating open; role signals will steer execution
//                                0.70,
//                                "Analysis produced executable subgoals for current scope → resume execution flow.",
//                                List.of("analysis_proposed_executable=true"),
//                                List.of(),
//                                Map.of("action", "resume_execution"),
//                                List.of(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.10, "AnalysisUnlock"))
//                        ));
            }

            SignalType type = signalKeyFor(role);

            if (hasProgressed(before, context)) {
                context = reinforceSignal(context, type);
            } else {
                context = attenuateSignal(context, type);
            }
        }

        // T1-DK: second-pass re-check — any role blocked by canExecute=false in the fitness-ranked
        // pass gets another chance now that earlier agents have updated the context. Preserves the
        // original fitness order so the dependency is discovered from context state, not hard-wired.
        for (ExecutionAgent agent : order) {
            if (shouldEndCycle(context)) break;
            String role = agent.role();
            if (!blockedByCanExecute.contains(role)) continue;
            if (!executionEligibilityExplainer.canExecute(role, context)) continue;

            log.info("Second-pass: role={} now eligible after earlier agents updated context", role);
            if (behaviourLogger != null) behaviourLogger.agentSelected(context.cycleName(), role, 0.0);
            CycleContextData before = context;
            context = agent.execute(context);
            SignalType type = signalKeyFor(role);
            context = hasProgressed(before, context)
                    ? reinforceSignal(context, type)
                    : attenuateSignal(context, type);
        }

        if (shouldEndCycle(context)) return context;

        // 3) Exploration: if everything is weak, seed a small random nudge
        context = exploreIfNeeded(context);

        return context;
    }

    /** Simple notion of "progress": any change in signals map or known deltas the context tracks. */
    private boolean hasProgressed(CycleContextData before, CycleContextData after) {
        // Keep it conservative: any signal set change or strength change counts as progress.
        Map<SignalType, Signal> a = before.signals();
        Map<SignalType, Signal> b = after.signals();
        if (!a.keySet().equals(b.keySet())) return true;
        for (SignalType k : a.keySet()) {
            Signal sa = a.get(k);
            Signal sb = b.get(k);
            if (sb == null) return true;
            if (Double.compare(sa.strength(), sb.strength()) != 0) return true;
            if (sa.age() != sb.age()) return true;
        }
        return false;
    }

    private double fitnessFor(ExecutionAgent agent, CycleContextData ctx) {
        // Base “identity” signal (ROLE_*) stays meaningful
        SignalType roleKey = signalKeyFor(agent.role());

        // Sum contributions from supported signals
        Set<SignalType> supported = agent.getSupportedSignals();
        if (supported == null || supported.isEmpty()) {
            supported = Set.of(roleKey);
        } else if (!supported.contains(roleKey)) {
            // ensure roleKey always participates
            supported = new java.util.HashSet<>(supported);
            supported.add(roleKey);
        }

        double signalScore = 0.0;
        boolean sawAnySignal = false;

        for (SignalType t : supported) {
            Signal s = ctx.signals().get(t);
            if (s == null) continue;

            sawAnySignal = true;

            double strength = s.strength();
            double recency = Math.exp(-(s.age() / 5.0));

            signalScore += (W_SIGNAL * strength) + (W_RECENCY * recency);
        }

        // T4-BZ-2: derived facts in the attention set contribute presence-shaped terms
        // (strength 1.0, recency 1.0) — identical to the always-1.0/decay-0 signals they
        // replaced (which were re-emitted at age 0 every cycle). EVOLUTION_OCCURRED's former
        // reinforce-to-cap dynamics are deliberately gone; slice 4 deletes this arithmetic.
        double derivedScore = derivedFactScore(supported, ctx);
        if (derivedScore > 0) {
            sawAnySignal = true;
            signalScore += derivedScore;
        }

        // T4-BZ-3: open channel requests in the attention set contribute the same formula the
        // stored request signals did — urgency for strength (issue value, no decay), the same
        // recency curve over age. Slice 4 replaces this arithmetic with the selection rule.
        double requestScore = requestScore(supported, ctx);
        if (requestScore > 0) {
            sawAnySignal = true;
            signalScore += requestScore;
        }

        double basePriority = safePriority(agent, ctx);
        double evalBias = safeEvalBias(ctx);
        double jitter = (rnd.nextDouble() - 0.5) * explorationJitter;

        // Priority is only a tie-breaker (or fallback if there are no relevant signals)
        double priorityScore = (!sawAnySignal || Math.abs(signalScore) < 1e-9)
                ? (W_PRIORITY * basePriority)
                : 0.0;

        return signalScore
                + priorityScore
                + W_EVALBIAS * evalBias
                + jitter
                + buildBrokenAdjustment(agent.role(), ctx);
    }

    /**
     * T1-DU: backstop for the build-block gate. While BUILD_BROKEN is active, planner and
     * evaluation are depressed so that within the eligible set (evolution/merge against the
     * BUILD_FIX subgoal) the right work outranks anything else. The gate (T1-DT) is the
     * principled fix; this backstop just removes ranking ambiguity in marginal cases where
     * a stale ROLE_PLANNER signal would otherwise win.
     */
    private double buildBrokenAdjustment(String role, CycleContextData ctx) {
        if (ctx == null || !DerivedFacts.buildBroken(ctx)) return 0.0;
        // T4-BZ-2: derived fact — presence-shaped at 1.0 (the deleted emitter always wrote 1.0);
        // a stored entry (REST injection / recorded state) keeps its own strength.
        Signal stored = ctx.signals().get(BasicSignals.BUILD_BROKEN);
        double strength = stored != null ? stored.strength() : 1.0;
        return switch (role == null ? "" : role.toLowerCase()) {
            case "planner", "evaluation" -> -W_SIGNAL * strength;
            // T2-DE: boost analysis when build is broken — it is the sole diagnostic escape hatch
            // and must outrank all other agents that remain eligible under the build-block gate.
            case "analysis" -> +W_SIGNAL * strength;
            default -> 0.0;
        };
    }

    /** T5-H: same components as {@link #fitnessFor} so the behaviour trace can show why a role won. */
    private Map<String, Double> fitnessBreakdown(ExecutionAgent agent, CycleContextData ctx) {
        SignalType roleKey = signalKeyFor(agent.role());
        Set<SignalType> supported = agent.getSupportedSignals();
        if (supported == null || supported.isEmpty()) {
            supported = Set.of(roleKey);
        } else if (!supported.contains(roleKey)) {
            supported = new java.util.HashSet<>(supported);
            supported.add(roleKey);
        }
        double signalScore = 0.0;
        boolean sawAnySignal = false;
        for (SignalType t : supported) {
            Signal s = ctx.signals().get(t);
            if (s == null) continue;
            sawAnySignal = true;
            signalScore += (W_SIGNAL * s.strength()) + (W_RECENCY * Math.exp(-(s.age() / 5.0)));
        }
        double derivedScore = derivedFactScore(supported, ctx);
        if (derivedScore > 0) {
            sawAnySignal = true;
            signalScore += derivedScore;
        }
        double requestScore = requestScore(supported, ctx);
        if (requestScore > 0) {
            sawAnySignal = true;
            signalScore += requestScore;
        }
        double basePriority = safePriority(agent, ctx);
        double evalBias = safeEvalBias(ctx);
        Map<String, Double> out = new java.util.LinkedHashMap<>();
        out.put("signal", signalScore);
        if (derivedScore > 0) out.put("derived", derivedScore);
        if (requestScore > 0) out.put("request", requestScore);
        out.put("priority", (!sawAnySignal || Math.abs(signalScore) < 1e-9) ? (W_PRIORITY * basePriority) : 0.0);
        out.put("evalBias", W_EVALBIAS * evalBias);
        // T1-DU: surface the BUILD_BROKEN depression so the behaviour trace shows why a role lost.
        double brokenAdj = buildBrokenAdjustment(agent.role(), ctx);
        if (brokenAdj != 0.0) out.put("buildBroken", brokenAdj);
        return out;
    }

    /**
     * T4-BZ-2: presence-shaped fitness contribution for derived facts in a role's attention
     * set — one term (W_SIGNAL * 1.0 + W_RECENCY * 1.0) per present fact, mirroring the
     * always-1.0/age-0 stored signals they replaced.
     */
    private double derivedFactScore(Set<SignalType> supported, CycleContextData ctx) {
        if (ctx == null) return 0.0;
        double term = W_SIGNAL + W_RECENCY;
        double sum = 0.0;
        // A stored entry of the type (REST injection / recorded state) already contributed in
        // the supported-signals loop — skip its derived term to avoid counting the fact twice.
        if (participates(supported, BasicSignals.BUILD_BROKEN, ctx) && DerivedFacts.buildBroken(ctx)) sum += term;
        if (participates(supported, BasicSignals.TEST_REPAIR_FOCUS, ctx) && DerivedFacts.testRepairFocus(ctx)) sum += term;
        if (participates(supported, BasicSignals.EVOLUTION_OCCURRED, ctx) && DerivedFacts.evolutionOccurred(ctx)) sum += term;
        if (participates(supported, BasicSignals.SCOPE_OUT_OF_SCOPE, ctx) && DerivedFacts.scopeOutOfScope(ctx)) sum += term;
        if (participates(supported, BasicSignals.SUBGOAL_SCOPE_MISMATCH, ctx) && DerivedFacts.subgoalScopeMismatch(ctx)) sum += term;
        return sum;
    }

    private static boolean participates(Set<SignalType> supported, SignalType type, CycleContextData ctx) {
        return supported.contains(type) && !ctx.hasSignal(type);
    }

    /**
     * T4-BZ-3: fitness contribution of open channel requests whose kind is in the agent's
     * attention set — W_SIGNAL x urgency + W_RECENCY x the same recency curve, per open
     * request. A stored twin of the kind (recorded state / REST injection) already contributed
     * in the supported-signals loop and wins per the migration bridge — skip to avoid double
     * counting.
     */
    private double requestScore(Set<SignalType> supported, CycleContextData ctx) {
        if (ctx == null || ctx.requests().isEmpty()) return 0.0;
        double sum = 0.0;
        for (com.walpro.autobuilder.core.lifecycle.ChannelRequest r : ctx.requests().open().values()) {
            if (!supported.contains(r.kind()) || ctx.hasSignal(r.kind())) continue;
            double recency = Math.exp(-(r.age() / 5.0));
            sum += (W_SIGNAL * r.urgency()) + (W_RECENCY * recency);
        }
        return sum;
    }

    private double safePriority(ExecutionAgent a, CycleContextData ctx) {
        try { return a.priority(ctx); } catch (Throwable t) { return 0.0; }
    }

    private double safeEvalBias(CycleContextData ctx) {
        try {
            Double b = ctx.evaluationBias(); // filled by strategy.adaptAfterCycle
            return b == null ? 0.0 : b;
        } catch (Throwable t) {
            return 0.0;
        }
    }

    // Role signals are identity-stable: ROLE_* SignalType is the identity,
    // strength/decay/age/meta are the mutable state. We never mutate the type
    // for role signals; new types are reserved for future trait/pattern signals.
    private SignalType signalKeyFor(String role) {
        String r = role == null ? "" : role.toLowerCase();
        return switch (r) {
            case "planner"   -> BasicSignals.ROLE_PLANNER;
            case "evolution" -> BasicSignals.ROLE_EVOLUTION;
            case "evaluation"-> BasicSignals.ROLE_EVALUATION;
            case "merge"     -> BasicSignals.ROLE_MERGE;
            case "analysis"   -> BasicSignals.ROLE_ANALYSIS;
            default          -> SignalType.of(role);
        };
    }

    private CycleContextData reinforceSignal(CycleContextData ctx, SignalType key) {
        // T4-J: 0.05 here is the decay-ratchet floor for a role signal born via reinforcement
        // (Signal.reinforce keeps the MINIMUM decay, so once a role is reinforced with this
        // default, no later re-emission can raise its decay above it). Tunable because the
        // T4-J replay measured role-signal decay as the decision-sensitive decay surface.
        double defaultDecay = tuningParameterStore != null
                ? tuningParameterStore.get("signal-decay.reinforce-default", 0.05)
                : 0.05;
        Signal current = ctx.signals().getOrDefault(key, Signal.of(key, 0.3, defaultDecay, key.name()));
        Signal reinforced = current.reinforce(Signal.of(key, SUCCESS_REINFORCE, current.decayPerCycle(), "reinforce"));
        return isRoleSignal(key)
                ? ctx.withSignalReplaceRoleSignals(reinforced)
                : ctx.withSignal(reinforced);
    }

    private CycleContextData attenuateSignal(CycleContextData ctx, SignalType key) {
        Signal current = ctx.signals().get(key);
        if (current == null) return ctx;
        // small genetic-style drift (slight mutation) to escape local optima
        Signal drifted = new Signal(current.type(), current.strength() * FAIL_ATTENUATION,
                current.decayPerCycle(), current.age(), current.source(), Map.of())/*.mutate("~")*/; // TODO: potentially use mutate to spawn *new* trait/pattern signal types (e.g., “EVOLUTION_PATTERN_X”), not for core ROLE_* signals (MW; 2025-DEC-06)
        return isRoleSignal(key)
                ? ctx.withSignalReplaceRoleSignals(drifted)
                : ctx.withSignal(drifted);
    }

    private CycleContextData exploreIfNeeded(CycleContextData ctx) {
        if (ctx.signals().isEmpty()) return ctx;

        double avg = ctx.signals().values().stream().mapToDouble(Signal::strength).average().orElse(0.0);
        if (avg > 0.08) return ctx;

        // Find the weakest signal and give it a tiny nudge (exploration)
        SignalType weakest = ctx.signals().entrySet().stream()
                .min(Comparator.comparingDouble(e -> e.getValue().strength()))
                .map(Map.Entry::getKey)
                .orElse(null);

        if (weakest == null) return ctx;

        Signal s = ctx.signals().get(weakest);
        Signal nudged = Signal.of(s.type(), Math.min(0.12, s.strength() + 0.04), s.decayPerCycle(), "explore");
        return ctx.withSignal(nudged);
    }

    private CycleContextData applyProposedSignals(CycleContextData ctx, DeliberationResult dr) {
        if (ctx == null || dr == null) {
            return ctx;
        }
        CycleContextData out = ctx;
        if (dr.proposedSignals() != null) {
            for (Signal s : dr.proposedSignals()) {
                if (s != null) out = out.withSignal(s);
            }
        }
        // T4-BZ-3: request-family proposals ride the typed carrier and enqueue on the channel.
        if (dr.proposedRequests() != null) {
            for (com.walpro.autobuilder.core.lifecycle.ChannelRequest r : dr.proposedRequests()) {
                if (r != null) out = out.issueRequest(r);
            }
        }
        return out;
    }

    private boolean isRoleSignal(SignalType type) {
        return type.name().startsWith("ROLE_");
    }

    private Map<String, Double> extractSignalSnapshot(CycleContextData ctx) {
        // T4-BZ-1: the snapshot payload renders through the readout surface.
        return SignalReadout.of(ctx).snapshot();
    }

    private boolean analysisUnlockedExecutableWork(
            CycleContextData before,
            CycleContextData after
    ) {
        if (before == null || after == null) return false;
        if (after.analysisOutcome() == null) return false;

        var proposed = after.analysisOutcome().proposedExecutableSubGoals();
        if (proposed == null || proposed.isEmpty()) return false;

        // Optional: check that at least one of those goals was actually injected
        // (e.g. currentSubGoalId changed or child count increased)
        return true;
    }

    public CycleContextData tryExecuteRole(String role, CycleContextData context) {
        if (context == null || role == null) return context;

        ExecutionAgent agent = executionAgents.stream()
                .filter(a -> a.role() != null && a.role().equalsIgnoreCase(role))
                .findFirst()
                .orElse(null);

        if (agent == null) return context;

        if (!executionEligibilityExplainer.canExecute(agent.role(), context)) return context;

        // respect “one execution per role per cycle” only inside runCycle;
        // this helper is intentionally “imperative”.
        return agent.execute(context);
    }

    /**
     * T4-AI-3: named cycle-termination reasons. MERGED keeps its historical semantics (a merge
     * ends the cycle immediately). HALT_SIGNAL_PRESENT is the mid-cycle extension of the T1-DL
     * cycle-start check: producers like PlannerAgent's empty-backlog-injection-limit raise
     * GOAL_EXHAUSTED/SYSTEM_PAUSED DURING the cycle, and the remaining agents previously kept
     * executing against a tree the producer had just declared halted — the halt was honored one
     * cycle late.
     */
    enum CycleTerminationReason { NONE, MERGED, HALT_SIGNAL_PRESENT }

    private CycleTerminationReason terminationReason(CycleContextData ctx) {
        if (ctx == null) return CycleTerminationReason.NONE;
        if (ctx.merged()) return CycleTerminationReason.MERGED;
        // Same predicate as the cycle-start T1-DL check, now honored mid-cycle too.
        if (DerivedFacts.goalExhausted(ctx) || ctx.hasSignal(BasicSignals.SYSTEM_PAUSED)) {
            return CycleTerminationReason.HALT_SIGNAL_PRESENT;
        }
        return CycleTerminationReason.NONE;
    }

    private boolean shouldEndCycle(CycleContextData ctx) {
        CycleTerminationReason reason = terminationReason(ctx);
        if (reason == CycleTerminationReason.NONE) return false;
        if (reason == CycleTerminationReason.HALT_SIGNAL_PRESENT) {
            // Field marker: a halt raised mid-cycle now ends the cycle immediately instead of
            // one cycle late (T4-AI-3).
            log.info("T4-AI-3: cycle ends mid-flight — {}", reason);
        } else {
            log.debug("Cycle termination: {}", reason);
        }
        return true;
    }

}