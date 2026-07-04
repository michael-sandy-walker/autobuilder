package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.analysis.heuristic.EvaluationHeuristic;
import com.walpro.autobuilder.analysis.heuristic.HeuristicNegotiator;
import com.walpro.autobuilder.analysis.heuristic.HeuristicOpinion;
import com.walpro.autobuilder.analysis.heuristic.NegotiationResult;
import com.walpro.autobuilder.analysis.heuristic.strategy.score.NoOpScoreAdjustmentStrategy;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicNegotiatorTest {

    private final HeuristicNegotiator negotiator = new HeuristicNegotiator(new NoOpScoreAdjustmentStrategy());

    private static final CycleContextData dummyContext = CycleContextData.builder().build();
    private static final EvaluationOutcome originalOutcome = new EvaluationOutcome(
            0.8, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "Original OK", null
    );

    static class StaticHeuristic implements EvaluationHeuristic {
        private final HeuristicOpinion opinion;
        StaticHeuristic(HeuristicOpinion opinion) {
            this.opinion = opinion;
        }
        @Override
        public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome outcome) {
            return opinion;
        }
    }

    @Test
    void shouldOverrideOnCritical() {
        List<EvaluationHeuristic> heuristics = List.of(
                new StaticHeuristic(HeuristicOpinion.of("CriticalHeuristic", 0.95, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "Critical reason")),
                new StaticHeuristic(HeuristicOpinion.of("OtherHeuristic", 0.5, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "Mild concern"))
        );

        NegotiationResult result = negotiator.negotiate(dummyContext, originalOutcome, heuristics);

        assertThat(result.outcome().mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(result.outcome().feedbackMessage()).contains("CriticalHeuristic");
    }

    @Test
    void shouldOverrideOnTwoHighsWithSameState() {
        List<EvaluationHeuristic> heuristics = List.of(
                new StaticHeuristic(HeuristicOpinion.of("High1", 0.8, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "One reason")),
                new StaticHeuristic(HeuristicOpinion.of("High2", 0.78, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "Another reason"))
        );

        NegotiationResult result = negotiator.negotiate(dummyContext, originalOutcome, heuristics);

        assertThat(result.outcome().mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(result.outcome().feedbackMessage()).contains("High1");
    }

    @Test
    void shouldFallbackToOriginalWhenNoStrongSignal() {
        List<EvaluationHeuristic> heuristics = List.of(
                new StaticHeuristic(HeuristicOpinion.of("Low1", 0.3, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "Low concern")),
                new StaticHeuristic(HeuristicOpinion.of("Medium1", 0.5, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "Mild suspicion"))
        );

        NegotiationResult result = negotiator.negotiate(dummyContext, originalOutcome, heuristics);

        assertThat(result.outcome()).isEqualTo(originalOutcome);
    }

    @Test
    void defectFindingRejection_outranksHigherConfidenceCriticalAcceptance() {
        // Run 14 field data (2026-07-04), verbatim vote pattern: the deterministic score echo
        // crossed the CRITICAL threshold on confidence alone (ACCEPTED, 1.0) and outranked
        // StoreCoherence's correct defect rejection (REJECTED, 0.9) in the max-confidence
        // tiebreak — two runtime-fatal parallel-store mutations merged. A defect finding
        // (deterministic structural defect of the mutation itself) wins the tiebreak.
        List<EvaluationHeuristic> heuristics = List.of(
                new StaticHeuristic(HeuristicOpinion.of("ScoreEcho", 1.0,
                        MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "score confidence")),
                new StaticHeuristic(HeuristicOpinion.ofDefectWithScore("DefectDetector", 0.9,
                        MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING,
                        "parallel store introduced", 0.5))
        );

        NegotiationResult result = negotiator.negotiate(dummyContext, originalOutcome, heuristics);

        assertThat(result.outcome().mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(result.outcome().feedbackMessage()).contains("DefectDetector");
    }

    @Test
    void plainCriticals_keepMaxConfidenceTiebreak() {
        // The designed procedural accept must survive: BuildFixAcceptance's CRITICAL-ACCEPTED
        // at 1.0 (defer to the post-merge build gate) still outranks a plain statistical
        // CRITICAL-REJECTED at lower confidence when no defect finding is present.
        List<EvaluationHeuristic> heuristics = List.of(
                new StaticHeuristic(HeuristicOpinion.of("ProceduralAccept", 1.0,
                        MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "defer to build gate")),
                new StaticHeuristic(HeuristicOpinion.of("StatisticalReject", 0.95,
                        MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "severe score drop"))
        );

        NegotiationResult result = negotiator.negotiate(dummyContext, originalOutcome, heuristics);

        assertThat(result.outcome().mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(result.outcome().feedbackMessage()).contains("ProceduralAccept");
    }

    @Test
    void shouldNotOverrideWhenHighsConflict() {
        List<EvaluationHeuristic> heuristics = List.of(
                new StaticHeuristic(HeuristicOpinion.of("High1", 0.8, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "Wants rejection")),
                new StaticHeuristic(HeuristicOpinion.of("High2", 0.77, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "Wants acceptance"))
        );

        NegotiationResult result = negotiator.negotiate(dummyContext, originalOutcome, heuristics);

        assertThat(result.outcome()).isEqualTo(originalOutcome);
    }


}
