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
    void render_timeout_includesDurationLine() {
        BuildOutcome timed = new BuildOutcome(BuildResult.TIMEOUT, "(no stderr captured)", 120_000L, "mvn compile", "c");
        String block = BuildResultPromptBlock.render(ctxWith(timed), BuildResultPromptBlock.Audience.EVALUATION);

        assertThat(block).isNotNull();
        assertThat(block).contains("Result: TIMEOUT").contains("Duration: 120000ms");
    }
}
