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
    void evaluate_testOnlyCompileError_returnsNone_doesNotRejectMainWork() {
        // T4-BN-2 follow-up: main compiles, only a test source fails — must NOT reject the mutation.
        BuildOutcome testOnly = new BuildOutcome(
                BuildResult.COMPILE_ERROR,
                "/proj/output/src/test/java/app/LoanServiceTest.java:[18,2] cannot find symbol\n  symbol: class Test",
                3000L, "mvn test-compile", "c");
        CycleContextData ctx = CycleContextData.builder().cycleName("c").buildOutcome(testOnly).build();

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

    // --- T2-EQ: compiles, but no runnable artifact ------------------------------------------

    @Test
    void evaluate_packageError_rejectsButLessHarshlyThanACompileFailure() {
        // Field shape 2026-07-27: a tree that compiled cleanly and would have scored 21/23,
        // reported as artifact-build-failed because packaging had nothing to launch.
        BuildOutcome packaging = new BuildOutcome(
                BuildResult.PACKAGE_ERROR,
                "[ERROR] Failed to execute goal ...:repackage: Unable to find main class",
                4567L,
                "package-verify",
                "c");
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c").buildOutcome(packaging).build();

        HeuristicOpinion op = heuristic.evaluate(ctx, anyOutcome());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(op.goalProgress()).isEqualTo(GoalProgressState.STAGNATING);
        // relays the tool's own words rather than describing the defect ourselves
        assertThat(op.rationale()).contains("Unable to find main class");
        // a compile failure condemns the diff outright (1.0); this one may not be the diff's fault
        assertThat(op.confidence()).isLessThan(1.0);
    }

    @Test
    void evaluate_packageError_isDistinctFromCompileError() {
        CycleContextData pkg = CycleContextData.builder().cycleName("c")
                .buildOutcome(new BuildOutcome(BuildResult.PACKAGE_ERROR, "no main class", 1L, "p", "c")).build();
        CycleContextData compile = CycleContextData.builder().cycleName("c")
                .buildOutcome(new BuildOutcome(BuildResult.COMPILE_ERROR, "cannot find symbol", 1L, "c", "c")).build();

        assertThat(heuristic.evaluate(pkg, anyOutcome()).confidence())
                .isLessThan(heuristic.evaluate(compile, anyOutcome()).confidence());
    }
}
