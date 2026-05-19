package com.walpro.autobuilder.core.service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import com.walpro.autobuilder.core.bootstrap.BuildSystemBootstrap;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.lifecycle.*;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshotService;
import com.walpro.autobuilder.memory.sms.SmsCycleIngestor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.walpro.autobuilder.client.LlmCircuitBreaker;
import com.walpro.autobuilder.config.AutobuilderConfigProperties;
import com.walpro.autobuilder.core.init.SystemResetConfig;
import com.walpro.autobuilder.core.persistence.CyclePersistenceService;
import com.walpro.autobuilder.goal.CoverageReviewResult;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutobuilderService {

    private final SeedCoordinator seedCoordinator;
    private final BuildSystemBootstrap buildSystemBootstrap;
    private final FileService fileService;
    private final AutobuilderConfigProperties autobuilderConfig;
    private final SystemResetConfig systemResetConfig;
    private final CycleContextCoordinator contextCoordinator;
    private final CycleExecutor cycleExecutor;
    private final CyclePersistenceService cyclePersistenceService;
    private final CycleLoggingService cycleLoggingService;
    private final SmsCycleIngestor smsCycleIngestor;
    private final GoalPlannerService goalPlannerService;

    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;
    private final LlmCircuitBreaker llmCircuitBreaker;
    private final OrchestrationSnapshotService orchestrationSnapshotService;

    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Executed context (bootstrap or normal cycle).
     * May be {@code null} before the first successful run.
     */
    private volatile CycleContextData context;

    /**
     * Backstop counter (T1-AK): incremented each time a subgoal is marked GIVEN_UP without a
     * preceding merge. Reset to zero on every successful merge. When it reaches
     * maxConsecutiveSubgoalExhaustionsWithoutMerge the loop is escalated to GOAL_EXHAUSTED +
     * SYSTEM_PAUSED.
     */
    private int consecutiveSubgoalExhaustionsWithoutMerge = 0;

    /**
     * T1-AH: stagnation circuit breaker counter. Incremented each time a cycle ends STAGNATING
     * without a merge. Reset on every successful merge. Activates the circuit breaker flag at
     * STAGNATION_BREAKER_THRESHOLD; escalates to SYSTEM_PAUSED at STAGNATION_PAUSE_THRESHOLD.
     */
    private int consecutiveStagnatingCyclesWithoutMerge = 0;
    private static final int STAGNATION_BREAKER_THRESHOLD = 5;
    private static final int STAGNATION_PAUSE_THRESHOLD = 10;

    /**
     * T2-AP: score plateau detection. Tracks the score at the last evaluation so we can detect
     * a flat band across consecutive cycles. NaN until the first evaluation runs.
     */
    private double lastEvaluatedScore = Double.NaN;

    /**
     * T2-CD: consecutive cycles where evolution produced the same fingerprint (REPETITION_DETECTED).
     * When this reaches SAME_FINGERPRINT_FULFILLED_THRESHOLD and the current subgoal has no
     * acceptance criteria, the snapshot eval score is checked; if stable and above 0.75, the
     * subgoal is promoted to FULFILLED rather than continuing to churn.
     */
    private int consecutiveSameFingerprintCycles = 0;

    /**
     * T1-EB: consecutive cycles that produced zero useful work (no merge, no mutation proposed,
     * no agent executed) while BUILD_BROKEN is active. Reset on any merge or agent execution.
     * When this reaches zeroWorkDeadlockLimit, the loop emits GOAL_EXHAUSTED + SYSTEM_PAUSED.
     */
    private int consecutiveZeroWorkCycles = 0;
    private static final int SAME_FINGERPRINT_FULFILLED_THRESHOLD = 3;
    private static final int PLATEAU_THRESHOLD = 5;
    private static final double PLATEAU_BAND = 0.03;

    /**
     * T1-BY: cap on how many consecutive times the T1-BP merge-suppression can fire for the
     * same subgoal without allowing eviction. Prevents immortal-subgoal deadlocks when a
     * subgoal keeps producing accepted-but-plateau merges indefinitely.
     */
    private static final int MAX_SUPPRESSED_EVICTIONS = 3;

    @Value("${autobuilder.goal.max-consecutive-subgoal-exhaustions:5}")
    private int maxConsecutiveSubgoalExhaustionsWithoutMerge = 5;

    /** Minimum GIVEN_UP children required before per-parent exhaustion fires (floor for the majority rule). */
    @Value("${autobuilder.goal.min-exhausted-children-floor:5}")
    private int minExhaustedChildrenFloor = 5;

    /** T1-EB: consecutive zero-work cycles with BUILD_BROKEN that trigger the deadlock halt. */
    @Value("${autobuilder.deadlock.zero-work-limit:5}")
    private int zeroWorkDeadlockLimit = 5;

    /**
     * Controls whether the Autobuilder bootstrap cycle is executed on startup.
     * Default: true (enabled in normal runtime).
     * In tests, we set this to false via application-test.properties.
     */
    @Value("${autobuilder.bootstrap.enabled:true}")
    private boolean bootstrapEnabled = true;

    /**
     * Controls whether the scheduled runAutobuilderCycle() should actually run.
     * Default: true in normal runtime. In tests: false.
     */
    @Value("${autobuilder.scheduler.enabled:true}")
    private boolean schedulerEnabled = true;

    private boolean systemPaused = false;

    public Optional<CycleContextData> getContext() {
        return Optional.ofNullable(context);
    }

    /**
     * T4-AM: merges a signal into the carried-over cycle context so the next cycle picks it up.
     * Safe to call from REST write-actions; acquires the cycle lock to avoid racing with a
     * concurrent cycle execution. No-op when no context has been built yet (service not warmed).
     */
    public void injectSignal(Signal signal) {
        if (signal == null) return;
        lock.lock();
        try {
            if (context == null) {
                log.debug("injectSignal {} ignored — no context yet", signal.type());
                return;
            }
            context = context.withSignal(signal);
            log.info("injectSignal: {} strength={} source={}", signal.type(), signal.strength(), signal.source());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes a single signal by type from the carried-over cycle context.
     * No-op when no context exists. Safe to call from REST handlers.
     */
    public void removeSignal(SignalType type) {
        if (type == null) return;
        lock.lock();
        try {
            if (context == null) {
                log.debug("removeSignal {} ignored — no context yet", type);
                return;
            }
            context = context.withoutSignal(type);
            log.info("removeSignal: {} removed", type);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clears the halt signals ({@code SYSTEM_PAUSED} and {@code GOAL_EXHAUSTED}) from the
     * carried-over cycle context and flips the result state back to {@code CONTINUE} so the
     * scheduler resumes on the next tick. No-op when no context exists (service not warmed).
     * Intended for the "resume loop" action in the UI and for coverage-review follow-ups.
     */
    public void clearHaltSignals() {
        lock.lock();
        try {
            if (context == null) {
                log.debug("clearHaltSignals ignored — no context yet");
                return;
            }
            context = context
                    .withoutSignal(BasicSignals.SYSTEM_PAUSED)
                    .withoutSignal(BasicSignals.GOAL_EXHAUSTED)
                    .withCycleResultState(CycleResultState.CONTINUE);
            log.info("clearHaltSignals: SYSTEM_PAUSED + GOAL_EXHAUSTED cleared — loop will resume on next cycle");
        } finally {
            lock.unlock();
        }
    }

    @PostConstruct
    public void init() throws IOException {
        if (!bootstrapEnabled) {
            log.warn("Autobuilder bootstrap disabled via configuration (autobuilder.bootstrap.enabled=false)");
            return;
        }

        if (autobuilderConfig.isResetOnStart()) {
            log.warn("Autobuilder system reset triggered via config (AutobuilderService)");
            systemResetConfig.resetSystem();
        }

        seedCoordinator.seedFromFileIfNecessary();

        fileService.ensureFolderExists("output");

        // T3-BT: seed build system skeleton into output/ before cycle 1 so that mvn compile
        // succeeds on the first invocation and BUILD_FIX is not injected at session start.
        buildSystemBootstrap.seedBuildSystemIfNecessary();

        runAutobuilderCycle();
    }

    @Scheduled(fixedDelayString = "${autobuilder.scheduler.inter-cycle-delay-ms:1000}")
    public void runAutobuilderCycle() {
        lock.lock();
        try {
            runAutobuilderCycleLocked();
        } finally {
            lock.unlock();
        }
    }

    private void runAutobuilderCycleLocked() {
        if (context != null && context.cycleResultState() != null) {
            if (context.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)) {
                if (!systemPaused) {
                    systemPaused = true;
                    log.info("System is paused (cycle {}). Skipping run.", context.cycleName());
                }
                return;
            }

            // T2-D: analysis agent believes the current subgoal may already be complete.
            // Pause for human review rather than continuing to cycle.
            if (context.hasStrongSignal(BasicSignals.POTENTIALLY_COMPLETE, 0.50)) {
                log.warn("POTENTIALLY_COMPLETE signal detected (subGoalId={}). Pausing for human review — verify whether the goal is fulfilled before resuming.",
                        context.currentSubGoalId());
                context = context.withSignal(Signal.of(BasicSignals.SYSTEM_PAUSED, 1.0, 0.0, "potentially-complete-review"));
                return;
            }

            switch (context.cycleResultState()) {
                case GOAL_FULFILLED -> {
                    if (!systemPaused) {
                        systemPaused = true;
                        log.info("Goal fulfilled. Pausing Autobuilder.");
                    }
                    return;
                }
                case SUBGOAL_EXHAUSTED -> {
                    String subGoalId = context.currentSubGoalId();

                    // T1-AR Fix 2: a subgoal that produced a successful merge in the just-completed
                    // cycle must not be given up. Clear the exhaustion signal and continue.
                    // T1-BY: cap suppression at MAX_SUPPRESSED_EVICTIONS to prevent a plateau-merging
                    // subgoal from becoming immortal. After the cap is hit, allow eviction to proceed.
                    if (context.merged()) {
                        int suppressedCount = goalPlannerService.getGoalMetadataInt(subGoalId, "consecutiveSuppressedEvictions");
                        if (suppressedCount < MAX_SUPPRESSED_EVICTIONS) {
                            log.info("SUBGOAL_EXHAUSTED but merge occurred — suppressing eviction (count={}/{}) for subGoalId={}.",
                                    suppressedCount + 1, MAX_SUPPRESSED_EVICTIONS, subGoalId);
                            goalPlannerService.incrementGoalMetadataInt(subGoalId, "consecutiveSuppressedEvictions");
                            context = context.withoutSignal(BasicSignals.SUBGOAL_EXHAUSTED);
                            break;
                        }
                        log.warn("SUBGOAL_EXHAUSTED + merge occurred but suppression cap reached ({}/{}) — allowing eviction for subGoalId={}.",
                                suppressedCount, MAX_SUPPRESSED_EVICTIONS, subGoalId);
                    }

                    // T1-AR Fix 1: when all subgoals are already given up (currentSubGoalId=null),
                    // applyGoalProgress(null, GIVEN_UP) is a no-op. Halt immediately instead of
                    // looping through recovery cycles that produce no useful work.
                    if (subGoalId == null) {
                        log.error("SUBGOAL_EXHAUSTED with null currentSubGoalId — no subgoal to recover. Halting system.");
                        context = context
                                .withSignal(Signal.of(BasicSignals.GOAL_EXHAUSTED, 1.0, 0.0, "null-subgoal-halt"))
                                .withSignal(Signal.of(BasicSignals.SYSTEM_PAUSED, 1.0, 0.0, "null-subgoal-halt"));
                        return;
                    }

                    // T1-CF: if all acceptance criteria are already cumulatively satisfied, the
                    // subgoal is done — evolution simply ran out of new diffs to generate. Promote
                    // to FULFILLED rather than abandoning work that is provably complete.
                    if (goalPlannerService.areCriteriaCumulativelySatisfied(subGoalId)) {
                        log.info("SUBGOAL_EXHAUSTED but all criteria met — promoting to FULFILLED instead of GIVEN_UP (subGoalId={}).", subGoalId);
                        consecutiveStagnatingCyclesWithoutMerge = 0;
                        lastEvaluatedScore = Double.NaN;
                        context = context.withPlateauCycles(0).withoutSignal(BasicSignals.SCORE_PLATEAU);
                        goalPlannerService.applyGoalProgress(subGoalId, GoalProgressState.FULFILLED,
                                "exhausted but all criteria cumulatively satisfied");
                        context = context
                                .withoutSignal(BasicSignals.SUBGOAL_EXHAUSTED)
                                .withoutSignal(BasicSignals.STAGNATION)
                                .withoutSignal(BasicSignals.REPETITION_DETECTED)
                                .withoutSignal(BasicSignals.LOW_CONFIDENCE)
                                .withStagnationCircuitBreakerActive(false)
                                .withGoalProgress(GoalProgressState.NOT_EVALUATED);
                        break;
                    }

                    consecutiveSubgoalExhaustionsWithoutMerge++;
                    consecutiveStagnatingCyclesWithoutMerge = 0;
                    lastEvaluatedScore = Double.NaN;
                    context = context.withPlateauCycles(0).withoutSignal(BasicSignals.SCORE_PLATEAU);
                    log.warn("Subgoal exhausted (subGoalId={}, consecutiveWithoutMerge={}/{}). Marking GIVEN_UP and recovering.",
                            subGoalId, consecutiveSubgoalExhaustionsWithoutMerge, maxConsecutiveSubgoalExhaustionsWithoutMerge);

                    goalPlannerService.applyGoalProgress(subGoalId, GoalProgressState.GIVEN_UP,
                            "exhausted after " + consecutiveSubgoalExhaustionsWithoutMerge + " cycles without merge");

                    // T2-AL: if a strict majority of siblings are GIVEN_UP with no completions,
                    // give up on the parent branch entirely rather than replanning more siblings.
                    goalPlannerService.exhaustParentIfStuck(subGoalId, minExhaustedChildrenFloor);

                    if (consecutiveSubgoalExhaustionsWithoutMerge >= maxConsecutiveSubgoalExhaustionsWithoutMerge) {
                        log.error("Exhaustion runaway detected: {} consecutive subgoal exhaustions without a merge. Escalating to root exhaustion — manual input required.",
                                consecutiveSubgoalExhaustionsWithoutMerge);
                        // Emit GOAL_EXHAUSTED + SYSTEM_PAUSED signals; the cycle continues normally
                        // so deriveCycleResult picks up GOAL_EXHAUSTED → next scheduled run halts.
                        context = context
                                .withSignal(Signal.of(BasicSignals.GOAL_EXHAUSTED, 1.0, 0.0, "exhaustion-runaway"))
                                .withSignal(Signal.of(BasicSignals.SYSTEM_PAUSED, 1.0, 0.0, "exhaustion-runaway"));
                    } else {
                        context = context
                                .withoutSignal(BasicSignals.SUBGOAL_EXHAUSTED)
                                .withoutSignal(BasicSignals.STAGNATION)
                                .withoutSignal(BasicSignals.REPETITION_DETECTED)
                                .withoutSignal(BasicSignals.LOW_CONFIDENCE)
                                .withStagnationCircuitBreakerActive(false)
                                .withGoalProgress(GoalProgressState.NOT_EVALUATED);
                        if (!context.hasStrongSignal(BasicSignals.REPLAN_REQUESTED, 0.50)) {
                            context = context.withSignal(Signal.of(BasicSignals.REPLAN_REQUESTED, 1.0, 0.20, "exhaustion-recovery"));
                        }
                    }
                }
                case GOAL_EXHAUSTED -> {
                    String rootGoalId = context.currentGoalId();
                    // T1-DJ: defensive regression check — deriveCycleResult should have returned
                    // GOAL_FULFILLED for a COMPLETED root; reaching here with COMPLETED status
                    // means the guard was bypassed.
                    GoalEntity rootGoal = goalPlannerService.getRootGoal();
                    if (rootGoal != null && rootGoal.getStatus() == GoalStatus.COMPLETED) {
                        log.warn("T1-DJ: GOAL_EXHAUSTED handler reached with COMPLETED root (goalId={}) — " +
                                "deriveCycleResult should have returned GOAL_FULFILLED. Possible regression.", rootGoalId);
                        if (behaviourLogger != null) {
                            behaviourLogger.guardViolated(context.cycleName(), "T1-DJ",
                                    "GOAL_EXHAUSTED handler reached with COMPLETED root goalId=" + rootGoalId);
                        }
                    }
                    log.warn("Root goal exhausted (goalId={}). Pausing — manual input required.", rootGoalId);
                    context = context.withSignal(Signal.of(BasicSignals.SYSTEM_PAUSED, 1.0, 0.0, "root-exhaustion"));
                    return;
                }
                case STAGNATING -> {
                    // T2-AA: track cumulative stagnation per subgoal for pre-emptive exhaustion.
                    if (context.currentSubGoalId() != null) {
                        goalPlannerService.incrementGoalMetadataInt(context.currentSubGoalId(), "cumulativeStagnatingCycles");
                    }
                    // T1-AH: stagnation circuit breaker — count stagnating cycles without a merge.
                    // T1-BU: do not count null-subgoal stagnation cycles — with no active subgoal
                    // no agent can produce a merge, so this is a structural wait state (T1-AD
                    // recovery gap), not a stuck subgoal. Counting it would re-activate the breaker
                    // and permanently block the T1-AD null-subgoal re-plan.
                    if (context.currentSubGoalId() == null) {
                        log.debug("Cycle result STAGNATING with no active subgoal — skipping circuit-breaker count.");
                        break;
                    }
                    consecutiveStagnatingCyclesWithoutMerge++;
                    log.warn("Cycle result STAGNATING (consecutiveWithoutMerge={}/{}).",
                            consecutiveStagnatingCyclesWithoutMerge, STAGNATION_PAUSE_THRESHOLD);

                    if (consecutiveStagnatingCyclesWithoutMerge >= STAGNATION_PAUSE_THRESHOLD) {
                        log.error("Stagnation runaway: {} consecutive stagnating cycles without a merge. Pausing — manual input required.",
                                consecutiveStagnatingCyclesWithoutMerge);
                        context = context
                                .withStagnationCircuitBreakerActive(true)
                                .withSignal(Signal.of(BasicSignals.SYSTEM_PAUSED, 1.0, 0.0, "stagnation-runaway"));
                        return;
                    }

                    if (consecutiveStagnatingCyclesWithoutMerge >= STAGNATION_BREAKER_THRESHOLD) {
                        log.warn("Stagnation circuit breaker activated (consecutiveWithoutMerge={}). Suppressing REPLAN_REQUESTED from interpretation; planner in prune-only mode.",
                                consecutiveStagnatingCyclesWithoutMerge);
                        context = context.withStagnationCircuitBreakerActive(true);
                    }

                    if (!context.hasStrongSignal(BasicSignals.REPLAN_REQUESTED, 0.50)) {
                        context = context.withSignal(Signal.of(BasicSignals.REPLAN_REQUESTED, 1.0, 0.20, "stagnation-recovery"));
                    }
                    context = context.withSignal(Signal.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, 0.10, "stagnation-recovery"));
                }
                case CONTINUE -> {
                    // next scheduled run
                }
            }
        }

        systemPaused = false;

        if (llmCircuitBreaker.isOpen()) {
            String reason = llmCircuitBreaker.pauseReason();
            log.error("LLM circuit breaker is open ({}). Pausing autobuilder.", reason);
            context = (context != null ? context : CycleContextData.builder().build())
                    .withSignal(Signal.of(BasicSignals.SYSTEM_PAUSED, 1.0, 0.0, "llm-circuit-breaker"));
            return;
        }

        if (!schedulerEnabled) {
            log.warn("Autobuilder scheduler disabled via configuration (autobuilder.scheduler.enabled=false)");
            return;
        }

        Optional<CycleContextData> initialContext = contextCoordinator.prepareRuntimeContext(context);
        if (initialContext.isEmpty()) {
            log.warn("Aborting cycle: unable to prepare context.");
            return;
        }

        context = cycleExecutor.runCycle(initialContext.get());

        // T2-CC: evolution declared #NoDiff — the LLM's #NoDiff response is an explicit assertion
        // that the current output already satisfies all criteria for the subgoal. Promote directly
        // to FULFILLED without requiring criteriaPassStatus (which is only written by evaluation and
        // would be absent when the implementation arrived via a prior subgoal's deferred merge).
        if (context.hasStrongSignal(BasicSignals.EVOLUTION_NOOP, 0.5)) {
            Signal noOpSignal = context.getSignal(BasicSignals.EVOLUTION_NOOP);
            context = context.withoutSignal(BasicSignals.EVOLUTION_NOOP);
            String noOpSubGoalId = context.currentSubGoalId();
            // T1-EE: evaluation may have fulfilled the signal's originating subgoal mid-cycle and
            // advanced the pointer. Reject the signal if it names a different subgoal than the
            // current one — promoting the wrong subgoal would leave it FULFILLED with zero evidence.
            if (noOpSignal != null && noOpSignal.meta() != null) {
                Object signalSubGoalId = noOpSignal.meta().get("subGoalId");
                if (signalSubGoalId != null && !signalSubGoalId.toString().equals(noOpSubGoalId)) {
                    log.debug("T1-EE: EVOLUTION_NOOP was for subGoalId={} but current is {} — discarding stale signal",
                            signalSubGoalId, noOpSubGoalId);
                    noOpSubGoalId = null;
                }
            }
            if (noOpSubGoalId != null) {
                // T1-DN: outer gate — refuse promotion when concrete state contradicts the LLM.
                String refusalReason = whyRefuseNoDiffPromotion(context);
                if (refusalReason != null) {
                    log.warn("T1-DN: refusing #NoDiff promotion — {} (subGoalId={})", refusalReason, noOpSubGoalId);
                    context = context.withSignal(Signal.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, 0.1, "noDiff-trust-gate:" + refusalReason));
                } else {
                    log.info("Evolution noOp — #NoDiff implies current output satisfies all criteria. Promoting to FULFILLED (subGoalId={}).", noOpSubGoalId);
                    consecutiveStagnatingCyclesWithoutMerge = 0;
                    consecutiveSameFingerprintCycles = 0;
                    lastEvaluatedScore = Double.NaN;
                    context = context.withPlateauCycles(0).withoutSignal(BasicSignals.SCORE_PLATEAU);
                    goalPlannerService.applyGoalProgress(noOpSubGoalId, GoalProgressState.FULFILLED,
                            "evolution #NoDiff — output already satisfies all criteria (T2-CC)");
                    context = context
                            .withoutSignal(BasicSignals.SUBGOAL_EXHAUSTED)
                            .withoutSignal(BasicSignals.STAGNATION)
                            .withoutSignal(BasicSignals.REPETITION_DETECTED)
                            .withoutSignal(BasicSignals.LOW_CONFIDENCE)
                            .withStagnationCircuitBreakerActive(false)
                            .withGoalProgress(GoalProgressState.NOT_EVALUATED);
                }
            }
        }

        // T1-EB: build-blocked deadlock breaker. When no agent runs and no mutation is proposed
        // while BUILD_BROKEN is active, increment a counter. Halt once the limit is reached.
        // "Zero work" = nothing merged, no mutation proposed, and no agent produced an outcome.
        if (context.hasStrongSignal(BasicSignals.BUILD_BROKEN, 0.5)) {
            boolean anyAgentRan = context.evolutionOutcome() != null
                    || context.evaluationOutcome() != null
                    || context.plannerOutcome() != null
                    || context.analysisOutcome() != null;
            boolean zeroWork = !context.merged()
                    && context.mutationState() == null
                    && !context.rolledBackThisCycle()
                    && !anyAgentRan;
            if (zeroWork) {
                consecutiveZeroWorkCycles++;
                log.warn("T1-EB: zero-work cycle with BUILD_BROKEN active (consecutiveZeroWorkCycles={}/{})",
                        consecutiveZeroWorkCycles, zeroWorkDeadlockLimit);
                if (consecutiveZeroWorkCycles >= zeroWorkDeadlockLimit) {
                    log.error("T1-EB: build-blocked deadlock — {} consecutive zero-work cycles. Pausing — manual intervention required.",
                            consecutiveZeroWorkCycles);
                    if (behaviourLogger != null) {
                        behaviourLogger.systemPaused(context.cycleName(), "build-blocked-deadlock", consecutiveZeroWorkCycles);
                    }
                    context = context
                            .withSignal(Signal.of(BasicSignals.GOAL_EXHAUSTED, 1.0, 0.0, "build-blocked-deadlock"))
                            .withSignal(Signal.of(BasicSignals.SYSTEM_PAUSED, 1.0, 0.0, "build-blocked-deadlock"));
                }
            } else {
                consecutiveZeroWorkCycles = 0;
            }
        } else {
            consecutiveZeroWorkCycles = 0;
        }

        context = trackScorePlateau(context);

        // T2-CD: update consecutive same-fingerprint counter
        if (context.merged()) {
            consecutiveSameFingerprintCycles = 0;
        } else if (context.hasSignal(BasicSignals.REPETITION_DETECTED)) {
            consecutiveSameFingerprintCycles++;
        } else if (context.hasSignal(BasicSignals.EVOLUTION_FINGERPRINT_CHANGED)) {
            consecutiveSameFingerprintCycles = 0;
        }

        // T2-CD: fingerprint-repeat FULFILLED inference for criteria-less subgoals
        if (!context.merged() && consecutiveSameFingerprintCycles >= SAME_FINGERPRINT_FULFILLED_THRESHOLD) {
            String cdSubGoalId = context.currentSubGoalId();
            java.util.List<String> cdCriteria = context.currentSubGoalCriteria();
            boolean criteriaLess = (cdCriteria == null || cdCriteria.isEmpty());
            if (cdSubGoalId != null && criteriaLess) {
                var snap = orchestrationSnapshotService.fetchLatestSnapshot(context.currentGoalId(), cdSubGoalId, 5);
                Double scoreNow = snap != null ? snap.evalScoreNow() : null;
                Double scoreBefore = snap != null ? snap.evalScoreBefore() : null;
                if (scoreNow != null && scoreNow >= 0.75
                        && (scoreBefore == null || scoreNow >= scoreBefore - 0.05)) {
                    log.info("{} consecutive same-fingerprint cycles + stable score ({:.3f}) — promoting criteria-less subgoal to FULFILLED (subGoalId={}).",
                            consecutiveSameFingerprintCycles, scoreNow, cdSubGoalId);
                    consecutiveSameFingerprintCycles = 0;
                    consecutiveStagnatingCyclesWithoutMerge = 0;
                    lastEvaluatedScore = Double.NaN;
                    context = context.withPlateauCycles(0).withoutSignal(BasicSignals.SCORE_PLATEAU);
                    String cdResolution = String.format(java.util.Locale.ROOT,
                            "fingerprint-repeat FULFILLED: criteria-less subgoal with stable score=%.2f over %d cycles (T2-CD)",
                            scoreNow, consecutiveSameFingerprintCycles);
                    goalPlannerService.applyGoalProgress(cdSubGoalId, GoalProgressState.FULFILLED, cdResolution);
                    context = context
                            .withoutSignal(BasicSignals.REPETITION_DETECTED)
                            .withoutSignal(BasicSignals.STAGNATION)
                            .withoutSignal(BasicSignals.LOW_CONFIDENCE)
                            .withStagnationCircuitBreakerActive(false)
                            .withGoalProgress(GoalProgressState.NOT_EVALUATED);
                } else {
                    log.info("{} consecutive same-fingerprint cycles but score insufficient (scoreNow={}) — continuing (subGoalId={}).",
                            consecutiveSameFingerprintCycles, scoreNow, cdSubGoalId);
                }
            }
        }

        if (context.merged()) {
            consecutiveSubgoalExhaustionsWithoutMerge = 0;
            consecutiveStagnatingCyclesWithoutMerge = 0;
            consecutiveSameFingerprintCycles = 0;
            consecutiveZeroWorkCycles = 0;
            if (context.stagnationCircuitBreakerActive()) {
                log.info("Merge detected — resetting stagnation circuit breaker.");
                context = context.withStagnationCircuitBreakerActive(false);
            }
        }

        context = cyclePersistenceService.persistSnapshot(context);
        cycleLoggingService.logCycleSummary(context);

        smsCycleIngestor.ingestCycle(context);

        log.info("Cycle {} complete. Result: {}, Merged: {}", context.cycleName(), context.cycleResultState(), context.merged());
    }

    /**
     * T1-DN: returns null when #NoDiff promotion is safe, or a short reason string when it
     * should be refused. Refuses if the build is known-broken or any criterion is unmet.
     */
    private String whyRefuseNoDiffPromotion(CycleContextData context) {
        BuildOutcome buildOutcome = context.buildOutcome();
        if (buildOutcome != null && buildOutcome.failed()) {
            return "build-failed";
        }
        Map<Integer, Boolean> criteriaStatus = context.cumulativeCriteriaStatus();
        if (criteriaStatus != null && criteriaStatus.containsValue(Boolean.FALSE)) {
            return "criteria-unmet";
        }
        return null;
    }

    // T2-AP: update plateauCycles based on this cycle's evaluation outcome.
    private CycleContextData trackScorePlateau(CycleContextData ctx) {
        if (ctx.evaluationOutcome() == null) {
            return ctx; // no evaluation this cycle — plateau unchanged
        }
        double currentScore = ctx.score();
        MutationAcceptanceState state = ctx.mutationState();

        if (state == MutationAcceptanceState.ACCEPTED) {
            int plateau;
            if (!Double.isNaN(lastEvaluatedScore) && Math.abs(currentScore - lastEvaluatedScore) < PLATEAU_BAND) {
                plateau = ctx.plateauCycles() + 1;
            } else {
                plateau = 0; // genuine improvement or first evaluation
            }
            lastEvaluatedScore = currentScore;
            ctx = ctx.withPlateauCycles(plateau);
            if (plateau >= PLATEAU_THRESHOLD) {
                log.warn("Score plateau: {} consecutive flat accepted cycles (score={}). Emitting SCORE_PLATEAU.", plateau, currentScore);
                ctx = ctx.withSignal(Signal.of(BasicSignals.SCORE_PLATEAU, 1.0, 0.05, "score-plateau"));
            }
        } else {
            // Mutation rejected — system already reacting, reset plateau
            lastEvaluatedScore = currentScore;
            ctx = ctx.withPlateauCycles(0).withoutSignal(BasicSignals.SCORE_PLATEAU);
        }
        return ctx;
    }

    public void bootstrapOnce() {
        try {
            init(); // respects reset-on-start flag and existing bootstrap logic
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Force a full system reset: purges the goal graph, wipes mutation/output/snapshot folders,
     * re-reads {@code bootstrap/seed.txt} as the new root goal, then runs a fresh cycle. Used by
     * the graph browser's "Reset" button so the loop can be restarted without restarting the JVM.
     */
    public void forceReset() {
        lock.lock();
        try {
            log.warn("Manual reset requested via API — clearing graph, filesystem, and goal stack");
            systemResetConfig.resetSystemForce();

            fileService.ensureFolderExists("output");

            consecutiveSubgoalExhaustionsWithoutMerge = 0;
            consecutiveStagnatingCyclesWithoutMerge = 0;
            consecutiveSameFingerprintCycles = 0;
            consecutiveZeroWorkCycles = 0;
            lastEvaluatedScore = Double.NaN;
            systemPaused = false;
            context = null;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
        runAutobuilderCycle();
    }

    /**
     * Runs a coverage retrospective against the root goal and injects any missing features
     * as new first-wave subgoals.  If gaps are found, clears SYSTEM_PAUSED and GOAL_EXHAUSTED
     * from the cycle context so the scheduler can resume.
     *
     * @return list of injected feature descriptions; empty if no gaps found
     */
    public CoverageReviewResult triggerCoverageReview() {
        lock.lock();
        try {
            CoverageReviewResult result = goalPlannerService.triggerManualCoverageReview();
            if (!result.injected().isEmpty() && context != null) {
                context = context
                        .withoutSignal(BasicSignals.SYSTEM_PAUSED)
                        .withoutSignal(BasicSignals.GOAL_EXHAUSTED)
                        .withCycleResultState(CycleResultState.CONTINUE);
                log.info("coverage-review: cleared SYSTEM_PAUSED + GOAL_EXHAUSTED — loop will resume on next cycle");
            }
            return result;
        } finally {
            lock.unlock();
        }
    }
}
