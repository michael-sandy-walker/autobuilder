package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuildResultHeuristicTest {

    private final BuildResultHeuristic heuristic = new BuildResultHeuristic();

    private static EvaluationOutcome anyOutcome() {
        return new EvaluationOutcome(0.8, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "msg", null);
    }

    @Test
    void evaluate_nullBuildOutcome_returnsNone() {
        CycleContextData ctx = CycleContextData.builder().cycleName("c").build();
        HeuristicOpinion op = heuristic.evaluate(ctx, anyOutcome());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);
        assertThat(op.confidence()).isZero();
    }

    @Test
    void evaluate_compileOk_returnsNone() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c")
                .buildOutcome(new BuildOutcome(BuildResult.COMPILE_OK, "", 1234L, "mvn compile", "c"))
                .build();
        HeuristicOpinion op = heuristic.evaluate(ctx, anyOutcome());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);
    }

    @Test
    void evaluate_skipped_returnsNone() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c")
                .buildOutcome(BuildOutcome.skipped("disabled"))
                .build();
        HeuristicOpinion op = heuristic.evaluate(ctx, anyOutcome());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);
    }

    @Test
    void evaluate_compileError_rejectsAtCriticalConfidence() {
        BuildOutcome failed = new BuildOutcome(
                BuildResult.COMPILE_ERROR,
                "[ERROR] cannot find symbol\n[ERROR] symbol: class Foo",
                4567L,
                "mvn compile",
                "c"
        );
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c")
                .buildOutcome(failed)
                .build();
        HeuristicOpinion op = heuristic.evaluate(ctx, anyOutcome());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(op.confidence()).isEqualTo(1.0);
        assertThat(op.suggestedScore()).isEqualTo(0.0);
        assertThat(op.rationale()).contains("Post-merge build FAILED");
        assertThat(op.rationale()).contains("cannot find symbol");
    }

    @Test
    void evaluate_compileError_fromPreviousCycle_returnsNone() {
        // Stale build result from a prior cycle must not block the current mutation —
        // the current mutation may be the fix for exactly that compile error.
        BuildOutcome stale = new BuildOutcome(
                BuildResult.COMPILE_ERROR, "[ERROR] cannot find symbol", 1000L, "mvn compile", "cycle-old"
        );
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-new")
                .buildOutcome(stale)
                .build();
        HeuristicOpinion op = heuristic.evaluate(ctx, anyOutcome());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);
        assertThat(op.confidence()).isZero();
    }

    @Test
    void evaluate_timeout_rejectsAtHighConfidence() {
        BuildOutcome timed = new BuildOutcome(BuildResult.TIMEOUT, "", 120_000L, "mvn compile", "c");
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c")
                .buildOutcome(timed)
                .build();
        HeuristicOpinion op = heuristic.evaluate(ctx, anyOutcome());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(op.confidence()).isEqualTo(0.75);
        assertThat(op.rationale()).contains("TIMED OUT");
    }
}
