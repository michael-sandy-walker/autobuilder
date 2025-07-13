package com.walpro.autobuilder.agent.evaluation.heuristic;

import com.walpro.autobuilder.agent.evaluation.heuristic.HeuristicOpinion;
import com.walpro.autobuilder.agent.evaluation.heuristic.NoMeaningfulChangeHeuristic;
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

class NoMeaningfulChangeHeuristicTest {

    private final NoMeaningfulChangeHeuristic heuristic = new NoMeaningfulChangeHeuristic();

    @Test
    void testHighScoreWithoutChangesYieldsStrongRejection() {
        EvaluationInput input = new EvaluationInput(
                0.9,
                0.85,
                Set.of(),
                Set.of(),
                "Improve test coverage", "dummySubgoal",
                "1", "2",
                Map.of(),
                List.of(),
                Map.of(),
                Map.of(),
                Collections.emptyList()
        );

        EvaluationOutcome outcome = new EvaluationOutcome(
                0.95,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "Test coverage improved",
                null
        );

        HeuristicOpinion opinion = heuristic.evaluate(input, outcome);

        assertThat(opinion.confidence()).isGreaterThanOrEqualTo(0.7);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.goalProgress()).isEqualTo(GoalProgressState.STAGNATING);
        assertThat(opinion.rationale()).contains("no actual file changes");
    }

    @Test
    void testNonEmptyChangesYieldNoOpinion() {
        EvaluationInput input = new EvaluationInput(
                0.6,
                0.5,
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
                0.6,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "Good refactoring",
                null
        );

        HeuristicOpinion opinion = heuristic.evaluate(input, outcome);

        assertThat(opinion.confidence()).isLessThan(0.1);
        assertThat(opinion.mutationState()).isEqualTo(outcome.mutationState());
        assertThat(opinion.goalProgress()).isEqualTo(outcome.goalProgress());
    }
}
