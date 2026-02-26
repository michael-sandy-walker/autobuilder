package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.problem.ProblemContext;
import com.walpro.autobuilder.analysis.problem.ProblemEntry;
import com.walpro.autobuilder.analysis.problem.ProblemSeverity;
import com.walpro.autobuilder.analysis.problem.ProblemStatus;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemReductionHeuristicTest {

    private final ProblemReductionHeuristic heuristic = new ProblemReductionHeuristic();

    private static ProblemEntry problem(String id, String goalId, String subId) {
        return new ProblemEntry(
                id,
                ProblemStatus.OPEN,
                ProblemSeverity.WARNING,
                "t",
                "d",
                Set.of("k"),
                goalId,
                subId,
                "c0",
                "c0",
                null,
                null,
                List.of(),
                List.of()
        );
    }

    @Test
    void returnsNull_whenNotAccepted() {
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("g")
                .currentSubGoalId("s")
                .build();

        EvaluationOutcome out = new EvaluationOutcome(
                0.8,
                MutationAcceptanceState.REJECTED,
                GoalProgressState.PROGRESSING,
                "fb",
                "rec"
        );

        assertThat(heuristic.evaluate(ctx, out)).isNull();
    }

    @Test
    void returnsNull_whenScoreImproved() {
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("g")
                .currentSubGoalId("s")
                .lastScore(0.50)
                .previousProblemContext(new ProblemContext(List.of(problem("p1", "g", "s")), List.of()))
                .problemContext(new ProblemContext(List.of(problem("p1", "g", "s")), List.of()))
                .build();

        EvaluationOutcome out = new EvaluationOutcome(
                0.60, // delta > 0
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.NOT_EVALUATED,
                "fb",
                "rec"
        );

        assertThat(heuristic.evaluate(ctx, out)).isNull();
    }

    @Test
    void returnsNull_whenProblemsReducedInScope() {
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("g")
                .currentSubGoalId("s")
                .lastScore(0.50)
                .previousProblemContext(new ProblemContext(List.of(problem("p1", "g", "s"), problem("p2", "g", "s")), List.of()))
                .problemContext(new ProblemContext(List.of(problem("p1", "g", "s")), List.of()))
                .build();

        EvaluationOutcome out = new EvaluationOutcome(
                0.50, // no score improve
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.NOT_EVALUATED,
                "fb",
                "rec"
        );

        assertThat(heuristic.evaluate(ctx, out)).isNull();
    }

    @Test
    void returnsOpinion_rejecting_whenAcceptedWithoutScoreProgressOrProblemReduction() {
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("g")
                .currentSubGoalId("s")
                .lastScore(0.50)
                .previousProblemContext(new ProblemContext(List.of(problem("p1", "g", "s")), List.of()))
                .problemContext(new ProblemContext(List.of(problem("p1", "g", "s")), List.of()))
                .build();

        EvaluationOutcome out = new EvaluationOutcome(
                0.50, // no improvement
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.NOT_EVALUATED,
                "fb",
                "rec"
        );

        HeuristicOpinion op = heuristic.evaluate(ctx, out);

        assertThat(op).isNotNull();
        assertThat(op.name()).isEqualTo("ProblemReductionHeuristic");
        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(op.goalProgress()).isEqualTo(GoalProgressState.STAGNATING);
        assertThat(op.rationale()).contains("ACCEPTED without justification");
    }
}