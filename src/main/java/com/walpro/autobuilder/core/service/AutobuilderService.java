package com.walpro.autobuilder.core.service;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import com.walpro.autobuilder.memory.sms.SmsCycleIngestor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.walpro.autobuilder.client.LlmCircuitBreaker;
import com.walpro.autobuilder.config.AutobuilderConfigProperties;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SeedCoordinator;
import com.walpro.autobuilder.core.lifecycle.CycleContextCoordinator;
import com.walpro.autobuilder.core.init.SystemResetConfig;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.CycleExecutor;
import com.walpro.autobuilder.core.persistence.CyclePersistenceService;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutobuilderService {

    private final SeedCoordinator seedCoordinator;
    private final FileService fileService;
    private final AutobuilderConfigProperties autobuilderConfig;
    private final SystemResetConfig systemResetConfig;
    private final CycleContextCoordinator contextCoordinator;
    private final CycleExecutor cycleExecutor;
    private final CyclePersistenceService cyclePersistenceService;
    private final CycleLoggingService cycleLoggingService;
    private final SmsCycleIngestor smsCycleIngestor;
    private final GoalPlannerService goalPlannerService;
    private final LlmCircuitBreaker llmCircuitBreaker;

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

    public Optional<CycleContextData> getContext() {
        return Optional.ofNullable(context);
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
                log.info("System is paused (cycle {}). Skipping run.", context.cycleName());
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
                    log.info("Goal fulfilled. Pausing Autobuilder.");
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

                    consecutiveSubgoalExhaustionsWithoutMerge++;
                    consecutiveStagnatingCyclesWithoutMerge = 0;
                    lastEvaluatedScore = Double.NaN;
                    context = context.withPlateauCycles(0).withoutSignal(BasicSignals.SCORE_PLATEAU);
                    log.warn("Subgoal exhausted (subGoalId={}, consecutiveWithoutMerge={}/{}). Marking GIVEN_UP and recovering.",
                            subGoalId, consecutiveSubgoalExhaustionsWithoutMerge, maxConsecutiveSubgoalExhaustionsWithoutMerge);

                    goalPlannerService.applyGoalProgress(subGoalId, GoalProgressState.GIVEN_UP);

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
                        log.debug("Cycle result STAGNATING with no active subgoal — skipping circuit-breaker count (T1-BU).");
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
        context = trackScorePlateau(context);

        if (context.merged()) {
            consecutiveSubgoalExhaustionsWithoutMerge = 0;
            consecutiveStagnatingCyclesWithoutMerge = 0;
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
}
