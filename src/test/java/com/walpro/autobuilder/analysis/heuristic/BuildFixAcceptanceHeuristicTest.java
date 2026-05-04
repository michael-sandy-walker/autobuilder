package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BuildFixAcceptanceHeuristicTest {

    private final BuildFixAcceptanceHeuristic heuristic = new BuildFixAcceptanceHeuristic();

    private static EvaluationOutcome rejected() {
        return new EvaluationOutcome(0.25, MutationAcceptanceState.REJECTED,
                GoalProgressState.STAGNATING, "build still fails", null);
    }

    private static BuildOutcome compileError() {
        return new BuildOutcome(BuildResult.COMPILE_ERROR, "no POM in this directory", 1000L, "mvn compile", "cycle-1");
    }

    private static CycleContextData ctxWith(BuildOutcome build, EvolutionOutcome evolution) {
        return CycleContextData.builder()
                .cycleName("cycle-test")
                .currentSubGoalId("any-sub")
                .build()
                .withBuildOutcome(build)
                .withEvolutionOutcome(evolution);
    }

    // ── happy path ─────────────────────────────────────────────────────────────

    @Test
    void evaluate_compileError_pomXmlMutation_overridesToAccepted() {
        EvolutionOutcome evolution = new EvolutionOutcome(
                List.of(new FileMutation("pom.xml", "<project>...</project>")),
                List.of(), "add pom.xml", "#pom");

        HeuristicOpinion op = heuristic.evaluate(ctxWith(compileError(), evolution), rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(op.level()).isEqualTo(ConfidenceLevel.CRITICAL);
        assertThat(op.confidence()).isEqualTo(1.0);
        assertThat(op.goalProgress()).isEqualTo(GoalProgressState.PROGRESSING);
    }

    @Test
    void evaluate_compileError_pomXmlInSubdirectory_isRecognisedAsBuildConfig() {
        EvolutionOutcome evolution = new EvolutionOutcome(
                List.of(new FileMutation("output/pom.xml", "<project/>"),
                        new FileMutation("src/main/java/Foo.java", "class Foo {}")),
                List.of(), "fix pom", "#raw");

        HeuristicOpinion op = heuristic.evaluate(ctxWith(compileError(), evolution), rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
    }

    @Test
    void evaluate_compileError_gradleFile_isRecognisedAsBuildConfig() {
        EvolutionOutcome evolution = new EvolutionOutcome(
                List.of(new FileMutation("build.gradle", "plugins { id 'java' }")),
                List.of(), "add gradle", "#raw");

        HeuristicOpinion op = heuristic.evaluate(ctxWith(compileError(), evolution), rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
    }

    @Test
    void evaluate_compileError_plannerCreatedSubgoalWithNoBuildFixMetadata_stillAccepts() {
        // Core scenario: planner replaces a BUILD_FIX subgoal with a plain "Add pom.xml"
        // subgoal (no BUILD_FIX metadata). The heuristic must still fire because the build
        // is concretely failing regardless of the subgoal's metadata tag.
        EvolutionOutcome evolution = new EvolutionOutcome(
                List.of(new FileMutation("pom.xml", "<project/>")),
                List.of(), "add pom.xml to root", "#raw");

        HeuristicOpinion op = heuristic.evaluate(ctxWith(compileError(), evolution), rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
    }

    // ── guard: no COMPILE_ERROR ────────────────────────────────────────────────

    @Test
    void evaluate_noBuildOutcome_abstains() {
        EvolutionOutcome evolution = new EvolutionOutcome(
                List.of(new FileMutation("pom.xml", "<project/>")),
                List.of(), "add pom", "#raw");

        HeuristicOpinion op = heuristic.evaluate(ctxWith(null, evolution), rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);
        assertThat(op.confidence()).isZero();
    }

    @Test
    void evaluate_buildCompileOk_abstains() {
        BuildOutcome ok = new BuildOutcome(BuildResult.COMPILE_OK, "", 500L, "mvn compile", "cycle-1");
        EvolutionOutcome evolution = new EvolutionOutcome(
                List.of(new FileMutation("pom.xml", "<project/>")),
                List.of(), "update pom", "#raw");

        HeuristicOpinion op = heuristic.evaluate(ctxWith(ok, evolution), rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);
    }

    // ── guard: COMPILE_ERROR but no build-config file ──────────────────────────

    @Test
    void evaluate_compileError_javaOnlyMutation_abstains() {
        EvolutionOutcome evolution = new EvolutionOutcome(
                List.of(new FileMutation("src/main/java/Fix.java", "class Fix {}")),
                List.of(), "java only", "#raw");

        HeuristicOpinion op = heuristic.evaluate(ctxWith(compileError(), evolution), rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);
    }

    // ── guard: null / empty evolution ──────────────────────────────────────────

    @Test
    void evaluate_nullEvolutionOutcome_abstains() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c").currentSubGoalId("sub-1")
                .build()
                .withBuildOutcome(compileError());

        HeuristicOpinion op = heuristic.evaluate(ctx, rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);
    }

    @Test
    void evaluate_nullInput_abstains() {
        HeuristicOpinion op = heuristic.evaluate(null, rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);
    }
}
