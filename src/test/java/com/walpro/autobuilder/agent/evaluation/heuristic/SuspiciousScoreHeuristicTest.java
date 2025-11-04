package com.walpro.autobuilder.agent.evaluation.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.heuristic.HeuristicOpinion;
import com.walpro.autobuilder.analysis.heuristic.SuspiciousScoreHeuristic;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousScoreHeuristicTest {

    private final SuspiciousScoreHeuristic heuristic = new SuspiciousScoreHeuristic();

    @Test
    void testNonSuspiciousScoreYieldsNoOpinion() {
    	CycleContextData context = CycleContextData.builder().build();
        context = context.withProjectDiff(new ProjectDiffResult(null, Map.of("A.java", "changed"), Set.of()));

        EvaluationOutcome original = new EvaluationOutcome(
                0.8,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "Looks fine",
                null
        );

        HeuristicOpinion opinion = heuristic.evaluate(context, original);

        assertThat(opinion.confidence()).isLessThan(0.1);
        assertThat(opinion.mutationState()).isEqualTo(original.mutationState());
    }

    @Test
    void testSuspiciousScoreWithoutChangesTriggersRejection() {
    	CycleContextData context = CycleContextData.builder().build();
    	context = context.withProjectDiff(new ProjectDiffResult(null, Map.of(), Set.of()));

        EvaluationOutcome suspicious = new EvaluationOutcome(
                1.0,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.FULFILLED,
                "Everything is perfect",
                null
        );

        HeuristicOpinion opinion = heuristic.evaluate(context, suspicious);

        assertThat(opinion.confidence()).isGreaterThanOrEqualTo(0.8);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
    }

    @Test
    void testLegitHighScoreWithFileChangesYieldsNoRejection() {
    	CycleContextData context = CycleContextData.builder().build();
    	context = context.withProjectDiff(new ProjectDiffResult(null, Map.of("B.java", "changed"), Set.of()));

        EvaluationOutcome suspicious = new EvaluationOutcome(
                1.0,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.FULFILLED,
                "Looks perfect",
                null
        );

        HeuristicOpinion opinion = heuristic.evaluate(context, suspicious);

        assertThat(opinion.confidence()).isLessThan(0.3);
        assertThat(opinion.mutationState()).isEqualTo(suspicious.mutationState());
    }
}
