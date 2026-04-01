package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.analysis.heuristic.SubGoalFulfillmentHeuristic;
import com.walpro.autobuilder.analysis.pattern.PatternSignalAnalyzer;
import com.walpro.autobuilder.analysis.pattern.PlanningThrashSignalAnalyzer;
import com.walpro.autobuilder.analysis.pattern.ScopeThrashSignalAnalyzer;
import com.walpro.autobuilder.analysis.problem.GoalCompletionDecider;
import com.walpro.autobuilder.analysis.scope.SubgoalScopeGuard;
import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.engine.AdaptiveExecutionEngine;
import com.walpro.autobuilder.core.interpretation.InterpretationService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.AttemptIds;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.PlanningCallback;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Goal-loop regression test:
 * When the current leaf is fulfilled and the branch is exhausted, the GoalPlannerService callback may inject new leaves.
 * CycleExecutor should end the cycle pointing at the newly injected leaf (not "stuck" at null / old scope).
 */
@ExtendWith(MockitoExtension.class)
class CycleExecutorGoalLoopTest {

    @TempDir
    Path tempDir;

    private AdaptiveExecutionEngine adaptiveExecutionEngine;
    private SubGoalFulfillmentHeuristic subGoalFulfillmentHeuristic;
    private InterpretationService interpretationService;
    private CycleScoreRecorder cycleScoreRecorder;
    private GoalCompletionDecider goalCompletionDecider;
    private PatternSignalAnalyzer patternSignalAnalyzer;
    private PlanningThrashSignalAnalyzer planningThrashSignalAnalyzer;
    private ScopeThrashSignalAnalyzer scopeThrashSignalAnalyzer;
    private SubgoalScopeGuard subgoalScopeGuard;

    private FileService fileService;
    private GoalGraphService goalGraphService;
    private GoalPlannerConfigProperties goalPlannerConfig;

    private GoalPlannerService goalPlannerService; // real (or spy)
    private CycleExecutor cycleExecutor;

    @BeforeEach
    void setUp() throws IOException {
        adaptiveExecutionEngine = mock(AdaptiveExecutionEngine.class);
        subGoalFulfillmentHeuristic = mock(SubGoalFulfillmentHeuristic.class);
        interpretationService = mock(InterpretationService.class);
        cycleScoreRecorder = mock(CycleScoreRecorder.class);
        goalCompletionDecider = mock(GoalCompletionDecider.class);
        patternSignalAnalyzer = mock(PatternSignalAnalyzer.class);
        planningThrashSignalAnalyzer = mock(PlanningThrashSignalAnalyzer.class);
        scopeThrashSignalAnalyzer = mock(ScopeThrashSignalAnalyzer.class);
        subgoalScopeGuard = mock(SubgoalScopeGuard.class);

        fileService = mock(FileService.class);
        goalGraphService = mock(GoalGraphService.class);
        goalPlannerConfig = mock(GoalPlannerConfigProperties.class);

        when(goalPlannerConfig.isSyncFromGraph()).thenReturn(false);
        when(goalPlannerConfig.isMigrateToGraph()).thenReturn(false);

        Path goalFile = tempDir.resolve("GoalStack.json");
        when(fileService.resolveSecurePath("meta/GoalStack.json")).thenReturn(goalFile);
        when(fileService.existsResolved(goalFile)).thenReturn(false);

        // real service, but spy so we can keep attempt-ids deterministic
        goalPlannerService = spy(new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig));
        goalPlannerService.setCurrentCycleContext(CycleContextData.builder().build());
        goalPlannerService.init();

        doReturn(new AttemptIds("attempt-1", "attempt-parent-0"))
                .when(goalPlannerService).ensureAttemptIdsForCurrentScope();

        doAnswer(inv -> new AttemptIds("attempt-x", "attempt-y"))
                .when(goalPlannerService).ensureAttemptIdsFor(anyString());

        cycleExecutor = new CycleExecutor(
                adaptiveExecutionEngine,
                goalPlannerService,
                subGoalFulfillmentHeuristic,
                interpretationService,
                cycleScoreRecorder,
                goalCompletionDecider,
                patternSignalAnalyzer,
                planningThrashSignalAnalyzer,
                scopeThrashSignalAnalyzer,
                subgoalScopeGuard
        );

        // identity-ish pipeline: keep CycleExecutor in control
        when(adaptiveExecutionEngine.runCycle(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(patternSignalAnalyzer.analyze(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(planningThrashSignalAnalyzer.analyze(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(scopeThrashSignalAnalyzer.analyze(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(interpretationService.interpret(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(goalCompletionDecider.decide(any(), anyBoolean()))
                .thenReturn(mock(GoalCompletionDecider.CompletionDecision.class));
        when(subgoalScopeGuard.guard(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void runCycle_whenLeafFulfilledAndBranchExhausted_andPlannerInjectsNewLeaf_advancesToNewLeaf() {
        // root -> g1 (only leaf)
        goalPlannerService.resetInitialGoal("root", "Root");
        goalPlannerService.addSubGoal("g1", "Only leaf", true, "root");

        assertThat(goalPlannerService.getCurrentSubGoal()).isNotNull();
        assertThat(goalPlannerService.getCurrentSubGoal().getId()).isEqualTo("g1");

        // planning callback injects new leaves under root (n1 becomes current)
        PlanningCallback callback = (ctx, parentId) -> {
            goalPlannerService.addSubGoal("n1", "New 1", true, parentId);
            goalPlannerService.addSubGoal("n2", "New 2", false, parentId);
        };
        goalPlannerService.setPlanningCallback(callback);

        // fulfillment => CycleExecutor.applyCompletion() calls applyGoalProgress(FULFILLED) on GoalPlannerService
        when(subGoalFulfillmentHeuristic.decide(any()))
                .thenReturn(SubGoalFulfillmentHeuristic.FulfillmentDecision.yes(0.95, "ok"));

        CycleContextData in = CycleContextData.builder()
                .cycleName("cycle-1")
                .signals(new LinkedHashMap<>())
                .remainingGoals(List.of())
                .completedGoals(List.of())
                .build();

        CycleContextData out = cycleExecutor.runCycle(in);

        // After cycle, the planner-injected new leaf should be the scope.
        // (We assert both: the service state and the returned context state.)
        assertThat(goalPlannerService.getCurrentSubGoal()).isNotNull();
        assertThat(goalPlannerService.getCurrentSubGoal().getId()).isEqualTo("n1");

        assertThat(out.currentSubGoalId()).isEqualTo("n1");
        assertThat(out.currentSubGoalDescription()).isEqualTo("New 1");
        assertThat(out.currentGoalId()).isEqualTo("root");
    }

    @Test
    void runCycle_whenLeafFulfilledAndBranchExhausted_andPlannerInjectsNothing_keepsScopeExhausted() {
        // root -> g1 (only leaf)
        goalPlannerService.resetInitialGoal("root", "Root");
        goalPlannerService.addSubGoal("g1", "Only leaf", true, "root");

        assertThat(goalPlannerService.getCurrentSubGoal()).isNotNull();
        assertThat(goalPlannerService.getCurrentSubGoal().getId()).isEqualTo("g1");

        // planning callback injects nothing
        PlanningCallback callback = (ctx, parentId) -> {
            // intentionally leave empty
        };
        goalPlannerService.setPlanningCallback(callback);

        // fulfillment => CycleExecutor.applyCompletion() calls applyGoalProgress(FULFILLED) on GoalPlannerService
        when(subGoalFulfillmentHeuristic.decide(any()))
                .thenReturn(SubGoalFulfillmentHeuristic.FulfillmentDecision.yes(0.95, "ok"));

        CycleContextData in = CycleContextData.builder()
                .cycleName("cycle-1")
                .signals(new LinkedHashMap<>())
                .remainingGoals(List.of())
                .completedGoals(List.of())
                .build();

        CycleContextData out = cycleExecutor.runCycle(in);

        // After cycle, the current subgoal should be null
        // (We assert both: the service state and the returned context state.)
        assertThat(goalPlannerService.getCurrentSubGoal()).isNull();
        assertThat(out.currentSubGoalId()).isNull();

        assertThat(out.currentGoalId()).isEqualTo("root");
        assertThat(out.currentGoalDescription()).isEqualTo("Root");
        assertThat(goalPlannerService.getRootGoal()).isNotNull();
        assertThat(goalPlannerService.getRootGoal().getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }
}