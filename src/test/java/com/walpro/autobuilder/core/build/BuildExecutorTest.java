package com.walpro.autobuilder.core.build;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

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

    // ── T5-S: one-shot environment probe on first execute() call ──

    @Test
    void execute_logsBuildEnvironmentOnFirstCall(@TempDir Path tempDir) {
        ListAppender<ILoggingEvent> appender = attachAppender();
        // Use `true` (POSIX no-op success) as the build command — keeps the test fast and
        // platform-portable; the probe will run `true -v` which exits non-zero but that's fine,
        // the probe must not crash on probe failure.
        BuildExecutor exec = new BuildExecutor(true, "true", 5_000L, "");

        exec.execute(tempDir.toString(), "test-cycle");

        List<String> messages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
        assertThat(messages).anyMatch(m -> m.startsWith("T5-S: build env"));
        assertThat(messages).anyMatch(m -> m.contains("java.home="));
        assertThat(messages).anyMatch(m -> m.contains("JAVA_HOME="));
    }

    @Test
    void execute_probeRunsOnceAcrossMultipleCalls(@TempDir Path tempDir) {
        ListAppender<ILoggingEvent> appender = attachAppender();
        BuildExecutor exec = new BuildExecutor(true, "true", 5_000L, "");

        exec.execute(tempDir.toString(), "cycle-1");
        exec.execute(tempDir.toString(), "cycle-2");
        exec.execute(tempDir.toString(), "cycle-3");

        long buildEnvLogCount = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("T5-S: build env"))
                .count();
        assertThat(buildEnvLogCount).isEqualTo(1);
    }

    @Test
    void execute_probeFailureDoesNotBreakBuild(@TempDir Path tempDir) {
        // Use a non-existent build tool so the probe `<tool> -v` ProcessBuilder.start() throws.
        // The real build call would also fail in this case (same tool), but the test asserts
        // the probe failure is swallowed: execute() returns a normal SKIPPED/COMPILE_ERROR
        // outcome rather than throwing.
        ListAppender<ILoggingEvent> appender = attachAppender();
        BuildExecutor exec = new BuildExecutor(true,
                "/this/build/tool/does/not/exist/abc123", 5_000L, "");

        BuildOutcome outcome = exec.execute(tempDir.toString(), "cycle-1");

        // Real build would have IOException-ed out via the catch block, returning SKIPPED.
        assertThat(outcome.result()).isEqualTo(BuildResult.SKIPPED);
        // Probe failure should be logged at WARN, not propagated.
        assertThat(appender.list)
                .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("T5-S")
                        && e.getFormattedMessage().contains("probe failed"));
    }

    @Test
    void execute_disabledExecutorDoesNotProbe(@TempDir Path tempDir) {
        ListAppender<ILoggingEvent> appender = attachAppender();
        BuildExecutor exec = new BuildExecutor(false, "true", 5_000L, "");

        exec.execute(tempDir.toString(), "cycle-1");

        assertThat(appender.list)
                .noneMatch(e -> e.getFormattedMessage().startsWith("T5-S"));
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(BuildExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    // ── T4-BN: executor strategy dispatch ─────────────────────────────────────

    @Test
    void execute_javacDirectStrategy_dispatchesToJavacDirectProbe(@TempDir Path tempDir) throws Exception {
        // Stub probe records the call and returns a sentinel outcome.
        java.util.concurrent.atomic.AtomicReference<String> calledWith = new java.util.concurrent.atomic.AtomicReference<>();
        JavacDirectProbe stub = new JavacDirectProbe() {
            @Override
            public BuildOutcome execute(String workingDir, String cycleId, long timeoutMs) {
                calledWith.set(workingDir + "|" + cycleId + "|" + timeoutMs);
                return new BuildOutcome(BuildResult.COMPILE_OK, "", 42L, "javac-direct", cycleId);
            }
        };

        BuildExecutor exec = new BuildExecutor(true, "mvn compile", 12_000L, "", "javac-direct");
        exec.setJavacDirectProbe(stub);

        BuildOutcome outcome = exec.execute(tempDir.toString(), "cycle-jvc");

        assertThat(outcome.command()).isEqualTo("javac-direct");
        assertThat(outcome.result()).isEqualTo(BuildResult.COMPILE_OK);
        assertThat(calledWith.get()).isEqualTo(tempDir + "|cycle-jvc|12000");
    }

    @Test
    void execute_javacDirectStrategy_butNoProbeWired_fallsBackToMaven(@TempDir Path tempDir) {
        // Strategy=javac-direct but no probe collaborator → maven fallback. Command is "true"
        // (returns 0 on Unix), so we expect COMPILE_OK from the maven path.
        BuildExecutor exec = new BuildExecutor(true, "true", 5_000L, "", "javac-direct");
        // Note: setJavacDirectProbe NOT called — collaborator is null.

        BuildOutcome outcome = exec.execute(tempDir.toString(), "cycle-fallback");

        assertThat(outcome.command()).isEqualTo("true"); // maven path used the configured command
    }

    @Test
    void execute_defaultStrategyMaven_doesNotInvokeJavacProbe(@TempDir Path tempDir) {
        java.util.concurrent.atomic.AtomicReference<String> calledWith = new java.util.concurrent.atomic.AtomicReference<>();
        JavacDirectProbe stub = new JavacDirectProbe() {
            @Override
            public BuildOutcome execute(String workingDir, String cycleId, long timeoutMs) {
                calledWith.set("called");
                return new BuildOutcome(BuildResult.COMPILE_OK, "", 0L, "javac-direct", cycleId);
            }
        };

        BuildExecutor exec = new BuildExecutor(true, "true", 5_000L, ""); // legacy 4-arg = maven default
        exec.setJavacDirectProbe(stub);

        BuildOutcome outcome = exec.execute(tempDir.toString(), "cycle-mvn");

        assertThat(calledWith.get()).isNull();
        assertThat(outcome.command()).isEqualTo("true");
    }
}
