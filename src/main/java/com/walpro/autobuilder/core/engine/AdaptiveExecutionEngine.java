package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.build.BuildBlockGate;
import com.walpro.autobuilder.core.deliberation.DeliberationDecision;
import com.walpro.autobuilder.core.deliberation.DeliberationMode;
import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.core.deliberation.DeliberationService;
import com.walpro.autobuilder.core.engine.meta.MetaOverrideCategory;
import com.walpro.autobuilder.core.engine.meta.MetaOverrideEvent;
import com.walpro.autobuilder.core.engine.meta.MetaOverrideLearningStore;
import com.walpro.autobuilder.core.engine.meta.MetaSkipPatternKey;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.observability.behaviour.BehaviourEvent;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import com.walpro.autobuilder.protocol.meta.MetaActionStep;
import com.walpro.autobuilder.protocol.meta.MetaIntention;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
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

    private static final double ROLE_MIN_EXEC = 0.10;
    private static final double DEADLOCK_INJECT_STRENGTH = 0.12; //TODO: Do we really need this? If so, wouldn't it be better to set it to 0.11 to make attenuation -> strength < 0.1 within the same cycle possible?

    private static final double SUCCESS_REINFORCE = 1.0;
    private static final double FAIL_ATTENUATION = 0.90;

    private static final int META_MAX_STEPS_PER_CYCLE = 5;

    private final List<ExecutionAgent> executionAgents;

    private final DeliberationService deliberationService;
    private final DeliberationExecutionGate deliberationGate;
    private final ExecutionEligibilityExplainer executionEligibilityExplainer;
    private final MetaOverrideLearningStore metaOverrideLearningStore;

    /** T5-H: optional behaviour trace logger. Setter so the @Bean factory method does not need a new arg. */
    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    /** T1-DT: optional — only present when {@code autobuilder.build.enabled=true}. */
    @Setter
    @Autowired(required = false)
    private BuildBlockGate buildBlockGate;

    protected double explorationJitter = EXPLORATION_JITTER;

    private final Random rnd = new Random();

    public CycleContextData runCycle(CycleContextData context) {
        return runCycle(context, false);
    }

    public CycleContextData runCycle(CycleContextData context, boolean executeMetaStepsOnly) {
        // T1-DL: skip deliberation entirely if a halt signal is already present
        if (context.hasSignal(BasicSignals.GOAL_EXHAUSTED) || context.hasSignal(BasicSignals.SYSTEM_PAUSED)) {
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

        // guards "one execution per role per cycle" across meta + fitness phases
        final Set<String> executedRoles = new HashSet<>();
        // roles skipped by canExecute=false in the fitness-ranked pass; eligible for a second-pass re-check
        final Set<String> blockedByCanExecute = new HashSet<>();

        // 1) If meta provided steps, execute them directly in-cycle (LLM orchestration)
        List<MetaActionStep> metaSteps = extractMetaSteps(dr);
        if (!metaSteps.isEmpty()) {
            CycleContextData beforeMeta = context;
            MetaExecutionResult metaResult = executeMetaSteps(context, metaSteps, mode, executedRoles);
            CycleContextData afterMeta = metaResult.context();

            // If meta did not execute anything, it will usually produce no progress at all.
            // In that case: fall back to normal signal-driven execution.
            boolean metaExecuted = metaResult.executedCount() > 0;
            boolean metaProgressed = hasProgressed(beforeMeta, afterMeta);

            // Meta steps are a prefix, not a full cycle program.
            // We continue with signal-driven ordering for remaining roles.

            if (!metaExecuted) {
                publishMetaOverride(beforeMeta, afterMeta, mode, metaSteps, metaResult);
                log.info("Meta steps present but none executable → falling back to signal-driven ordering.");
                afterMeta = injectRequiredRoleSignalForModeIfDeadlocked(context, mode);
            } else if (metaResult.stopCycle) {
                log.info("Meta steps executed with stop_cycle condition, stopping cycle.");
                return afterMeta;
            }

            context = afterMeta; // carry forward anyway, then finish cycle via signal-driven ordering
        }

        if (executeMetaStepsOnly || shouldEndCycle(context)) return context;

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

        // 2) Otherwise fall back to signal-driven ordering
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
        }

        // 3) Execute in fitness order with signal reinforcement/attenuation
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
                        .withoutSignal(BasicSignals.REPLAN_REQUESTED)
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
        if (ctx == null) return 0.0;
        Signal s = ctx.signals().get(BasicSignals.BUILD_BROKEN);
        if (s == null) return 0.0;
        return switch (role == null ? "" : role.toLowerCase()) {
            case "planner", "evaluation" -> -W_SIGNAL * s.strength();
            // T2-DE: boost analysis when build is broken — it is the sole diagnostic escape hatch
            // and must outrank all other agents that remain eligible under the build-block gate.
            case "analysis" -> +W_SIGNAL * s.strength();
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
        double basePriority = safePriority(agent, ctx);
        double evalBias = safeEvalBias(ctx);
        Map<String, Double> out = new java.util.LinkedHashMap<>();
        out.put("signal", signalScore);
        out.put("priority", (!sawAnySignal || Math.abs(signalScore) < 1e-9) ? (W_PRIORITY * basePriority) : 0.0);
        out.put("evalBias", W_EVALBIAS * evalBias);
        // T1-DU: surface the BUILD_BROKEN depression so the behaviour trace shows why a role lost.
        double brokenAdj = buildBrokenAdjustment(agent.role(), ctx);
        if (brokenAdj != 0.0) out.put("buildBroken", brokenAdj);
        return out;
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
        Signal current = ctx.signals().getOrDefault(key, Signal.of(key, 0.3, 0.05, key.name()));
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
        if (ctx == null || dr == null || dr.proposedSignals() == null || dr.proposedSignals().isEmpty()) {
            return ctx;
        }
        CycleContextData out = ctx;
        for (Signal s : dr.proposedSignals()) {
            if (s != null) out = out.withSignal(s);
        }
        return out;
    }

    private boolean isRoleSignal(SignalType type) {
        return type.name().startsWith("ROLE_");
    }

    private MetaExecutionResult executeMetaSteps(CycleContextData context,
                                                 List<MetaActionStep> steps,
                                                 DeliberationMode mode,
                                                 Set<String> executedRoles) {

        int executed = 0;
        List<SkippedMetaStep> skipped = new ArrayList<>();

        for (MetaActionStep step : steps) {
            if (shouldEndCycle(context)) {
                log.info("Meta step {} merged.", step.index());
                break;
            }

            if (executed >= META_MAX_STEPS_PER_CYCLE) {
                skipped.add(new SkippedMetaStep(step.index(), step.targetRole(), step.request(), "STEP_CAP_REACHED"));
                log.info("Meta step cap reached ({}).", META_MAX_STEPS_PER_CYCLE);
                break;
            }

            // T2-DJ: check membership BEFORE adding — a blocked step must not poison executedRoles
            // or the signal-driven phase will skip the role as "already_executed_this_cycle" even
            // though it never ran.
            if (executedRoles.contains(step.targetRole())) {
                skipped.add(new SkippedMetaStep(step.index(), step.targetRole(), step.request(), "ROLE_ALREADY_EXECUTED"));
                log.debug("Skipping role={} (already executed this cycle)", step.targetRole());
                continue;
            }

            ExecutionAgent agent = findAgentForTargetRole(step.targetRole());
            if (agent == null) {
                skipped.add(new SkippedMetaStep(step.index(), step.targetRole(), step.request(), "NO_AGENT_FOR_ROLE"));
                log.warn("Meta step {} skipped: no agent found for TargetRole={}", step.index(), step.targetRole());
                continue;
            }

            String role = agent.role();

            if (!deliberationGate.allows(mode, role)) {
                skipped.add(new SkippedMetaStep(step.index(), role, step.request(), "MODE_BLOCKS_ROLE"));
                log.info("Meta step {} skipped: DeliberationMode={} blocks role={}", step.index(), mode, role);
                continue;
            }

            if (!executionEligibilityExplainer.canExecute(agent.role(), context)) {
                skipped.add(new SkippedMetaStep(step.index(), role, step.request(), "CAN_EXECUTE_FALSE"));
                log.info("Meta step {} skipped: canExecute=false for role={}", step.index(), role);
                continue;
            }

            // All gates passed — mark the role as executed only now so skipped steps don't block it.
            executedRoles.add(role);
            log.info("Meta step {} executing role={} request='{}'", step.index(), role, step.request());

            CycleContextData before = context;
            MetaIntention metaIntention = new MetaIntention(role, step.request());
            context = agent.execute(context.withMetaIntention(metaIntention));
            executed++;

            if (role.equals("analysis") && analysisUnlockedExecutableWork(before, context)) {
                log.info("Analysis produced executable subgoals — exiting REPLAN mode");

                context = context
                        .withoutSignal(BasicSignals.REPLAN_REQUESTED)
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

            StopDecision stop = stopDecision(context, step);
            if (stop != StopDecision.NONE) {
                log.info("Meta step {} stop condition met ({}): stopIf='{}' stopCycleIf='{}'",
                        step.index(), stop, step.stopIf(), step.stopCycleIf());
                if (stop == StopDecision.STOP_CYCLE) {
                    // end cycle immediately — no fallback, no signal-driven pass
                    return new MetaExecutionResult(context, executed, skipped, true);
                }
                break; // STOP_META: stop meta sequencing only
            }
        }

        context = exploreIfNeeded(context);
        return new MetaExecutionResult(context, executed, skipped, false);
    }

    private ExecutionAgent findAgentForTargetRole(String targetRole) {
        if (targetRole == null || targetRole.isBlank()) return null;
        String t = targetRole.trim().toLowerCase();

        // coordinator roles are lowercase: planner/evolution/evaluation/merge
        return executionAgents.stream()
                .filter(a -> a.role() != null && a.role().equalsIgnoreCase(t))
                .findFirst()
                .orElse(null);
    }

    /** T4-AI-1: stopCycleIf and stopIf are evaluated as DISTINCT guards (the pipe-string
     *  carrier collapsed them into one field; a step declaring both lost its stopIf). */
    private StopDecision stopDecision(CycleContextData ctx, MetaActionStep step) {
        if (step == null) return StopDecision.NONE;
        if (stopConditionMet(ctx, step.stopCycleIf())) return StopDecision.STOP_CYCLE;
        if (stopConditionMet(ctx, step.stopIf())) return StopDecision.STOP_META;
        return StopDecision.NONE;
    }

    private boolean stopConditionMet(CycleContextData ctx, String condition) {
        if (condition == null || condition.isBlank()) return false;
        String s = condition.trim().toLowerCase();
        if ("never".equals(s)) return false;

        boolean conditionMet = false;

        if (s.contains("mutationacceptancestate")) {
            // ctx.mutationState() is written by CycleExecutor.applyEvaluationInterpretation() AFTER
            // runCycle() returns — it is always null inside the meta-step loop.
            // Fall back to evaluationOutcome.mutationState(), which EvaluationAgent writes immediately.
            com.walpro.autobuilder.model.MutationAcceptanceState state = ctx.mutationState();
            if (state == null && ctx.evaluationOutcome() != null) {
                state = ctx.evaluationOutcome().mutationState();
            }
            if (state != null) {
                if (s.contains("accepted") && state == com.walpro.autobuilder.model.MutationAcceptanceState.ACCEPTED) conditionMet = true;
                if (s.contains("rejected") && state == com.walpro.autobuilder.model.MutationAcceptanceState.REJECTED) conditionMet = true;
            }
        }

        if (s.contains("goalprogressstate") && ctx.goalProgress() != null) {
            if (s.contains("progressing") && ctx.goalProgress() == com.walpro.autobuilder.model.GoalProgressState.PROGRESSING) conditionMet = true;
            if (s.contains("stagnating") && ctx.goalProgress() == com.walpro.autobuilder.model.GoalProgressState.STAGNATING) conditionMet = true;
        }

        if (s.contains("hasplanneroutcome") && ctx.plannerOutcome() != null) conditionMet = true;
        if (s.contains("hasevolutionoutcome") && ctx.evolutionOutcome() != null) conditionMet = true;
        if (s.contains("hasevaluationoutcome") && ctx.evaluationOutcome() != null) conditionMet = true;

        return conditionMet;
    }

    /** T4-AI-1: typed steps ride DeliberationResult directly — the pipe-string form in the
     *  meta map is telemetry only. NONE-need and role-less steps are non-executable. */
    private List<MetaActionStep> extractMetaSteps(DeliberationResult dr) {
        if (dr == null || dr.metaSteps() == null) return List.of();
        return dr.metaSteps().stream()
                .filter(x -> x != null && x.targetRole() != null && !x.targetRole().isBlank())
                .filter(x -> x.need() != MetaActionStep.Need.NONE)
                .sorted(Comparator.comparingInt(MetaActionStep::index))
                .toList();
    }

    private void publishMetaOverride(
            CycleContextData beforeMeta,
            CycleContextData afterMeta,
            DeliberationMode mode,
            List<MetaActionStep> requestedSteps,
            MetaExecutionResult result
    ) {

        String ts = Instant.now().toString();

        MetaOverrideEvent event = new MetaOverrideEvent(
                afterMeta.cycleName(),
                "META_STEPS_ALL_SKIPPED",                 // keep emergent string
                MetaOverrideCategory.EXECUTION_BLOCKED,   // Variant A stays
                mode != null ? mode.name() : "UNDIRECTED",
                afterMeta.currentGoalId(),
                afterMeta.currentSubGoalId(),
                requestedSteps != null ? requestedSteps.size() : 0,
                result.executedCount(),
                result.skippedSteps().stream()
                        .map(s -> {
                            Map<String, Object> m = new java.util.HashMap<>();
                            m.put("index", s.index());
                            m.put("role", s.role());
                            m.put("reason", s.reason());
                            return m;
                        })
                        .toList(),
                extractSignalSnapshot(afterMeta),
                ts
        );

        String modeKey = mode != null ? mode.name() : "UNDIRECTED";
        for (SkippedMetaStep s : result.skippedSteps()) {
            metaOverrideLearningStore.record(new MetaSkipPatternKey(modeKey, s.role(), s.reason()));
        }
    }

    private Map<String, Double> extractSignalSnapshot(CycleContextData ctx) {
        Map<String, Double> out = new HashMap<>();
        if (ctx == null || ctx.signals() == null || ctx.signals().isEmpty()) {
            return out;
        }
        for (Map.Entry<SignalType, Signal> e : ctx.signals().entrySet()) {
            SignalType type = e.getKey();
            Signal s = e.getValue();
            if (type == null || s == null) continue;
            out.put(type.name(), s.strength());
        }
        return out;
    }

    private CycleContextData injectRequiredRoleSignalForModeIfDeadlocked(CycleContextData ctx, DeliberationMode mode) {
        if (ctx == null || mode == null) return ctx;

        // If mode allows multiple roles, we don’t force anything here.
        Set<String> allowed = deliberationGate.allowedRoles(mode);
        if (allowed == null || allowed.size() != 1) return ctx;

        String requiredRole = allowed.iterator().next().toLowerCase();

        SignalType requiredRoleSignal = switch (requiredRole) {
            case "planner" -> BasicSignals.ROLE_PLANNER;
            case "evolution" -> BasicSignals.ROLE_EVOLUTION;
            case "evaluation" -> BasicSignals.ROLE_EVALUATION;
            case "merge" -> BasicSignals.ROLE_MERGE;
            case "analysis" -> BasicSignals.ROLE_ANALYSIS;
            default -> null;
        };

        if (requiredRoleSignal == null) return ctx;

        // If it’s already strong enough, nothing to do.
        if (ctx.hasStrongSignal(requiredRoleSignal, ROLE_MIN_EXEC)) return ctx;

        // Inject the specific missing signal, with metadata describing deadlock.
        Map<String, Object> meta = new HashMap<>();
        meta.put("deadlock", true);
        meta.put("deadlock.mode", mode.name());
        meta.put("deadlock.requiredRole", requiredRole);
        meta.put("deadlock.reason", "MODE_REQUIRES_ROLE_SIGNAL_ABOVE_THRESHOLD");

        Signal injected = new Signal(
                requiredRoleSignal,
                DEADLOCK_INJECT_STRENGTH,
                0.25,
                0,
                "deadlock-inject",
                meta
        );

        // Role signals should replace role signals (as we already do elsewhere).
        return ctx.withSignalReplaceRoleSignals(injected);
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

    private boolean shouldEndCycle(CycleContextData ctx) {
        return ctx != null && ctx.merged();
    }

    private record SkippedMetaStep(int index, String role, String request, String reason) {}

    private record MetaExecutionResult(CycleContextData context,
                                       int executedCount,
                                       List<SkippedMetaStep> skippedSteps,
                                       boolean stopCycle) {}

    private enum StopDecision {
        NONE,
        STOP_META,
        STOP_CYCLE
    }
}