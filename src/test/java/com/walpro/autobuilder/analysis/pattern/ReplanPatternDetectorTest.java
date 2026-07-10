package com.walpro.autobuilder.analysis.pattern;

import java.time.Instant;
import java.util.List;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.planner.PlannerActionSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReplanPatternDetectorTest {

    private AgentGraphService agentGraphService;
    private ReplanPatternDetector detector;

    @BeforeEach
    void setUp() {
        agentGraphService = mock(AgentGraphService.class);
        detector = new ReplanPatternDetector(agentGraphService);
    }

    private static PlannerActionSnapshot snap(String fingerprint) {
        return new PlannerActionSnapshot("id", "sg1", fingerprint, true, 1, "cycle-1", "attempt-1", Instant.now());
    }

    private static CycleContextData ctx(String subGoalId) {
        return CycleContextData.builder()
                .cycleName("cycle-1")
                .currentGoalId("root-1")
                .currentSubGoalId(subGoalId)
                .build();
    }

    @Test
    void detect_whenFewerThanMinSamples_returnsUnchanged() {
        when(agentGraphService.findRecentPlannerActions(any(), eq(ReplanPatternDetector.LOOKBACK)))
                .thenReturn(List.of(snap("fp-A"), snap("fp-A"), snap("fp-A")));

        CycleContextData result = detector.detect(ctx("sg1"));

        assertThat(result.hasOpenRequest(BasicSignals.REPLAN_THRASH_DETECTED)).isFalse();
    }

    @Test
    void detect_whenAllFingerprintsDistinct_returnsUnchanged() {
        when(agentGraphService.findRecentPlannerActions(any(), eq(ReplanPatternDetector.LOOKBACK)))
                .thenReturn(List.of(snap("fp-A"), snap("fp-B"), snap("fp-C"), snap("fp-D")));

        CycleContextData result = detector.detect(ctx("sg1"));

        assertThat(result.hasOpenRequest(BasicSignals.REPLAN_THRASH_DETECTED)).isFalse();
    }

    @Test
    void detect_whenAllFingerprintsSame_emitsReplanThrashDetected() {
        when(agentGraphService.findRecentPlannerActions(any(), eq(ReplanPatternDetector.LOOKBACK)))
                .thenReturn(List.of(snap("fp-A"), snap("fp-A"), snap("fp-A"), snap("fp-A")));

        CycleContextData result = detector.detect(ctx("sg1"));

        assertThat(result.hasOpenRequest(BasicSignals.REPLAN_THRASH_DETECTED)).isTrue();
        assertThat(result.requestUrgency(BasicSignals.REPLAN_THRASH_DETECTED)).isEqualTo(1.0);
    }

    @Test
    void detect_whenMajorityRepeat_emitsSignal() {
        // 3 repeats out of 4: density = 0.75 → severity well above 0.50
        when(agentGraphService.findRecentPlannerActions(any(), eq(ReplanPatternDetector.LOOKBACK)))
                .thenReturn(List.of(snap("fp-A"), snap("fp-A"), snap("fp-A"), snap("fp-B")));

        CycleContextData result = detector.detect(ctx("sg1"));

        assertThat(result.hasOpenRequest(BasicSignals.REPLAN_THRASH_DETECTED)).isTrue();
    }

    @Test
    void detect_whenPlanningThrottledAlreadyActive_skips() {
        when(agentGraphService.findRecentPlannerActions(any(), eq(ReplanPatternDetector.LOOKBACK)))
                .thenReturn(List.of(snap("fp-A"), snap("fp-A"), snap("fp-A"), snap("fp-A")));

        CycleContextData throttled = ctx("sg1")
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(BasicSignals.PLANNING_THROTTLED, 1.0, "test", com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.0)));

        CycleContextData result = detector.detect(throttled);

        assertThat(result.hasOpenRequest(BasicSignals.REPLAN_THRASH_DETECTED)).isFalse();
    }

    @Test
    void detect_whenNullContext_returnsNull() {
        assertThat(detector.detect(null)).isNull();
    }

    @Test
    void detect_whenNoSubGoalId_usesRootGoalId() {
        when(agentGraphService.findRecentPlannerActions(eq("root-1"), eq(ReplanPatternDetector.LOOKBACK)))
                .thenReturn(List.of(snap("fp-A"), snap("fp-A"), snap("fp-A"), snap("fp-A")));

        CycleContextData result = detector.detect(ctx(null));

        assertThat(result.hasOpenRequest(BasicSignals.REPLAN_THRASH_DETECTED)).isTrue();
    }
}
