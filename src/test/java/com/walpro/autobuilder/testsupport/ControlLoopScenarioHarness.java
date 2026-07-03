package com.walpro.autobuilder.testsupport;

import com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService;
import com.walpro.autobuilder.client.LlmCircuitBreaker;
import com.walpro.autobuilder.config.AutobuilderConfigProperties;
import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.bootstrap.BuildSystemBootstrap;
import com.walpro.autobuilder.core.init.SystemResetConfig;
import com.walpro.autobuilder.core.lifecycle.CycleContextCoordinator;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.CycleExecutor;
import com.walpro.autobuilder.core.lifecycle.CycleResultState;
import com.walpro.autobuilder.core.lifecycle.RootConvergenceTracker;
import com.walpro.autobuilder.core.lifecycle.SeedCoordinator;
import com.walpro.autobuilder.core.persistence.CyclePersistenceService;
import com.walpro.autobuilder.core.service.AutobuilderService;
import com.walpro.autobuilder.core.service.CycleLoggingService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalConsolidationService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.GoalType;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshotService;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import com.walpro.autobuilder.memory.sms.SmsCycleIngestor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T5-AF cycle-scenario layer: drives the REAL control-loop handler ladder
 * ({@code AutobuilderService.runAutobuilderCycle} processing the previous cycle's result state)
 * against a REAL {@code GoalPlannerService} goal tree, with the two nondeterministic inputs
 * scripted — the cycle execution outcome (what result state the next cycle ends in) and the
 * criteria-verification answers (what the LLM verifier would say at the give-up edge).
 *
 * <p>Why this altitude: the give-up-edge ladder (banked-criteria promotion → verify-and-promote →
 * T4-BP-4 residue rescope → T2-EI 1b retries → T2-EJ division → give-up → runaway brake) is
 * SEQUENCED here, across cycles, and no unit test exercises that sequencing — field runs are the
 * only place it executes, and they visit each edge rarely and unreproducibly (T2-EJ's division
 * waited three weeks for its first field firing). Scripting the cycle result rather than the raw
 * LLM chat response keeps the scenario deterministic while every goal-tree transition it asserts
 * on is produced by production code.
 *
 * <p>Everything downstream of the handler (persistence, logging, ingestion, convergence tracker)
 * is pass-through; the executor echoes back a context whose result state the scenario chooses
 * per cycle via {@link #scriptCycleResults}.
 */
public final class ControlLoopScenarioHarness {

    public final GoalPlannerService goalPlannerService;
    public final CriteriaVerificationService verifier;
    public final AutobuilderService service;

    private final CycleExecutor cycleExecutor;

    private ControlLoopScenarioHarness(GoalPlannerService goalPlannerService,
                                       CriteriaVerificationService verifier,
                                       AutobuilderService service,
                                       CycleExecutor cycleExecutor) {
        this.goalPlannerService = goalPlannerService;
        this.verifier = verifier;
        this.service = service;
        this.cycleExecutor = cycleExecutor;
    }

    public static ControlLoopScenarioHarness create() {
        FileService fileService = mock(FileService.class);
        GoalGraphService goalGraphService = mock(GoalGraphService.class);
        GoalPlannerConfigProperties goalPlannerConfig = mock(GoalPlannerConfigProperties.class);
        try {
            doNothing().when(fileService).writeFile(anyString(), anyString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).setParent(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).save(any(GoalEntity.class));

        GoalPlannerService goalPlannerService =
                new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);

        CycleExecutor cycleExecutor = mock(CycleExecutor.class);
        CycleContextCoordinator contextCoordinator = mock(CycleContextCoordinator.class);
        CyclePersistenceService cyclePersistenceService = mock(CyclePersistenceService.class);
        RootConvergenceTracker rootConvergenceTracker = mock(RootConvergenceTracker.class);
        // Handler-in, handler-out: preparation, persistence and the brake are identity functions
        // so the context the ladder produced is exactly the context the next cycle carries.
        lenient().when(contextCoordinator.prepareRuntimeContext(any()))
                .thenAnswer(inv -> Optional.ofNullable(inv.getArgument(0)));
        lenient().when(cyclePersistenceService.persistSnapshot(any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(rootConvergenceTracker.track(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        AutobuilderService service = new AutobuilderService(
                mock(SeedCoordinator.class),
                mock(BuildSystemBootstrap.class),
                fileService,
                mock(AutobuilderConfigProperties.class),
                mock(SystemResetConfig.class),
                contextCoordinator,
                cycleExecutor,
                cyclePersistenceService,
                mock(CycleLoggingService.class),
                mock(SmsCycleIngestor.class),
                goalPlannerService,
                mock(LlmCircuitBreaker.class),
                rootConvergenceTracker,
                mock(GoalConsolidationService.class),
                mock(OrchestrationSnapshotService.class)
        );
        setField(service, "schedulerEnabled", true);

        CriteriaVerificationService verifier = mock(CriteriaVerificationService.class);
        setField(service, "criteriaVerificationService", verifier);

        return new ControlLoopScenarioHarness(goalPlannerService, verifier, service, cycleExecutor);
    }

    // ── goal tree ─────────────────────────────────────────────────────────────

    /** Seeds root + one ACTIVE subgoal with the given criteria and origin, mirrors production metadata. */
    public GoalEntity seedTree(String subGoalDescription, List<String> criteria, boolean extraOrigin) {
        GoalEntity root = new GoalEntity("G", "root goal", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        GoalEntity sub = new GoalEntity("S", subGoalDescription, "planner", GoalType.SUBGOAL, "G");
        sub.setStatus(GoalStatus.ACTIVE);
        sub.getMetadata().put("acceptanceCriteria", criteria);
        sub.getMetadata().put(GoalPlannerService.SUBGOAL_ORIGIN_KEY,
                extraOrigin ? GoalPlannerService.SUBGOAL_ORIGIN_EXTRA : GoalPlannerService.SUBGOAL_ORIGIN_REQUIRED);

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);
        goalMap.put(sub.getId(), sub);
        ReflectionTestUtils.setField(goalPlannerService, "goalMap", goalMap);
        ReflectionTestUtils.setField(goalPlannerService, "rootGoalId", "G");
        ReflectionTestUtils.setField(goalPlannerService, "currentSubGoalId", "S");
        return sub;
    }

    // ── scripting ─────────────────────────────────────────────────────────────

    /**
     * Sets the context carried into the next {@link #runCycle()} call — the state the handler
     * ladder will process. Mirrors what the previous cycle's execution would have left behind.
     */
    public void carryContext(CycleContextData context) {
        setField(service, "context", context);
    }

    /** A carried context whose previous cycle ended SUBGOAL_EXHAUSTED on subgoal {@code S}. */
    public CycleContextData exhaustedContext(String description, List<String> criteria) {
        return CycleContextData.builder()
                .cycleName("scenario")
                .cycleResultState(CycleResultState.SUBGOAL_EXHAUSTED)
                .currentSubGoalId("S")
                .currentSubGoalDescription(description)
                .currentSubGoalCriteria(criteria)
                .build();
    }

    /**
     * Scripts what each subsequent cycle execution ends in: call N of {@link #runCycle()} makes
     * the executor return the prepared context with the Nth result state (last one repeats).
     * SUBGOAL_EXHAUSTED entries re-point at the planner's CURRENT subgoal so a scenario keeps
     * exhausting whichever leaf production code selected after a division/rescope.
     */
    public void scriptCycleResults(CycleResultState... states) {
        final int[] call = {0};
        lenient().when(cycleExecutor.runCycle(any())).thenAnswer(inv -> {
            CycleContextData prepared = inv.getArgument(0);
            CycleResultState state = states[Math.min(call[0]++, states.length - 1)];
            CycleContextData.CycleContextDataBuilder out = prepared.toBuilder().cycleResultState(state);
            if (state == CycleResultState.SUBGOAL_EXHAUSTED) {
                GoalEntity current = goalPlannerService.peekCurrentSubGoal();
                if (current != null) {
                    out.currentSubGoalId(current.getId())
                            .currentSubGoalDescription(current.getDescription())
                            .currentSubGoalCriteria(readCriteria(current));
                }
            }
            return out.build();
        });
    }

    /** Runs one control-loop step: handler ladder over the carried context, then the scripted cycle. */
    public void runCycle() {
        service.runAutobuilderCycle();
    }

    public CycleContextData context() {
        return service.getContext().orElseThrow();
    }

    // ── config knobs (mirror the @Value fields) ───────────────────────────────

    public ControlLoopScenarioHarness withRetries(boolean enabled) {
        setField(service, "subgoalRetryEnabled", enabled);
        return this;
    }

    public ControlLoopScenarioHarness withDivideOnExhaustion(boolean enabled) {
        setField(service, "divideOnExhaustion", enabled);
        return this;
    }

    public ControlLoopScenarioHarness withExhaustionCap(int cap) {
        setField(service, "maxConsecutiveSubgoalExhaustionsWithoutMerge", cap);
        return this;
    }

    @SuppressWarnings("unchecked")
    private static List<String> readCriteria(GoalEntity goal) {
        Object criteria = goal.getMetadata() != null ? goal.getMetadata().get("acceptanceCriteria") : null;
        return criteria instanceof List<?> l ? (List<String>) l : List.of();
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("harness field injection failed: " + fieldName, e);
        }
    }
}
