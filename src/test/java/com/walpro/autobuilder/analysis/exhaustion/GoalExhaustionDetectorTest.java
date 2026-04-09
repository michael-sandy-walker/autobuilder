package com.walpro.autobuilder.analysis.exhaustion;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GoalExhaustionDetectorTest {

    private final GoalExhaustionDetector detector = new GoalExhaustionDetector();

    /** Dummy evolution outcome — non-null, prevents Rule C (consecutive-no-evolution) from firing. */
    private static final EvolutionOutcome STUB_EVOLUTION =
            new EvolutionOutcome(List.of(), List.of(), "stub", "stub");

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

    // ── T2-BL: Criteria-aware exhaustion suppression ──────────────────────────

    @Test
    void t2bl_criteriaProgressing_suppressesRuleA() {
        // Rule A conditions met (stagnation + repetition) but criteria pass count just increased.
        // lastEvolutionOutcome is non-null to prevent Rule C (consecutive-no-evolution) from firing.
        var signals = new LinkedHashMap<>(Map.of(
                BasicSignals.STAGNATION, Signal.of(BasicSignals.STAGNATION, 0.6, 0.1, "test"),
                BasicSignals.REPETITION_DETECTED, Signal.of(BasicSignals.REPETITION_DETECTED, 0.85, 0.1, "test")
        ));
        // cumulativeCriteriaStatus has 2 passes; lastCriteriaPassCount was 1 → progress
        Map<Integer, Boolean> cumulative = new LinkedHashMap<>(Map.of(0, true, 1, true));
        CycleContextData ctx = CycleContextData.builder()
                .currentSubGoalId("goal-1")
                .signals(signals)
                .cumulativeStagnatingCycles(0)
                .cumulativeCriteriaStatus(cumulative)
                .lastCriteriaPassCount(1) // was 1 last cycle, now 2
                .lastEvolutionOutcome(STUB_EVOLUTION)
                .build();

        ExhaustionReport report = detector.detect(ctx);

        assertThat(report.exhausted()).isFalse();
        assertThat(report.rationale()).contains("criteria progress");
    }

    @Test
    void t2bl_criteriaProgressing_suppressesRuleD() {
        // Rule D conditions met (cumulative history >= 5 + stagnation signal) but criteria progressing.
        // lastEvolutionOutcome is non-null to prevent Rule C from firing.
        var signals = new LinkedHashMap<>(Map.of(
                BasicSignals.STAGNATION, Signal.of(BasicSignals.STAGNATION, 0.6, 0.1, "test")
        ));
        Map<Integer, Boolean> cumulative = new LinkedHashMap<>(Map.of(0, true, 1, true, 2, true));
        CycleContextData ctx = CycleContextData.builder()
                .currentSubGoalId("goal-1")
                .signals(signals)
                .cumulativeStagnatingCycles(6)
                .cumulativeCriteriaStatus(cumulative)
                .lastCriteriaPassCount(2) // was 2 last cycle, now 3
                .lastEvolutionOutcome(STUB_EVOLUTION)
                .build();

        ExhaustionReport report = detector.detect(ctx);

        assertThat(report.exhausted()).isFalse();
        assertThat(report.rationale()).contains("criteria progress");
    }

    @Test
    void t2bl_criteriaProgressing_doesNotSuppressRuleB() {
        // Rule B: global stuckness (repetition + low-confidence + low-entropy).
        // Criteria progressing must NOT suppress Rule B.
        var signals = new LinkedHashMap<>(Map.of(
                BasicSignals.REPETITION_DETECTED, Signal.of(BasicSignals.REPETITION_DETECTED, 0.85, 0.1, "test"),
                BasicSignals.LOW_CONFIDENCE, Signal.of(BasicSignals.LOW_CONFIDENCE, 0.85, 0.1, "test")
        ));
        // Provide a diff with only 1 changed file to satisfy Rule B's low-entropy condition.
        // We simulate low-entropy by setting no diff (noChanges=false, lowEntropy from diff structure)
        // — actually Rule B uses lowEntropy || noChanges || severeRegression.
        // Use severeRegression path: lastScore=0.9, currentScore=0.5 → delta=-0.4 (severe)
        var evalOutcome = new com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome(
                0.5, com.walpro.autobuilder.model.MutationAcceptanceState.REJECTED,
                com.walpro.autobuilder.model.GoalProgressState.STAGNATING, "msg", null);

        Map<Integer, Boolean> cumulative = new LinkedHashMap<>(Map.of(0, true));
        CycleContextData ctx = CycleContextData.builder()
                .currentSubGoalId("goal-1")
                .signals(signals)
                .cumulativeStagnatingCycles(0)
                .lastScore(0.9)
                .evaluationOutcome(evalOutcome)
                .cumulativeCriteriaStatus(cumulative)
                .lastCriteriaPassCount(0) // progressing: 0→1
                .build();

        ExhaustionReport report = detector.detect(ctx);

        // Rule B still fires despite criteria progress
        assertThat(report.exhausted()).isTrue();
        assertThat(report.evidence()).containsEntry("exhaustedByHistory", false);
    }

    @Test
    void t2bl_criteriaCountUnchanged_doesNotSuppressRuleA() {
        // Same pass count as last cycle → not progressing → Rule A fires normally
        var signals = new LinkedHashMap<>(Map.of(
                BasicSignals.STAGNATION, Signal.of(BasicSignals.STAGNATION, 0.6, 0.1, "test"),
                BasicSignals.REPETITION_DETECTED, Signal.of(BasicSignals.REPETITION_DETECTED, 0.85, 0.1, "test")
        ));
        Map<Integer, Boolean> cumulative = new LinkedHashMap<>(Map.of(0, true));
        CycleContextData ctx = CycleContextData.builder()
                .currentSubGoalId("goal-1")
                .signals(signals)
                .cumulativeStagnatingCycles(0)
                .cumulativeCriteriaStatus(cumulative)
                .lastCriteriaPassCount(1) // same count as current → not progressing
                .build();

        ExhaustionReport report = detector.detect(ctx);

        assertThat(report.exhausted()).isTrue();
    }

    @Test
    void t2bl_noCriteria_doesNotSuppressExhaustion() {
        // No criteria defined (cumulativeCriteriaStatus is null) → no suppression
        var signals = new LinkedHashMap<>(Map.of(
                BasicSignals.STAGNATION, Signal.of(BasicSignals.STAGNATION, 0.6, 0.1, "test"),
                BasicSignals.REPETITION_DETECTED, Signal.of(BasicSignals.REPETITION_DETECTED, 0.85, 0.1, "test")
        ));
        CycleContextData ctx = CycleContextData.builder()
                .currentSubGoalId("goal-1")
                .signals(signals)
                .cumulativeStagnatingCycles(0)
                .cumulativeCriteriaStatus(null)
                .lastCriteriaPassCount(0)
                .build();

        ExhaustionReport report = detector.detect(ctx);

        assertThat(report.exhausted()).isTrue();
    }
}
