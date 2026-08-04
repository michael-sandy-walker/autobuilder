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

    private final BuildFixAcceptanceHeuristic heuristic = new BuildFixAcceptanceHeuristic(com.walpro.autobuilder.core.build.TestBuildToolRegistry.classifier());

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

    private static BuildOutcome containerMismatch(String consumerPath) {
        // Verbatim shape from run 2026-08-02_20-33-26. Note the coordinate names ONLY the
        // consumer — the producer's file never appears anywhere in the diagnostic.
        return new BuildOutcome(BuildResult.COMPILE_ERROR,
                "[ERROR] " + consumerPath + ":[41,68] incompatible types: "
                        + "com.example.library.model.Book cannot be converted to "
                        + "java.util.Optional<com.example.library.model.Book>",
                1000L, "mvn compile", "cycle-1");
    }

    // ── happy path ─────────────────────────────────────────────────────────────

    @Test
    void evaluate_containerMismatch_acceptsTheProducerTheCompilerNeverNamed() {
        // T2-FE: the repair belongs in the repository's return type, and that file appears in no
        // diagnostic, so neither the subgoal scope nor T2-DQ's stderr-named rule admits it.
        EvolutionOutcome evolution = new EvolutionOutcome(
                List.of(new FileMutation("src/main/java/com/example/library/repository/BookRepository.java",
                        "public class BookRepository {\n"
                                + "    public Optional<Book> findById(Long id) { return null; }\n}")),
                List.of(), "return Optional", "#fix");

        HeuristicOpinion op = heuristic.evaluate(ctxWith(
                containerMismatch("/out/src/main/java/com/example/library/service/LoanService.java"),
                evolution), rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
    }

    @Test
    void evaluate_containerMismatch_ignoresAFileThatMerelyMentionsTheType() {
        // Declaration, not mention — otherwise every file touching Book would be exempt.
        EvolutionOutcome evolution = new EvolutionOutcome(
                List.of(new FileMutation("src/main/java/com/example/library/util/Helper.java",
                        "public class Helper {\n    void log(Book b) { System.out.println(b); }\n}")),
                List.of(), "unrelated", "#x");

        HeuristicOpinion op = heuristic.evaluate(ctxWith(
                containerMismatch("/out/src/main/java/com/example/library/service/LoanService.java"),
                evolution), rejected());

        assertThat(op.mutationState()).isNotEqualTo(MutationAcceptanceState.ACCEPTED);
    }

    @Test
    void evaluate_noContainerMismatchInStderr_leavesTheCarveOutClosed() {
        // The exemption is gated on the wrapping shape; an ordinary compile error must not widen
        // BUILD_FIX scope, which is the failure T2-DQ's sibling ticket records.
        EvolutionOutcome evolution = new EvolutionOutcome(
                List.of(new FileMutation("src/main/java/com/example/Other.java",
                        "class Other { public Book findById(Long id) { return null; } }")),
                List.of(), "unrelated", "#x");

        HeuristicOpinion op = heuristic.evaluate(ctxWith(compileError(), evolution), rejected());

        assertThat(op.mutationState()).isNotEqualTo(MutationAcceptanceState.ACCEPTED);
    }


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

    // ── T1-DQ: consecutive-failure dampener ────────────────────────────────────

    @Test
    void evaluate_belowMaxAttempts_stillOverrides() {
        EvolutionOutcome evolution = new EvolutionOutcome(
                List.of(new FileMutation("pom.xml", "<project/>")),
                List.of(), "fix pom", "#raw");

        CycleContextData ctx = ctxWith(compileError(), evolution)
                .withConsecutiveBuildFixAttempts(2); // below cap of 3

        HeuristicOpinion op = heuristic.evaluate(ctx, rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
    }

    @Test
    void evaluate_atMaxAttempts_abstains() {
        EvolutionOutcome evolution = new EvolutionOutcome(
                List.of(new FileMutation("pom.xml", "<project/>")),
                List.of(), "fix pom", "#raw");

        CycleContextData ctx = ctxWith(compileError(), evolution)
                .withConsecutiveBuildFixAttempts(3); // at cap — must abstain

        HeuristicOpinion op = heuristic.evaluate(ctx, rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);
        assertThat(op.confidence()).isZero();
    }

    @Test
    void evaluate_aboveMaxAttempts_abstains() {
        EvolutionOutcome evolution = new EvolutionOutcome(
                List.of(new FileMutation("pom.xml", "<project/>")),
                List.of(), "fix pom", "#raw");

        CycleContextData ctx = ctxWith(compileError(), evolution)
                .withConsecutiveBuildFixAttempts(10);

        HeuristicOpinion op = heuristic.evaluate(ctx, rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);
    }

    // ── T2-DQ: stderr-named Java source override ───────────────────────────────

    private static BuildOutcome compileErrorOnFile(String fileCoord) {
        // Maven-style file-coordinate prefix: "<path>.java:[line,col]"
        String stderr = "[ERROR] " + fileCoord + " cannot find symbol\n" +
                "  symbol:   method foo()\n" +
                "  location: class Bar";
        return new BuildOutcome(BuildResult.COMPILE_ERROR, stderr, 1200L, "mvn compile", "cycle-1");
    }

    @Test
    void evaluate_compileError_mutationTouchesStderrNamedJavaFile_overridesToAccepted() {
        // T2-DQ: the LLM evaluator rejected this Java edit as "off-subgoal" but the compile
        // error itself names this exact file as the failure site → ACCEPTED.
        EvolutionOutcome evolution = new EvolutionOutcome(
                List.of(new FileMutation("src/main/java/com/example/Book.java",
                        "class Book { public Book(String t) {} }")),
                List.of(), "add constructor", "#raw");

        BuildOutcome build = compileErrorOnFile("/abs/output/src/main/java/com/example/Book.java:[12,5]");

        HeuristicOpinion op = heuristic.evaluate(ctxWith(build, evolution), rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(op.level()).isEqualTo(ConfidenceLevel.CRITICAL);
        assertThat(op.confidence()).isEqualTo(1.0);
    }

    @Test
    void evaluate_compileError_mutationTouchesUnrelatedJavaFile_abstains() {
        // T2-DQ guard: even though build is COMPILE_ERROR, a Java edit on a file not named
        // by the compiler's diagnostic is left to the LLM evaluator. No override.
        EvolutionOutcome evolution = new EvolutionOutcome(
                List.of(new FileMutation("src/main/java/com/example/Other.java", "class Other {}")),
                List.of(), "other", "#raw");

        BuildOutcome build = compileErrorOnFile("/abs/output/src/main/java/com/example/Book.java:[12,5]");

        HeuristicOpinion op = heuristic.evaluate(ctxWith(build, evolution), rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);
    }

    @Test
    void evaluate_compileError_stderrNamedFileMatchByBasename_overridesToAccepted() {
        // T2-DQ: basename comparison handles directory-prefix differences between absolute
        // compiler paths and our relative output/... mutation paths.
        EvolutionOutcome evolution = new EvolutionOutcome(
                List.of(new FileMutation("output/Book.java", "class Book {}")),
                List.of(), "add Book", "#raw");

        BuildOutcome build = compileErrorOnFile("/tmp/some/deep/path/Book.java:[5,9]");

        HeuristicOpinion op = heuristic.evaluate(ctxWith(build, evolution), rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
    }

    @Test
    void evaluate_compileError_stderrNamedFile_atMaxAttempts_abstains() {
        // T2-DQ + T2-CV: the stuck-retry guard still fires for stderr-named-file matches.
        EvolutionOutcome evolution = new EvolutionOutcome(
                List.of(new FileMutation("src/main/java/com/example/Book.java", "class Book {}")),
                List.of(), "edit Book", "#raw");

        CycleContextData ctx = ctxWith(compileErrorOnFile("Book.java:[1,1]"), evolution)
                .withConsecutiveBuildFixAttempts(3);

        HeuristicOpinion op = heuristic.evaluate(ctx, rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);
    }
}
