package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * T4-AN-5: delegate compatibility/coordinate resolution to the build tool itself. When the build
 * fails, the LLM otherwise has to guess coordinates/versions from training memory (observed
 * 2026-05-04: it repeatedly emitted {@code <release>21</release>} with maven-compiler-plugin 3.1,
 * which doesn't support {@code <release>}, burning 6 of 12 cycles on pom-only churn). Instead, run
 * the tool's own diagnostic commands and surface their output to the prompt — the tool is the
 * source of truth for its ecosystem, and adding a new build system is a yaml-only change.
 *
 * <p>All detection rules and diagnostic commands come from {@link BuildToolProbeProperties} (yaml),
 * never from literals here. Diagnostics are slower than {@code compile}, so callers run this only
 * on {@code COMPILE_ERROR} and the result is cached by {@code cycleId} (one diagnostic run per
 * cycle regardless of how many prompt sections render it). Command failures/timeouts are folded in
 * as informational text — a diagnostic that can't run must never mask the underlying compile error.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "a within-cycle memo of the build tool probe")
public class BuildToolProbe {

    private final BuildToolProbeProperties props;

    /** Per-cycle cache: diagnostics run at most once per cycleId. */
    private String cachedCycleId;
    private String cachedDiagnostic;

    /** Build tools whose defining file is present in {@code workingDir}, per the yaml detection map. */
    public Set<String> detectTools(String workingDir) {
        Set<String> tools = new LinkedHashSet<>();
        if (workingDir == null || workingDir.isBlank()) return tools;
        File dir = new File(workingDir);
        for (var e : props.getToolDetection().entrySet()) {
            if (new File(dir, e.getKey()).isFile()) {
                tools.add(e.getValue());
            }
        }
        return tools;
    }

    /**
     * Run the configured diagnostic commands for every detected tool in {@code workingDir} and
     * return their trimmed, concatenated output — or {@code null} when disabled, no tool is
     * detected, or nothing is configured. Cached by {@code cycleId}; never throws.
     */
    public synchronized String diagnose(String workingDir, String cycleId) {
        if (cycleId != null && cycleId.equals(cachedCycleId)) return cachedDiagnostic;

        String result = runDiagnostics(workingDir);
        cachedCycleId = cycleId;
        cachedDiagnostic = result;
        return result;
    }

    private String runDiagnostics(String workingDir) {
        Set<String> tools = detectTools(workingDir);
        if (tools.isEmpty()) return null;
        File dir = new File(workingDir);

        List<String> lines = new ArrayList<>();
        for (String tool : tools) {
            List<String> commands = props.getDiagnostics().getOrDefault(tool, List.of());
            for (String command : commands) {
                if (command == null || command.isBlank()) continue;
                lines.add("$ " + command);
                lines.addAll(runOne(command, dir));
            }
        }
        if (lines.isEmpty()) return null;
        // Keep the trailing lines (the tool's result usually lands last); bound prompt cost.
        int cap = Math.max(1, props.getDiagnosticLineCap());
        if (lines.size() > cap) {
            lines = new ArrayList<>(lines.subList(lines.size() - cap, lines.size()));
            lines.add(0, "(diagnostic output truncated to last " + cap + " lines)");
        }
        return String.join("\n", lines);
    }

    /** Runs one diagnostic command, capturing its trailing output; failures become informational. */
    private List<String> runOne(String command, File dir) {
        List<String> tokens = Arrays.stream(command.trim().split("\\s+")).filter(s -> !s.isBlank()).toList();
        if (tokens.isEmpty()) return List.of();
        Process process = null;
        try {
            process = new ProcessBuilder(tokens).directory(dir).redirectErrorStream(true).start();
            Deque<String> tail = new ArrayDeque<>();
            Thread drainer = drainer(process, tail);
            drainer.start();
            boolean finished = process.waitFor(props.getDiagnosticTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return List.of("(diagnostic timed out after " + props.getDiagnosticTimeoutMs() + "ms)");
            }
            drainer.join(1000);
            return new ArrayList<>(tail);
        } catch (IOException e) {
            return List.of("(diagnostic failed: " + e.getMessage() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return List.of("(diagnostic interrupted)");
        }
    }

    private Thread drainer(Process process, Deque<String> tail) {
        int cap = Math.max(1, props.getDiagnosticLineCap());
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    tail.addLast(line);
                    while (tail.size() > cap) tail.removeFirst();
                }
            } catch (IOException ignored) {
                // process exited / stream closed — fine.
            }
        });
        t.setDaemon(true);
        return t;
    }

    /** Test/diagnostic hook — drop the per-cycle cache. */
    public synchronized void clearCache() {
        cachedCycleId = null;
        cachedDiagnostic = null;
    }
}
