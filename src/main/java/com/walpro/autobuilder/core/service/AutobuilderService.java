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
     * Backstop counter (T2-AK): incremented each time a subgoal is marked GIVEN_UP without a
     * preceding merge. Reset to zero on every successful merge. When it reaches
     * maxConsecutiveSubgoalExhaustionsWithoutMerge the loop is escalated to GOAL_EXHAUSTED +
     * SYSTEM_PAUSED.
     */
    private int consecutiveSubgoalExhaustionsWithoutMerge = 0;

    @Value("${autobuilder.goal.max-consecutive-subgoal-exhaustions:5}")
    private int maxConsecutiveSubgoalExhaustionsWithoutMerge = 5;

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
            switch (context.cycleResultState()) {
                case GOAL_FULFILLED -> {
                    log.info("Goal fulfilled. Pausing Autobuilder.");
                    return;
                }
                case SUBGOAL_EXHAUSTED -> {
                    String subGoalId = context.currentSubGoalId();
                    if (context.hasSignal(BasicSignals.SYSTEM_PAUSED)) {
                        log.warn("SUBGOAL_EXHAUSTED but SYSTEM_PAUSED already set — skipping recovery.");
                        return;
                    }
                    consecutiveSubgoalExhaustionsWithoutMerge++;
                    log.warn("Subgoal exhausted (subGoalId={}, consecutiveWithoutMerge={}/{}). Marking GIVEN_UP and recovering.",
                            subGoalId, consecutiveSubgoalExhaustionsWithoutMerge, maxConsecutiveSubgoalExhaustionsWithoutMerge);

                    goalPlannerService.applyGoalProgress(subGoalId, GoalProgressState.GIVEN_UP);

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
                                .withSignal(Signal.of(BasicSignals.REPLAN_REQUESTED, 1.0, 0.05, "exhaustion-recovery"));
                    }
                }
                case GOAL_EXHAUSTED -> {
                    String rootGoalId = context.currentGoalId();
                    log.warn("Root goal exhausted (goalId={}). Pausing — manual input required.", rootGoalId);
                    context = context.withSignal(Signal.of(BasicSignals.SYSTEM_PAUSED, 1.0, 0.0, "root-exhaustion"));
                    return;
                }
                case STAGNATING -> {
                    log.warn("Cycle result STAGNATING — injecting divergence and replan signals for recovery.");
                    // T2-AA: track cumulative stagnation per subgoal for pre-emptive exhaustion.
                    if (context.currentSubGoalId() != null) {
                        goalPlannerService.incrementGoalMetadataInt(context.currentSubGoalId(), "cumulativeStagnatingCycles");
                    }
                    context = context
                            .withSignal(Signal.of(BasicSignals.REPLAN_REQUESTED, 1.0, 0.05, "stagnation-recovery"))
                            .withSignal(Signal.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, 0.10, "stagnation-recovery"));
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

        if (context.merged()) {
            consecutiveSubgoalExhaustionsWithoutMerge = 0;
        }

        context = cyclePersistenceService.persistSnapshot(context);
        cycleLoggingService.logCycleSummary(context);

        smsCycleIngestor.ingestCycle(context);

        log.info("Cycle {} complete. Result: {}, Merged: {}", context.cycleName(), context.cycleResultState(), context.merged());
    }

    public void bootstrapOnce() {
        try {
            init(); // respects reset-on-start flag and existing bootstrap logic
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
