package com.walpro.autobuilder.analysis.exhaustion;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GoalExhaustionDetectorTest {

    private final GoalExhaustionDetector detector = new GoalExhaustionDetector();

    // ── No exhaustion ─────────────────────────────────────────────────────────

    @Test
    void nullContext_returnsNone() {
        assertThat(detector.detect(null).exhausted()).isFalse();
    }

    @Test
    void noGoalId_returnsNone() {
        CycleContextData ctx = CycleContextData.builder().signals(new LinkedHashMap<>()).build();
        assertThat(detector.detect(ctx).exhausted()).isFalse();
    }

    @Test
    void healthyCycle_noExhaustion() {
        CycleContextData ctx = CycleContextData.builder()
                .currentSubGoalId("goal-1")
                .signals(new LinkedHashMap<>())
                .cumulativeStagnatingCycles(0)
                .build();
        assertThat(detector.detect(ctx).exhausted()).isFalse();
    }

    // ── Rule D (T2-AA): pre-emptive exhaustion from history ──────────────────

    @Test
    void ruleD_cumulativeThresholdMet_andCurrentlyStagnating_exhausts() {
        var signals = new LinkedHashMap<>(Map.of(
                BasicSignals.STAGNATION, Signal.of(BasicSignals.STAGNATION, 0.6, 0.1, "test")
        ));
        CycleContextData ctx = CycleContextData.builder()
                .currentSubGoalId("goal-1")
                .signals(signals)
                .cumulativeStagnatingCycles(5) // threshold is 5
                .build();

        ExhaustionReport report = detector.detect(ctx);

        assertThat(report.exhausted()).isTrue();
        assertThat(report.rationale()).contains("Cumulative stagnation history");
        assertThat(report.evidence()).containsEntry("cumulativeStagnatingCycles", 5);
        assertThat(report.evidence()).containsEntry("exhaustedByHistory", true);
    }

    @Test
    void ruleD_cumulativeBelowThreshold_doesNotExhaustAlone() {
        var signals = new LinkedHashMap<>(Map.of(
                BasicSignals.STAGNATION, Signal.of(BasicSignals.STAGNATION, 0.6, 0.1, "test")
        ));
        CycleContextData ctx = CycleContextData.builder()
                .currentSubGoalId("goal-1")
                .signals(signals)
                .cumulativeStagnatingCycles(3) // below threshold of 5
                .build();

        // Rule A/B/C don't fire either (no repetition, no low-confidence, no diff data)
        ExhaustionReport report = detector.detect(ctx);
        // Should not exhaust from history alone when below threshold
        assertThat(report.evidence().getOrDefault("exhaustedByHistory", false)).isEqualTo(false);
    }

    @Test
    void ruleD_thresholdMet_butNotCurrentlyStagnating_doesNotFire() {
        // High accumulated history but this cycle is healthy → Rule D should not fire
        CycleContextData ctx = CycleContextData.builder()
                .currentSubGoalId("goal-1")
                .signals(new LinkedHashMap<>()) // no stagnation signal
                .cumulativeStagnatingCycles(10)
                .build();

        ExhaustionReport report = detector.detect(ctx);
        assertThat(report.evidence().getOrDefault("exhaustedByHistory", false)).isEqualTo(false);
    }

    @Test
    void ruleD_highCumulativeHistory_raisesConfidence() {
        var signals = new LinkedHashMap<>(Map.of(
                BasicSignals.STAGNATION, Signal.of(BasicSignals.STAGNATION, 0.6, 0.1, "test"),
                BasicSignals.REPETITION_DETECTED, Signal.of(BasicSignals.REPETITION_DETECTED, 0.85, 0.1, "test")
        ));
        CycleContextData ctx = CycleContextData.builder()
                .currentSubGoalId("goal-1")
                .signals(signals)
                .cumulativeStagnatingCycles(7) // above threshold
                .build();

        ExhaustionReport report = detector.detect(ctx);

        // Rule A fires (stagnation + repetition) AND Rule D fires → combined confidence is higher
        assertThat(report.exhausted()).isTrue();
        assertThat(report.confidence()).isGreaterThan(0.70);
    }
}
