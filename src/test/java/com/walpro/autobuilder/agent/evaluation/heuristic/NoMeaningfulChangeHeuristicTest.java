package com.walpro.autobuilder.agent.evaluation.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.heuristic.HeuristicOpinion;
import com.walpro.autobuilder.analysis.heuristic.NoMeaningfulChangeHeuristic;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NoMeaningfulChangeHeuristicTest {

    private final NoMeaningfulChangeHeuristic heuristic = new NoMeaningfulChangeHeuristic();

    @Test
    void testHighScoreWithoutChangesYieldsStrongRejection() {
        CycleContextData context = CycleContextData.builder().build();
        context = context.withProjectDiff(new ProjectDiffResult(null, Map.of(), Set.of()));

        EvaluationOutcome outcome = new EvaluationOutcome(
                0.95,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "Test coverage improved",
                null
        );

        HeuristicOpinion opinion = heuristic.evaluate(context, outcome);

        assertThat(opinion.confidence()).isGreaterThanOrEqualTo(0.7);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.goalProgress()).isEqualTo(GoalProgressState.STAGNATING);
        assertThat(opinion.rationale()).contains("no actual file changes");
    }

    @Test
    void testNonEmptyChangesYieldNoOpinion() {
        CycleContextData context = CycleContextData.builder().build();
        context = context.withProjectDiff(new ProjectDiffResult(null, Map.of("src/Foo.java", "changed"), Set.of()));

        EvaluationOutcome outcome = new EvaluationOutcome(
                0.6,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "Good refactoring",
                null
        );

        HeuristicOpinion opinion = heuristic.evaluate(context, outcome);

        assertThat(opinion.confidence()).isLessThan(0.1);
        assertThat(opinion.mutationState()).isEqualTo(outcome.mutationState());
        assertThat(opinion.goalProgress()).isEqualTo(outcome.goalProgress());
    }
}
