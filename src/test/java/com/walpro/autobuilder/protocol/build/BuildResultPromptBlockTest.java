package com.walpro.autobuilder.protocol.build;

import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-AN-3: tests for the shared {@link BuildResultPromptBlock} formatter consumed by both
 * the evaluation and evolution contracts.
 */
class BuildResultPromptBlockTest {

    private static CycleContextData ctxWith(BuildOutcome outcome) {
        return CycleContextData.builder().cycleName("c").buildOutcome(outcome).build();
    }

    @Test
    void render_nullOutcome_returnsNull() {
        CycleContextData ctx = CycleContextData.builder().cycleName("c").build();
        assertThat(BuildResultPromptBlock.render(ctx, BuildResultPromptBlock.Audience.EVALUATION)).isNull();
        assertThat(BuildResultPromptBlock.render(ctx, BuildResultPromptBlock.Audience.EVOLUTION)).isNull();
    }

    @Test
    void render_compileOk_returnsNull() {
        CycleContextData ctx = ctxWith(new BuildOutcome(BuildResult.COMPILE_OK, "", 100L, "mvn compile", "c"));
        assertThat(BuildResultPromptBlock.render(ctx, BuildResultPromptBlock.Audience.EVALUATION)).isNull();
        assertThat(BuildResultPromptBlock.render(ctx, BuildResultPromptBlock.Audience.EVOLUTION)).isNull();
    }

    @Test
    void render_skipped_returnsNull() {
        CycleContextData ctx = ctxWith(BuildOutcome.skipped("disabled"));
        assertThat(BuildResultPromptBlock.render(ctx, BuildResultPromptBlock.Audience.EVALUATION)).isNull();
        assertThat(BuildResultPromptBlock.render(ctx, BuildResultPromptBlock.Audience.EVOLUTION)).isNull();
    }

    @Test
    void render_compileError_evaluationAudience_endsWithRejectedDirective() {
        BuildOutcome failed = new BuildOutcome(
                BuildResult.COMPILE_ERROR,
                "[ERROR] cannot find symbol\n[ERROR] symbol: class Foo",
                1234L, "mvn compile", "c");
        String block = BuildResultPromptBlock.render(ctxWith(failed), BuildResultPromptBlock.Audience.EVALUATION);

        assertThat(block).isNotNull();
        assertThat(block)
                .contains("--- BUILD RESULT ---")
                .contains("Result: COMPILE_ERROR")
                .contains("Command: mvn compile")
                .contains("Duration: 1234ms")
                .contains("cannot find symbol")
                .contains("Score this mutation REJECTED");
    }

    @Test
    void render_compileError_evolutionAudience_endsWithFixDirective_andNoScoreLanguage() {
        BuildOutcome failed = new BuildOutcome(
                BuildResult.COMPILE_ERROR,
                "[ERROR] no POM in this directory",
                100L, "mvn compile", "c");
        String block = BuildResultPromptBlock.render(ctxWith(failed), BuildResultPromptBlock.Audience.EVOLUTION);

        assertThat(block).isNotNull();
        assertThat(block)
                .contains("--- BUILD RESULT ---")
                .contains("Result: COMPILE_ERROR")
                .contains("no POM in this directory")
                .contains("MUST address the diagnostic")
                .contains("do NOT respond with #NoDiff")
                .contains("ground truth");
        // Evolution audience must not contain the evaluator's REJECTED-scoring instruction.
        assertThat(block).doesNotContain("Score this mutation REJECTED");
    }

    @Test
    void render_truncatesStderrTo20Lines() {
        StringBuilder bigStderr = new StringBuilder();
        for (int i = 1; i <= 50; i++) bigStderr.append("[ERROR] line-").append(i).append("\n");
        BuildOutcome failed = new BuildOutcome(
                BuildResult.COMPILE_ERROR, bigStderr.toString().trim(), 100L, "mvn compile", "c");
        String block = BuildResultPromptBlock.render(ctxWith(failed), BuildResultPromptBlock.Audience.EVOLUTION);

        assertThat(block).isNotNull();
        // Last 20 lines should be present; earlier ones should not.
        assertThat(block).contains("line-50").contains("line-31");
        assertThat(block).doesNotContain("line-1\n").doesNotContain("line-30\n");
    }

    @Test
    void render_compileError_plannerAudience_usesBuildResultHeading_andBuildFirstDirective() {
        BuildOutcome failed = new BuildOutcome(
                BuildResult.COMPILE_ERROR,
                "[ERROR] cannot find symbol: HelloController",
                100L, "mvn compile", "c");
        String block = BuildResultPromptBlock.render(ctxWith(failed), BuildResultPromptBlock.Audience.PLANNER);

        assertThat(block).isNotNull();
        assertThat(block)
                .contains("--- BUILD RESULT ---")
                .contains("Result: COMPILE_ERROR")
                .contains("cannot find symbol: HelloController")
                .contains("only valid #SubGoalAppend is build-fix work")
                .contains("[BUILD_FIX]");
        // Planner audience must not contain the evaluator's REJECTED-scoring instruction
        // or the evolver's #NoDiff directive.
        assertThat(block).doesNotContain("Score this mutation REJECTED");
        assertThat(block).doesNotContain("do NOT respond with #NoDiff");
    }

    @Test
    void render_compileOk_plannerAudience_returnsNull() {
        CycleContextData ctx = ctxWith(new BuildOutcome(BuildResult.COMPILE_OK, "", 100L, "mvn compile", "c"));
        assertThat(BuildResultPromptBlock.render(ctx, BuildResultPromptBlock.Audience.PLANNER)).isNull();
    }

    @Test
    void render_timeout_includesDurationLine() {
        BuildOutcome timed = new BuildOutcome(BuildResult.TIMEOUT, "(no stderr captured)", 120_000L, "mvn compile", "c");
        String block = BuildResultPromptBlock.render(ctxWith(timed), BuildResultPromptBlock.Audience.EVALUATION);

        assertThat(block).isNotNull();
        assertThat(block).contains("Result: TIMEOUT").contains("Duration: 120000ms");
    }

    // ── T4-AN-5: build-tool diagnostic section ──

    @Test
    void render_buildDiagnostic_shownToEvolutionAndPlanner_notEvaluation() {
        CycleContextData ctx = ctxWith(new BuildOutcome(BuildResult.COMPILE_ERROR, "[ERROR] boom", 100L, "mvn compile", "c"))
                .withBuildDiagnostic("[INFO] +- org.springframework.boot:spring-boot-starter-web:jar:3.2.0");

        String evo = BuildResultPromptBlock.render(ctx, BuildResultPromptBlock.Audience.EVOLUTION);
        String plan = BuildResultPromptBlock.render(ctx, BuildResultPromptBlock.Audience.PLANNER);
        String eval = BuildResultPromptBlock.render(ctx, BuildResultPromptBlock.Audience.EVALUATION);

        assertThat(evo).contains("--- BUILD DIAGNOSTIC").contains("spring-boot-starter-web");
        assertThat(plan).contains("--- BUILD DIAGNOSTIC").contains("spring-boot-starter-web");
        assertThat(eval).isNotNull().doesNotContain("BUILD DIAGNOSTIC"); // evaluator only scores REJECTED
    }

    @Test
    void render_noBuildDiagnostic_noSection() {
        CycleContextData ctx = ctxWith(new BuildOutcome(BuildResult.COMPILE_ERROR, "[ERROR] boom", 100L, "mvn compile", "c"));
        String evo = BuildResultPromptBlock.render(ctx, BuildResultPromptBlock.Audience.EVOLUTION);
        assertThat(evo).isNotNull().doesNotContain("BUILD DIAGNOSTIC");
    }

    /**
     * Rule-extraction run 2026-10-18_18-30-42: one real error, then follow-on errors in classes the change never
     * touched; the last twenty lines held only those, and the planner made a work item of them. The block shows the
     * cause and counts the rest.
     */
    @Test
    void theCauseIsShown_andTheErrorsInFilesTheChangeDidNotWriteAreCounted() {
        StringBuilder err = new StringBuilder("[ERROR] /o/src/main/java/z/Surface.java:[312,36] method read(java.lang.String) is already defined in class z.Surface\n");
        for (int i = 0; i < 25; i++) {
            err.append("[ERROR] /o/src/main/java/z/Other").append(i).append(".java:[81,13] cannot find symbol\n")
               .append("[ERROR]   symbol:   variable log\n")
               .append("[ERROR]   location: class z.Other").append(i).append('\n');
        }
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .buildOutcome(new BuildOutcome(BuildResult.COMPILE_ERROR, err.toString(), 100L, "mvn compile", "c"))
                .evolutionOutcome(new com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome(
                        java.util.List.of(new com.walpro.autobuilder.agent.evolution.model.FileMutation("src/main/java/z/Surface.java", "x")),
                        java.util.List.of(), "s", "raw"))
                .build();

        String block = BuildResultPromptBlock.render(ctx, BuildResultPromptBlock.Audience.PLANNER);

        assertThat(block).contains("Surface.java:[312,36] method read(java.lang.String) is already defined")
                .doesNotContain("variable log")
                .contains("25 more error(s) in 25 file(s) the change did not write");
    }
}
