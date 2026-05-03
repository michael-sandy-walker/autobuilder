package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BuildExecutorTest {

    @Test
    void execute_disabled_returnsSkipped() {
        BuildExecutor exec = new BuildExecutor(false, "true", 5_000L, "");
        BuildOutcome outcome = exec.execute("/tmp", "test-cycle");

        assertThat(outcome.result()).isEqualTo(BuildResult.SKIPPED);
        assertThat(exec.isEnabled()).isFalse();
    }

    @Test
    void execute_missingWorkingDir_returnsSkipped() {
        BuildExecutor exec = new BuildExecutor(true, "true", 5_000L, "");
        BuildOutcome outcome = exec.execute("", "test-cycle");

        assertThat(outcome.result()).isEqualTo(BuildResult.SKIPPED);
    }

    @Test
    void execute_workingDirDoesNotExist_returnsSkipped() {
        BuildExecutor exec = new BuildExecutor(true, "true", 5_000L, "");
        BuildOutcome outcome = exec.execute("/this/path/definitely/does/not/exist/abc123", "test-cycle");

        assertThat(outcome.result()).isEqualTo(BuildResult.SKIPPED);
    }

    @Test
    void execute_successfulCommand_returnsCompileOk(@TempDir Path tempDir) {
        BuildExecutor exec = new BuildExecutor(true, "true", 5_000L, "");
        BuildOutcome outcome = exec.execute(tempDir.toString(), "test-cycle");

        assertThat(outcome.result()).isEqualTo(BuildResult.COMPILE_OK);
        assertThat(outcome.command()).isEqualTo("true");
        assertThat(outcome.durationMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void execute_failingCommand_returnsCompileError(@TempDir Path tempDir) {
        BuildExecutor exec = new BuildExecutor(true, "false", 5_000L, "");
        BuildOutcome outcome = exec.execute(tempDir.toString(), "test-cycle");

        assertThat(outcome.result()).isEqualTo(BuildResult.COMPILE_ERROR);
        assertThat(outcome.failed()).isTrue();
    }

    @Test
    void execute_returnsNonNullStderrTailField(@TempDir Path tempDir) {
        // Whitespace tokenizer doesn't shell-expand quotes, so we can't easily inject stderr
        // text in a unit test. Contract under verification: stderrTail is never null even on
        // commands that produce no stderr — downstream consumers (heuristic, prompt block) rely
        // on this.
        BuildExecutor exec = new BuildExecutor(true, "false", 5_000L, "");
        BuildOutcome outcome = exec.execute(tempDir.toString(), "test-cycle");

        assertThat(outcome.result()).isEqualTo(BuildResult.COMPILE_ERROR);
        assertThat(outcome.stderrTail()).isNotNull();
    }

    @Test
    void execute_workingDirOverride_takesPrecedence(@TempDir Path tempDir) {
        BuildExecutor exec = new BuildExecutor(true, "true", 5_000L, tempDir.toString());
        // Pass a bogus working dir; the override should win.
        BuildOutcome outcome = exec.execute("/this/path/definitely/does/not/exist/abc123", "test-cycle");

        assertThat(outcome.result()).isEqualTo(BuildResult.COMPILE_OK);
    }

    @Test
    void execute_emptyCommand_returnsSkipped(@TempDir Path tempDir) {
        BuildExecutor exec = new BuildExecutor(true, "  ", 5_000L, "");
        BuildOutcome outcome = exec.execute(tempDir.toString(), "test-cycle");

        assertThat(outcome.result()).isEqualTo(BuildResult.SKIPPED);
    }
}
