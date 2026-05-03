package com.walpro.autobuilder.core.build;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * T4-AN-1: post-merge compile gate. Runs the configured build command (default {@code mvn
 * compile}) against the target project root and reports the outcome. Produces ground-truth
 * compilation evidence — REJECTED-at-CRITICAL when the merged code does not compile.
 *
 * <p>The executor is always present in the context; the heuristic that consumes its output is
 * registered conditionally via {@code autobuilder.build.enabled}. Callers should consult
 * {@link #isEnabled()} before invoking {@link #execute(String)}.
 */
@Slf4j
@Component
public class BuildExecutor {

    private final boolean enabled;
    private final String command;
    private final long timeoutMs;
    private final String workingDirOverride;

    public BuildExecutor(
            @Value("${autobuilder.build.enabled:false}") boolean enabled,
            @Value("${autobuilder.build.command:mvn compile}") String command,
            @Value("${autobuilder.build.timeout-ms:120000}") long timeoutMs,
            @Value("${autobuilder.build.working-dir:}") String workingDirOverride
    ) {
        this.enabled = enabled;
        this.command = command;
        this.timeoutMs = timeoutMs;
        this.workingDirOverride = workingDirOverride;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Runs the configured build command in {@code workingDir} (or the configured override).
     * Returns a {@link BuildOutcome} that always has a non-null {@link BuildResult}.
     *
     * <p>Process management notes:
     * <ul>
     *   <li>stderr is captured and stored as the trailing 50 lines (~hard cap on size).</li>
     *   <li>stdout is drained to prevent buffer-fill deadlocks but not stored.</li>
     *   <li>If the timeout fires the process is forcibly destroyed.</li>
     *   <li>If the executor is disabled the call is a no-op returning {@link BuildResult#SKIPPED}.</li>
     * </ul>
     */
    public BuildOutcome execute(String workingDir, String cycleId) {
        if (!enabled) {
            return BuildOutcome.skipped("build executor disabled");
        }

        String effectiveDir = workingDirOverride != null && !workingDirOverride.isBlank()
                ? workingDirOverride
                : workingDir;
        if (effectiveDir == null || effectiveDir.isBlank()) {
            return BuildOutcome.skipped("no working directory");
        }
        File dir = new File(effectiveDir);
        if (!dir.isDirectory()) {
            return BuildOutcome.skipped("working directory does not exist: " + effectiveDir);
        }

        List<String> tokens = tokenize(command);
        if (tokens.isEmpty()) {
            return BuildOutcome.skipped("empty build command");
        }

        long start = System.currentTimeMillis();
        Process process = null;
        try {
            // redirectErrorStream(true) merges stderr into stdout — Maven writes [ERROR] lines to
            // stdout, not stderr, so we must capture the merged stream to get compiler diagnostics.
            ProcessBuilder pb = new ProcessBuilder(tokens)
                    .directory(dir)
                    .redirectErrorStream(true);
            process = pb.start();

            Deque<String> stderrTail = new ArrayDeque<>();
            Thread outputCapture = drainerThread(process.getInputStream(), stderrTail);

            outputCapture.start();

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                outputCapture.join(1000);
                long elapsed = System.currentTimeMillis() - start;
                String tail = String.join("\n", stderrTail);
                log.warn("BuildExecutor: timeout after {}ms running '{}' in {}", timeoutMs, command, effectiveDir);
                return new BuildOutcome(BuildResult.TIMEOUT, tail, elapsed, command, cycleId);
            }

            outputCapture.join(1000);
            long elapsed = System.currentTimeMillis() - start;
            int exit = process.exitValue();
            String tail = String.join("\n", stderrTail);

            BuildResult result = exit == 0 ? BuildResult.COMPILE_OK : BuildResult.COMPILE_ERROR;
            log.info("BuildExecutor: {} (exit={}, durationMs={}, stderrLines={})", result, exit, elapsed, stderrTail.size());
            return new BuildOutcome(result, tail, elapsed, command, cycleId);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            long elapsed = System.currentTimeMillis() - start;
            log.warn("BuildExecutor: invocation failed: {}", e.getMessage());
            return new BuildOutcome(BuildResult.SKIPPED, "build invocation failed: " + e.getMessage(), elapsed, command, cycleId);
        }
    }

    private static final int STDERR_TAIL_LINES = 50;

    private static Thread drainerThread(InputStream in, Deque<String> tail) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (tail != null) {
                        tail.addLast(line);
                        while (tail.size() > STDERR_TAIL_LINES) {
                            tail.removeFirst();
                        }
                    }
                }
            } catch (IOException ignored) {
                // process exited or stream closed — fine.
            }
        });
        t.setDaemon(true);
        return t;
    }

    /**
     * Minimal command tokenization — splits on whitespace. Sufficient for the simple
     * {@code mvn compile} default and most overrides; users needing shell semantics should
     * prefix the command with {@code sh -c }.
     */
    private static List<String> tokenize(String cmd) {
        if (cmd == null || cmd.isBlank()) return List.of();
        return Arrays.stream(cmd.trim().split("\\s+")).toList();
    }
}
