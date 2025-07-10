package com.walpro.autobuilder.agent.evaluation.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationInput;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.evaluation.model.GoalProgressState;
import com.walpro.autobuilder.evaluation.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

class SuspiciousScoreHeuristicTest {

    private final SuspiciousScoreHeuristic heuristic = new SuspiciousScoreHeuristic();

    @Test
    void testNonSuspiciousScoreYieldsNoOpinion() {
        EvaluationInput input = new EvaluationInput(
                0.8,
                0.6,
                Set.of("A.java"),
                Set.of(),
                "Improve performance",
                Map.of(),
                List.of(),
                Map.of(),
                Map.of()
        );

        EvaluationOutcome original = new EvaluationOutcome(
                0.8,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "Looks fine",
                null
        );

        HeuristicOpinion opinion = heuristic.evaluate(input, original);

        assertThat(opinion.confidence()).isLessThan(0.1);
        assertThat(opinion.mutationState()).isEqualTo(original.mutationState());
    }

    @Test
    void testSuspiciousScoreWithoutChangesTriggersRejection() {
        EvaluationInput input = new EvaluationInput(
                1.0,
                0.99,
                Set.of(),
                Set.of(),
                "Test stagnation",
                Map.of(),
                List.of(),
                Map.of(),
                Map.of()
        );

        EvaluationOutcome suspicious = new EvaluationOutcome(
                1.0,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.FULFILLED,
                "Everything is perfect",
                null
        );

        HeuristicOpinion opinion = heuristic.evaluate(input, suspicious);

        assertThat(opinion.confidence()).isGreaterThanOrEqualTo(0.8);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
    }

    @Test
    void testLegitHighScoreWithFileChangesYieldsNoRejection() {
        EvaluationInput input = new EvaluationInput(
                0.98,
                0.96,
                Set.of("B.java"),
                Set.of(),
                "Legit improvement",
                Map.of(),
                List.of(),
                Map.of(),
                Map.of()
        );

        EvaluationOutcome suspicious = new EvaluationOutcome(
                1.0,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.FULFILLED,
                "Looks perfect",
                null
        );

        HeuristicOpinion opinion = heuristic.evaluate(input, suspicious);

        assertThat(opinion.confidence()).isLessThan(0.3);
        assertThat(opinion.mutationState()).isEqualTo(suspicious.mutationState());
    }
}
