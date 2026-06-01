package com.walpro.autobuilder.core.build;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final String executorStrategy;

    /** T5-S: gate for the one-shot environment probe — true after the first execute() call. */
    private final AtomicBoolean envProbeDone = new AtomicBoolean(false);

    /** T4-BN: in-process javac probe. Optional — wired by Spring when present. Null in pure unit
     *  tests that construct {@code BuildExecutor} directly via the legacy 4-arg constructor. */
    @Setter
    @Autowired(required = false)
    private JavacDirectProbe javacDirectProbe;

    @Autowired
    public BuildExecutor(
            @Value("${autobuilder.build.enabled:false}") boolean enabled,
            @Value("${autobuilder.build.command:mvn compile}") String command,
            @Value("${autobuilder.build.timeout-ms:120000}") long timeoutMs,
            @Value("${autobuilder.build.working-dir:}") String workingDirOverride,
            @Value("${autobuilder.build.executor:maven}") String executorStrategy
    ) {
        this.enabled = enabled;
        this.command = command;
        this.timeoutMs = timeoutMs;
        this.workingDirOverride = workingDirOverride;
        this.executorStrategy = executorStrategy == null ? "maven" : executorStrategy.toLowerCase(java.util.Locale.ROOT);
    }

    /** Legacy 4-arg constructor used by unit tests; defaults to the maven strategy. */
    public BuildExecutor(boolean enabled, String command, long timeoutMs, String workingDirOverride) {
        this(enabled, command, timeoutMs, workingDirOverride, "maven");
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

        // T4-BN: opt-in fast probe via `autobuilder.build.executor: javac-direct`. Falls back to
        // maven when the in-process javac collaborator is unavailable (e.g. legacy test setup).
        if ("javac-direct".equals(executorStrategy)) {
            if (javacDirectProbe == null) {
                log.warn("T4-BN: autobuilder.build.executor=javac-direct but no JavacDirectProbe wired — falling back to maven.");
            } else {
                return javacDirectProbe.execute(effectiveDir, cycleId, timeoutMs);
            }
        }

        List<String> tokens = tokenize(command);
        if (tokens.isEmpty()) {
            return BuildOutcome.skipped("empty build command");
        }

        // T5-S: log the resolved build-tool version + JDK on the very first execute(...) call
        // of the session. The spawned mvn resolves javac via JAVA_HOME, which can differ from
        // both this JVM's own JDK and the operator's interactive shell. Surfacing that triple
        // once at startup means an operator can diagnose "release version not supported" type
        // errors without re-running mvn -v in the launcher environment.
        if (envProbeDone.compareAndSet(false, true)) {
            probeBuildEnvironment(tokens.get(0), dir);
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

    /**
     * T5-S: one-shot diagnostic probe. Runs {@code <buildTool> -v} in the same {@link ProcessBuilder}
     * environment the real build will use, and logs:
     * <ul>
     *   <li>this JVM's {@code java.home} system property (the JDK running autobuilder itself),</li>
     *   <li>the {@code JAVA_HOME} env var visible to spawned subprocesses (what the spawned mvn
     *       inherits — can differ from {@code java.home}),</li>
     *   <li>the build tool's own version output (Maven version + the JDK that {@code mvn} actually
     *       resolves, which may be a third value if {@code JAVA_HOME} disagrees with the running JVM).</li>
     * </ul>
     * Failures are logged at WARN and swallowed — the real build call must not be blocked by a
     * diagnostic that can't run.
     */
    private void probeBuildEnvironment(String buildTool, File dir) {
        String jvmJavaHome = System.getProperty("java.home");
        String envJavaHome = System.getenv("JAVA_HOME");
        log.info("T5-S: build env — autobuilder JVM java.home={}, JAVA_HOME={}", jvmJavaHome, envJavaHome);

        try {
            ProcessBuilder pb = new ProcessBuilder(buildTool, "-v")
                    .directory(dir)
                    .redirectErrorStream(true);
            Process probe = pb.start();
            Deque<String> out = new ArrayDeque<>();
            Thread drainer = drainerThread(probe.getInputStream(), out);
            drainer.start();
            boolean finished = probe.waitFor(ENV_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                probe.destroyForcibly();
                log.warn("T5-S: '{} -v' probe timed out after {}ms", buildTool, ENV_PROBE_TIMEOUT_MS);
                return;
            }
            drainer.join(500);
            log.info("T5-S: spawned '{} -v' output:\n{}", buildTool, String.join("\n", out));
        } catch (IOException e) {
            log.warn("T5-S: '{} -v' probe failed: {}", buildTool, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("T5-S: '{} -v' probe interrupted: {}", buildTool, e.getMessage());
        }
    }

    /** T5-S: probe must finish quickly — `mvn -v` typically returns in < 200ms. */
    private static final long ENV_PROBE_TIMEOUT_MS = 5_000L;

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
