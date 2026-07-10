package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.build.BuildBlockGate;
import com.walpro.autobuilder.core.deliberation.DeliberationDecision;
import com.walpro.autobuilder.core.deliberation.DeliberationMode;
import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.core.deliberation.DeliberationService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.DerivedFacts;
import com.walpro.autobuilder.core.lifecycle.ParticipationWindow;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalReadout;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.observability.behaviour.BehaviourEvent;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T4-BZ-4: explicit selection pass (the Q-a rule, checked 27/28 against the frozen-state
 * library before it was built):
 * <ol>
 *   <li>halt — pause/terminal facts end the pass before it starts (I1);</li>
 *   <li>candidates — the deliberation-mode gate's allowed roles (I4);</li>
 *   <li>ordering — descending lexicographic (explicit constraint, open-request urgency for
 *       the role, participation-window boost, base priority), deterministic tie-break by
 *       pipeline order planner→analysis→evolution→evaluation→merge (closes audit-Q3's
 *       jitter coin-flip with a stated rule);</li>
 *   <li>walk-down — first eligible role executes, one execution per role per cycle, a merge
 *       ends the cycle (I2); roles blocked at first pass get the T1-DK second-pass
 *       re-check (I10).</li>
 * </ol>
 * Replaces the fitness arithmetic (strength x recency summation, reinforce/attenuate on
 * stored role signals, evaluation bias, exploration jitter and the weak-signal nudge). Role
 * participation now rides {@link ParticipationWindow}s: progress re-grants the executed
 * role's window (+1.0 boost, the old reinforcement 1:1), no-progress shrinks it (x0.9, the
 * old attenuation 1:1).
 */
@Slf4j
@RequiredArgsConstructor
public class AdaptiveExecutionEngine {

    /** Boost of the re-grant a progressing role's window receives (old SUCCESS_REINFORCE 1:1). */
    private static final double SUCCESS_REGRANT_BOOST = 1.0;

    /** Q-a tie-break: the pipeline order. Stated rule, not arithmetic accident. */
    private static final List<String> PIPELINE_ORDER =
            List.of("planner", "analysis", "evolution", "evaluation", "merge");

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

    /** T4-J Phase 2: optional tuning-store hook for the re-grant decay-equivalent (see below). */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.tuning.TuningParameterStore tuningParameterStore;

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
        context = applyProposals(context, dr);
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

        // 1) Selection ordering per the Q-a rule
        List<ExecutionAgent> eligibleByMode = executionAgents.stream()
                .filter(a -> deliberationGate.allows(mode, a.role()))
                .toList();

        if (eligibleByMode.isEmpty()) {
            log.info("DeliberationMode={} → no eligible roles. Ending pass.", mode);
            return context;
        }

        final CycleContextData snapshot = context;

        List<ExecutionAgent> order = eligibleByMode.stream()
                .sorted(selectionComparator(snapshot))
                .toList();

        log.debug("Selection order this pass: {}", order.stream().map(ExecutionAgent::role).toList());

        // Positional rank value (n..1, descending): agent_fitness_ranked keeps its shape (I9)
        // while the breakdown carries the rule's actual ordering keys.
        Map<String, Double> rankValues = new LinkedHashMap<>();
        for (int i = 0; i < order.size(); i++) {
            rankValues.put(order.get(i).role(), (double) (order.size() - i));
        }

        if (behaviourLogger != null) {
            List<BehaviourEvent.AgentRanking> rankings = new ArrayList<>();
            for (ExecutionAgent a : order) {
                rankings.add(new BehaviourEvent.AgentRanking(a.role(),
                        rankValues.get(a.role()), selectionBreakdown(a, snapshot)));
            }
            behaviourLogger.agentFitnessRanked(snapshot.cycleName(), rankings);
            // T4-BO: the causality-preserving snapshot for signal->action correlation — taken
            // HERE, before any agent in this pass executes, so a signal that changes as a side
            // effect of running an agent can never be misread as having "caused" that agent.
            behaviourLogger.signalSnapshot(snapshot.cycleName(), extractSignalSnapshot(snapshot));
        }

        // 2) Execute in selection order; progress re-grants the role's window, no-progress shrinks it
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
            if (behaviourLogger != null) behaviourLogger.agentSelected(context.cycleName(), role,
                    rankValues.getOrDefault(role, 0.0));
            CycleContextData before = context;
            context = agent.execute(context);

            if (role.equals("analysis") && analysisUnlockedExecutableWork(before, context)) {
                log.info("Analysis produced executable subgoals — exiting REPLAN mode");

                // I5 analysis-unlock guard: the replan request is served, evolution's
                // participation opens, the planner's window closes (A14 — the old strength-0
                // re-emission's stated intent "stop replanning now" made real).
                context = context
                        .consumeRequest(BasicSignals.REPLAN_REQUESTED)
                        .grantWindow(ParticipationWindow.of("evolution", 1.0, 0.10, "AnalysisUnlock"))
                        .revokeWindow("planner");
            }

            context = hasProgressed(before, context)
                    ? regrantWindow(context, role)
                    : context.shrinkWindow(role);
        }

        // T1-DK: second-pass re-check — any role blocked by canExecute=false in the fitness-ranked
        // pass gets another chance now that earlier agents have updated the context. Preserves the
        // original selection order so the dependency is discovered from context state, not hard-wired.
        for (ExecutionAgent agent : order) {
            if (shouldEndCycle(context)) break;
            String role = agent.role();
            if (!blockedByCanExecute.contains(role)) continue;
            if (!executionEligibilityExplainer.canExecute(role, context)) continue;

            log.info("Second-pass: role={} now eligible after earlier agents updated context", role);
            if (behaviourLogger != null) behaviourLogger.agentSelected(context.cycleName(), role, 0.0);
            CycleContextData before = context;
            context = agent.execute(context);
            context = hasProgressed(before, context)
                    ? regrantWindow(context, role)
                    : context.shrinkWindow(role);
        }

        return context;
    }

    /** Simple notion of "progress": any change in the carriers the selection layer reads. */
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
        // T4-BZ-3/4: the request channel and participation windows are selection state too.
        if (!before.requests().open().equals(after.requests().open())) return true;
        return !before.windows().active().equals(after.windows().active());
    }

    // ------------------------------------------------------------------
    // The Q-a ordering: (a) explicit constraint > (b) open-request urgency for the role >
    // (c) window boost > (d) base priority > (e) pipeline-order tie-break.
    // ------------------------------------------------------------------

    private Comparator<ExecutionAgent> selectionComparator(CycleContextData ctx) {
        return Comparator
                .comparingInt((ExecutionAgent a) -> constraintRank(a.role(), ctx)).reversed()
                .thenComparing(Comparator.comparingDouble(
                        (ExecutionAgent a) -> requestUrgencyFor(a, ctx)).reversed())
                .thenComparing(Comparator.comparingDouble(
                        (ExecutionAgent a) -> ctx.windowBoost(a.role())).reversed())
                .thenComparing(Comparator.comparingDouble(
                        (ExecutionAgent a) -> safePriority(a, ctx)).reversed())
                .thenComparing(a -> pipelineIndex(a.role()));
    }

    /**
     * (a) Explicit constraints — each one a stated production intent, not derived arithmetic.
     * Currently exactly one, in full: while the build is broken, analysis orders FIRST (the
     * sole diagnostic escape hatch, T2-DE) and planner/evaluation order LAST among the
     * candidates (T1-DU: a stale strong ROLE_PLANNER window must not outrank the build-fix
     * work) — formerly the signed buildBrokenAdjustment fitness term.
     */
    private static int constraintRank(String role, CycleContextData ctx) {
        if (ctx == null || !DerivedFacts.buildBroken(ctx)) return 0;
        return switch (role == null ? "" : role.toLowerCase()) {
            case "analysis" -> 1;
            case "planner", "evaluation" -> -1;
            default -> 0;
        };
    }

    /** (b) Max urgency over open requests addressed to the role (attention set ∩ channel kinds). */
    private static double requestUrgencyFor(ExecutionAgent agent, CycleContextData ctx) {
        if (ctx == null) return 0.0;
        Set<SignalType> supported = agent.getSupportedSignals();
        if (supported == null || supported.isEmpty()) return 0.0;
        double max = 0.0;
        for (SignalType t : supported) {
            if (!BasicSignals.REQUEST_CHANNEL_KINDS.contains(t)) continue;
            max = Math.max(max, ctx.requestUrgency(t));
        }
        return max;
    }

    private static int pipelineIndex(String role) {
        int i = PIPELINE_ORDER.indexOf(role == null ? "" : role.toLowerCase());
        return i < 0 ? PIPELINE_ORDER.size() : i;
    }

    /** The rule's ordering keys, verbatim, so the behaviour trace shows why a role won (I9). */
    private Map<String, Double> selectionBreakdown(ExecutionAgent agent, CycleContextData ctx) {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("constraint", (double) constraintRank(agent.role(), ctx));
        out.put("urgency", requestUrgencyFor(agent, ctx));
        out.put("window", ctx.windowBoost(agent.role()));
        out.put("priority", safePriority(agent, ctx));
        out.put("pipeline", (double) pipelineIndex(agent.role()));
        return out;
    }

    private double safePriority(ExecutionAgent a, CycleContextData ctx) {
        try { return a.priority(ctx); } catch (Throwable t) { return 0.0; }
    }

    /**
     * Progress re-grants the executed role's window: +1.0 boost (capped, exactly the old
     * reinforcement), age reset, validity re-derived from the decay-equivalent — the T4-J
     * tuning hook survives on the same parameter name until T4-BZ-5 re-points it.
     */
    private CycleContextData regrantWindow(CycleContextData ctx, String role) {
        double decayEquivalent = tuningParameterStore != null
                ? tuningParameterStore.get("signal-decay.reinforce-default", 0.05)
                : 0.05;
        return ctx.grantWindow(ParticipationWindow.of(role, SUCCESS_REGRANT_BOOST, decayEquivalent, "reinforce"));
    }

    private CycleContextData applyProposals(CycleContextData ctx, DeliberationResult dr) {
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
        // T4-BZ-4: role-participation proposals grant windows.
        if (dr.proposedWindows() != null) {
            for (ParticipationWindow w : dr.proposedWindows()) {
                if (w != null) out = out.grantWindow(w);
            }
        }
        return out;
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
