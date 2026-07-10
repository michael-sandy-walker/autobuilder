package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuildFixEscalatorTest {

    private final BuildFixEscalator escalator = new BuildFixEscalator();

    @Test
    void escalateIfStuck_buildOk_returnsContextUnchanged() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c1")
                .signals(new java.util.LinkedHashMap<>())
                .build();

        CycleContextData result = escalator.escalateIfStuck(ctx);

        assertThat(result.hasOpenRequest(BasicSignals.REPLAN_REQUESTED)).isFalse();
        assertThat(result.hasWindow("analysis")).isFalse();
    }

    @Test
    void escalateIfStuck_stage1_attemptsThreshold_emitsReplanRequested() {
        CycleContextData ctx = withBuildBroken()
                .withConsecutiveBuildFixAttempts(BuildFixEscalator.STAGE_1_ATTEMPT_THRESHOLD);

        CycleContextData result = escalator.escalateIfStuck(ctx);

        assertThat(result.hasOpenRequest(BasicSignals.REPLAN_REQUESTED)).isTrue();
        assertThat(result.peekRequest(BasicSignals.REPLAN_REQUESTED).source())
                .isEqualTo("buildFixStuck");
        assertThat(result.hasWindow("analysis")).isFalse();
    }

    @Test
    void escalateIfStuck_stage1_repetitionThreshold_emitsReplanRequested() {
        CycleContextData ctx = withBuildBroken()
                .withSignal(Signal.of(BasicSignals.REPETITION_DETECTED,
                        BuildFixEscalator.STAGE_1_REPETITION_THRESHOLD, 0.05, "test"));

        CycleContextData result = escalator.escalateIfStuck(ctx);

        assertThat(result.hasOpenRequest(BasicSignals.REPLAN_REQUESTED)).isTrue();
    }

    @Test
    void escalateIfStuck_stage2_emitsRoleAnalysisBoost() {
        CycleContextData ctx = withBuildBroken()
                .withConsecutiveBuildFixAttempts(BuildFixEscalator.STAGE_2_ATTEMPT_THRESHOLD);

        CycleContextData result = escalator.escalateIfStuck(ctx);

        assertThat(result.hasOpenRequest(BasicSignals.REPLAN_REQUESTED)).isTrue();
        assertThat(result.hasWindow("analysis")).isTrue();
        assertThat(result.windows().get("analysis").source())
                .isEqualTo("buildFixStuck-analyze");
    }

    @Test
    void escalateIfStuck_belowAllThresholds_returnsContextUnchanged() {
        CycleContextData ctx = withBuildBroken()
                .withConsecutiveBuildFixAttempts(1);

        CycleContextData result = escalator.escalateIfStuck(ctx);

        assertThat(result.hasOpenRequest(BasicSignals.REPLAN_REQUESTED)).isFalse();
        assertThat(result.hasWindow("analysis")).isFalse();
    }

    private static CycleContextData withBuildBroken() {
        return CycleContextData.builder()
                .cycleName("c-broken")
                .signals(new java.util.LinkedHashMap<>())
                .build()
                .withSignal(Signal.of(BasicSignals.BUILD_BROKEN, 1.0, 0.0, "test"));
    }
}
