package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.analysis.heuristic.SubGoalFulfillmentHeuristic;
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
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import com.walpro.autobuilder.observability.SignalLifetimeTracker;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T4-BO: end-to-end wiring — a real {@link SignalLifetimeTracker} sees the pre/post-tick signal
 * maps at all three {@code tickSignals} sites in {@link CycleExecutor}, and an expiring request
 * across real {@code runCycle()} calls produces a {@code signalLifetimeObserved} behaviour event.
 * {@link CycleExecutorGoalLoopTest} already pins that leaving both collaborators unset (the
 * default in that test) changes nothing — this test is the positive case.
 */
@ExtendWith(MockitoExtension.class)
class CycleExecutorSignalLifetimeWiringTest {

    @TempDir
    Path tempDir;

    private CycleExecutor cycleExecutor;
    private BehaviourLogger behaviourLogger;

    @BeforeEach
    void setUp() throws IOException {
        AdaptiveExecutionEngine adaptiveExecutionEngine = mock(AdaptiveExecutionEngine.class);
        SubGoalFulfillmentHeuristic subGoalFulfillmentHeuristic = mock(SubGoalFulfillmentHeuristic.class);
        InterpretationService interpretationService = mock(InterpretationService.class);
        CycleScoreRecorder cycleScoreRecorder = mock(CycleScoreRecorder.class);
        GoalCompletionDecider goalCompletionDecider = mock(GoalCompletionDecider.class);
        PatternSignalAnalyzer patternSignalAnalyzer = mock(PatternSignalAnalyzer.class);
        PlanningThrashSignalAnalyzer planningThrashSignalAnalyzer = mock(PlanningThrashSignalAnalyzer.class);
        ReplanPatternDetector replanPatternDetector = mock(ReplanPatternDetector.class);
        ScopeThrashSignalAnalyzer scopeThrashSignalAnalyzer = mock(ScopeThrashSignalAnalyzer.class);
        SubgoalScopeGuard subgoalScopeGuard = mock(SubgoalScopeGuard.class);

        FileService fileService = mock(FileService.class);
        GoalGraphService goalGraphService = mock(GoalGraphService.class);
        GoalPlannerConfigProperties goalPlannerConfig = mock(GoalPlannerConfigProperties.class);
        when(goalPlannerConfig.isSyncFromGraph()).thenReturn(false);
        when(goalPlannerConfig.isMigrateToGraph()).thenReturn(false);

        Path goalFile = tempDir.resolve("GoalStack.json");
        when(fileService.resolveSecurePath("meta/GoalStack.json")).thenReturn(goalFile);
        when(fileService.existsResolved(goalFile)).thenReturn(false);

        GoalPlannerService goalPlannerService = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        goalPlannerService.setCurrentCycleContext(CycleContextData.builder().build());
        goalPlannerService.init();
        goalPlannerService.resetInitialGoal("root", "Root");
        goalPlannerService.addSubGoal("g1", "Leaf", true, "root");

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

        behaviourLogger = mock(BehaviourLogger.class);
        cycleExecutor.setBehaviourLogger(behaviourLogger);
        cycleExecutor.setSignalLifetimeTracker(new SignalLifetimeTracker());

        when(adaptiveExecutionEngine.runCycle(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(patternSignalAnalyzer.analyze(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(planningThrashSignalAnalyzer.analyze(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(replanPatternDetector.detect(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(scopeThrashSignalAnalyzer.analyze(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(interpretationService.interpret(any())).thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));
        when(goalCompletionDecider.decide(any(), anyBoolean())).thenReturn(mock(GoalCompletionDecider.CompletionDecision.class));
        when(subgoalScopeGuard.guard(any(CycleContextData.class))).thenAnswer(inv -> inv.getArgument(0));
        when(subGoalFulfillmentHeuristic.decide(any()))
                .thenReturn(SubGoalFulfillmentHeuristic.FulfillmentDecision.no("not yet"));
    }

    @Test
    void realTracker_seesAnExpiringRequestAcrossTwoRealCycles_andEmitsTheLifetimeEvent() {
        // validity 1: survives cycle 1's tick (age 1), expires at cycle 2's tick (age 2 > 1) —
        // the request-channel equivalent of the old one-cycle full-decay signal.
        CycleContextData cycle1 = CycleContextData.builder()
                .cycleName("cycle-1")
                .remainingGoals(List.of())
                .completedGoals(List.of())
                .build()
                .issueRequest(ChannelRequest.of(
                        com.walpro.autobuilder.core.lifecycle.BasicSignals.STAGNATION, 1.0, "test", 1));
        CycleContextData afterCycle1 = cycleExecutor.runCycle(cycle1);

        assertThat(afterCycle1.hasOpenRequest(com.walpro.autobuilder.core.lifecycle.BasicSignals.STAGNATION))
                .as("fresh request survives its own first tick").isTrue();

        CycleContextData cycle2 = afterCycle1.toBuilder().cycleName("cycle-2").build();
        cycleExecutor.runCycle(cycle2);

        org.mockito.Mockito.verify(behaviourLogger).signalLifetimeObserved(
                org.mockito.ArgumentMatchers.eq("cycle-2"),
                org.mockito.ArgumentMatchers.eq("STAGNATION"),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(2),
                org.mockito.ArgumentMatchers.eq("expired"),
                org.mockito.ArgumentMatchers.eq("test"));
    }
}
