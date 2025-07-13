package com.walpro.autobuilder.agent.evaluation.heuristic;

import com.walpro.autobuilder.agent.evaluation.heuristic.HeuristicOpinion;
import com.walpro.autobuilder.agent.evaluation.heuristic.ScoreRegressionHeuristic;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationInput;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

class ScoreRegressionHeuristicTest {

    private final ScoreRegressionHeuristic heuristic = new ScoreRegressionHeuristic();

    @Test
    void testRejectsMajorRegression() {
        EvaluationInput input = new EvaluationInput(
                0.5,
                0.8,
                Set.of("src/Foo.java"),
                Set.of(),
                "Refactor Foo", "dummySubgoal",
                "1", "2",
                Map.of(),
                List.of(),
                Map.of(),
                Map.of(),
                Collections.emptyList()
        );

        EvaluationOutcome outcome = new EvaluationOutcome(
                0.5,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "Refactored as requested",
                null
        );

        HeuristicOpinion opinion = heuristic.evaluate(input, outcome);

        assertThat(opinion.confidence()).isGreaterThanOrEqualTo(0.75);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.rationale()).contains("drop");
    }

    @Test
    void testStableScoreYieldsNoOpinion() {
        EvaluationInput input = new EvaluationInput(
                0.78,
                0.8,
                Set.of("src/Foo.java"),
                Set.of(),
                "Refactor Foo", "dummySubgoal",
                "1", "2",
                Map.of(),
                List.of(),
                Map.of(),
                Map.of(),
                Collections.emptyList()
        );

        EvaluationOutcome outcome = new EvaluationOutcome(
                0.8,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "Minor improvements",
                null
        );

        HeuristicOpinion opinion = heuristic.evaluate(input, outcome);

        assertThat(opinion.confidence()).isLessThan(0.1);
        assertThat(opinion.mutationState()).isEqualTo(outcome.mutationState());
    }
}
