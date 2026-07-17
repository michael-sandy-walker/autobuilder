package com.walpro.autobuilder.analysis.pattern;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalProgressApplicationResult;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.scope.ScopeActionSnapshot;
import com.walpro.autobuilder.model.GoalProgressState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScopeThrashSignalAnalyzerTest {

    @Mock
    private AgentGraphService agentGraphService;

    @Mock
    private GoalPlannerService goalPlannerService;

    private ScopeThrashSignalAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new ScopeThrashSignalAnalyzer(agentGraphService, goalPlannerService);
    }

    private static ScopeActionSnapshot snap(String status) {
        return new ScopeActionSnapshot("a", "sub-1", status, "cycle-1", Instant.now());
    }

    private static CycleContextData ctxWithSubgoal(String subGoalId) {
        return CycleContextData.builder()
                .currentSubGoalId(subGoalId)
                
                .build();
    }

    @Test
    void thrash_routesToExhaustionHandler_raisesSignal_keepsSubgoalAndDoesNotGiveUp() {
        // T2-EK slice 1: the thrash result is declared as SUBGOAL_EXHAUSTED so the one
        // give-up home (rescue ladder + runaway-brake accounting) decides — no direct give-up.
        when(agentGraphService.findRecentScopeActions(eq("sub-1"), anyInt()))
                .thenReturn(List.of(snap("OUT_OF_SCOPE"), snap("OUT_OF_SCOPE"), snap("OUT_OF_SCOPE")));

        CycleContextData out = analyzer.analyze(ctxWithSubgoal("sub-1"));

        assertThat(out.hasOpenRequest(BasicSignals.SUBGOAL_EXHAUSTED)).isTrue();
        assertThat(out.currentSubGoalId()).isEqualTo("sub-1");
        verify(goalPlannerService, never()).applyGoalProgress(anyString(), any(), anyString());
    }

    @Test
    void thrash_legacyFlagOff_givesUpDirectlyAndAdvancesPointer() {
        ReflectionTestUtils.setField(analyzer, "routeToRescue", false);
        when(agentGraphService.findRecentScopeActions(eq("sub-1"), anyInt()))
                .thenReturn(List.of(snap("OUT_OF_SCOPE"), snap("OUT_OF_SCOPE"), snap("OUT_OF_SCOPE")));
        when(goalPlannerService.applyGoalProgress(eq("sub-1"), eq(GoalProgressState.GIVEN_UP), anyString()))
                .thenReturn(new GoalProgressApplicationResult("sub-1", false, "sub-1", "sub-2", false));

        CycleContextData out = analyzer.analyze(ctxWithSubgoal("sub-1"));

        assertThat(out.currentSubGoalId()).isEqualTo("sub-2");
        assertThat(out.hasOpenRequest(BasicSignals.SUBGOAL_EXHAUSTED)).isFalse();
    }

    @Test
    void belowThreshold_orBrokenStreak_noOp() {
        when(agentGraphService.findRecentScopeActions(eq("sub-1"), anyInt()))
                .thenReturn(List.of(snap("OUT_OF_SCOPE"), snap("OUT_OF_SCOPE")))
                .thenReturn(List.of(snap("OUT_OF_SCOPE"), snap("IN_SCOPE"), snap("OUT_OF_SCOPE"), snap("OUT_OF_SCOPE")));

        CycleContextData shortStreak = analyzer.analyze(ctxWithSubgoal("sub-1"));
        CycleContextData brokenStreak = analyzer.analyze(ctxWithSubgoal("sub-1"));

        assertThat(shortStreak.hasOpenRequest(BasicSignals.SUBGOAL_EXHAUSTED)).isFalse();
        assertThat(brokenStreak.hasOpenRequest(BasicSignals.SUBGOAL_EXHAUSTED)).isFalse();
        verify(goalPlannerService, never()).applyGoalProgress(anyString(), any(), anyString());
    }

    @Test
    void nullOrMissingSubgoal_noOp() {
        assertThat(analyzer.analyze(null)).isNull();
        CycleContextData noSub = CycleContextData.builder()

                .build();
        assertThat(analyzer.analyze(noSub)).isSameAs(noSub);
        verifyNoInteractions(agentGraphService);
    }

    // T5-I: firing emits pattern_detected; quiet evaluation emits nothing.

    @Test
    void thrash_emitsPatternDetected_quietDoesNot() {
        var behaviourLogger = mock(com.walpro.autobuilder.observability.behaviour.BehaviourLogger.class);
        analyzer.setBehaviourLogger(behaviourLogger);
        when(agentGraphService.findRecentScopeActions(eq("sub-1"), anyInt()))
                .thenReturn(List.of(snap("OUT_OF_SCOPE"), snap("OUT_OF_SCOPE"), snap("OUT_OF_SCOPE")))
                .thenReturn(List.of(snap("IN_SCOPE")));

        analyzer.analyze(ctxWithSubgoal("sub-1"));
        verify(behaviourLogger).patternDetected(
                any(), eq("ScopeThrashSignalAnalyzer"),
                contains("sub-1"), eq("SUBGOAL_EXHAUSTED(scope-thrash)"));

        analyzer.analyze(ctxWithSubgoal("sub-1"));
        verifyNoMoreInteractions(behaviourLogger);
    }
}
