package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreRegressionHeuristicTest {

    private final ScoreRegressionHeuristic heuristic = new ScoreRegressionHeuristic();

    @Test
    void testRejectsMajorRegression() {
        CycleContextData context = CycleContextData.builder().build();
        context = context.withLastScore(0.8);
        context = context.withProjectDiff(new ProjectDiffResult(null, Map.of("src/Foo.java", "changed"), Set.of()));

        EvaluationOutcome outcome = new EvaluationOutcome(
                0.5,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "Refactored as requested",
                null
        );

        HeuristicOpinion opinion = heuristic.evaluate(context, outcome);

        assertThat(opinion.confidence()).isGreaterThanOrEqualTo(0.75);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.rationale()).contains("drop");
    }

    @Test
    void testStableScoreYieldAccepted() {
        CycleContextData context = CycleContextData.builder().build();
        context = context.withLastScore(0.8);
        context = context.withProjectDiff(new ProjectDiffResult(null, Map.of("src/Foo.java", "changed"), Set.of()));

        EvaluationOutcome outcome = new EvaluationOutcome(
                0.81,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "Minor improvements",
                null
        );

        HeuristicOpinion opinion = heuristic.evaluate(context, outcome);

        assertThat(opinion.confidence()).isEqualTo(0.0);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(opinion.goalProgress()).isEqualTo(GoalProgressState.PROGRESSING);
    }
}
