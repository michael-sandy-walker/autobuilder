package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.analysis.heuristic.SubGoalFulfillmentHeuristic;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.analysis.pattern.PatternSignalAnalyzer;
import com.walpro.autobuilder.analysis.pattern.PlanningThrashSignalAnalyzer;
import com.walpro.autobuilder.analysis.pattern.ReplanPatternDetector;
import com.walpro.autobuilder.analysis.pattern.ScopeThrashSignalAnalyzer;
import com.walpro.autobuilder.analysis.problem.GoalCompletionDecider;
import com.walpro.autobuilder.analysis.scope.SubgoalScopeGuard;
import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.engine.AdaptiveExecutionEngine;
import com.walpro.autobuilder.core.interpretation.InterpretationService;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
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
    private ReplanPatternDetector replanPatternDetector;
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
        replanPatternDetector = mock(ReplanPatternDetector.class);
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

        MutationSnapshotService mutationSnapshotService = mock(MutationSnapshotService.class);

        cycleExecutor = new CycleExecutor(
                adaptiveExecutionEngine,
                goalPlannerService,
                subGoalFulfillmentHeuristic,
                interpretationService,
                cycleScoreRecorder,
                goalCompletionDecider,
                patternSignalAnalyzer,
                planningThrashSignalAnalyzer,
                replanPatternDetector,
                scopeThrashSignalAnalyzer,
                subgoalScopeGuard,
                mutationSnapshotService,
                new com.walpro.autobuilder.core.build.BuildFixEscalator()
        );

        // identity-ish pipeline: keep CycleExecutor in control
        when(adaptiveExecutionEngine.runCycle(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(patternSignalAnalyzer.analyze(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(planningThrashSignalAnalyzer.analyze(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(replanPatternDetector.detect(any()))
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
    void runCycle_T1DH_whenLeafFulfilledAndFirstWaveSatisfied_skipsPlannerAndCompletesRoot() {
        // T1-DH: when fulfilling the last leaf would otherwise trigger a re-plan against root,
        // the pre-plan auto-complete check fires first. With ratio 1/1 = 1.0 > 0.5 the root is
        // marked COMPLETED and the planner callback is NOT invoked — preventing the cycle-7
        // duplicate-injection waste described in the T1-DH ticket.
        goalPlannerService.resetInitialGoal("root", "Root");
        goalPlannerService.addSubGoal("g1", "Only leaf", true, "root");
        // T2-DR: closure consults the observation ledger; seed banked criteria so g1 satisfies it.
        goalPlannerService.setAcceptanceCriteria("g1", List.of("done"));
        goalPlannerService.saveCriteriaPassStatus("g1", java.util.Map.of(1, true));

        assertThat(goalPlannerService.getCurrentSubGoal()).isNotNull();
        assertThat(goalPlannerService.getCurrentSubGoal().getId()).isEqualTo("g1");

        // planning callback would inject new leaves if invoked — assert it is not invoked.
        boolean[] callbackInvoked = {false};
        PlanningCallback callback = (ctx, parentId) -> {
            callbackInvoked[0] = true;
            goalPlannerService.addSubGoal("n1", "New 1", true, parentId);
            return 1;
        };
        goalPlannerService.setPlanningCallback(callback);

        when(subGoalFulfillmentHeuristic.decide(any()))
                .thenReturn(SubGoalFulfillmentHeuristic.FulfillmentDecision.yes(0.95, "ok"));

        CycleContextData in = CycleContextData.builder()
                .cycleName("cycle-1")
                
                .remainingGoals(List.of())
                .completedGoals(List.of())
                .build();

        CycleContextData out = cycleExecutor.runCycle(in);

        assertThat(callbackInvoked[0]).isFalse();
        assertThat(goalPlannerService.getCurrentSubGoal()).isNull();
        assertThat(goalPlannerService.getRootGoal().getStatus()).isEqualTo(GoalStatus.COMPLETED);
        assertThat(out.currentGoalId()).isEqualTo("root");
        assertThat(goalPlannerService.getGoalById("n1")).isNull();
    }

    @Test
    void runCycle_T1DH_supersedesT1BKZeroInjectionPath_byPreemptingPlannerEntirely() {
        // T1-BK previously required REPLAN_REQUESTED and root=ACTIVE when the planner injected 0.
        // T1-DH supersedes that path for the single-child-completed configuration: the pre-plan
        // auto-complete check fires before the callback is reached, so root is marked COMPLETED
        // and the T1-BK fallback never runs (planInto returning 0 becomes structurally unreachable
        // from this entrypoint).
        goalPlannerService.resetInitialGoal("root", "Root");
        goalPlannerService.addSubGoal("g1", "Only leaf", true, "root");
        // T2-DR: closure consults the observation ledger; seed banked criteria so g1 satisfies it.
        goalPlannerService.setAcceptanceCriteria("g1", List.of("done"));
        goalPlannerService.saveCriteriaPassStatus("g1", java.util.Map.of(1, true));

        PlanningCallback callback = (ctx, parentId) -> 0;
        goalPlannerService.setPlanningCallback(callback);

        when(subGoalFulfillmentHeuristic.decide(any()))
                .thenReturn(SubGoalFulfillmentHeuristic.FulfillmentDecision.yes(0.95, "ok"));

        CycleContextData in = CycleContextData.builder()
                .cycleName("cycle-1")
                
                .remainingGoals(List.of())
                .completedGoals(List.of())
                .build();

        CycleContextData out = cycleExecutor.runCycle(in);

        assertThat(out.currentSubGoalId()).isNull();
        assertThat(out.currentGoalId()).isEqualTo("root");
        assertThat(goalPlannerService.getRootGoal().getStatus()).isEqualTo(GoalStatus.COMPLETED);
        assertThat(goalPlannerService.getCurrentCycleContext().hasOpenRequest(BasicSignals.REPLAN_REQUESTED)).isFalse();
    }
}