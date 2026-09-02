package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One home for "what is this project's resolved dependency classpath?" — extracted from
 * {@link JavacDirectProbe} (T4-BN) when T4-BM's package discovery became a second consumer.
 * Shells {@code mvn dependency:build-classpath} once per {@code (workingDir, pom mtime)} and
 * caches the result, so both the compile probe and discovery read the SAME project-resolved
 * classpath (the project's own dependency tree, not any registry's assumption about it).
 */
@Slf4j
@Component
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "memo of the resolved classpath; a restored tree re-resolves it")
public class ProjectClasspathService {

    private static final long CLASSPATH_RESOLVE_TIMEOUT_MS = 60_000L;

    /** Cached classpath keyed by {@code (workingDir, pomMtimeMs)}. Single-entry; the loop only
     *  ever probes one project, so a multi-entry cache would be overhead with no benefit. */
    private final AtomicReference<ClasspathCacheEntry> classpathCache = new AtomicReference<>();

    private record ClasspathCacheEntry(String workingDir, long pomMtimeMs, String classpath) {}

    /**
     * The project's resolved dependency classpath ({@code File.pathSeparator}-joined jar paths),
     * or {@code ""} when it cannot be resolved (no pom, mvn failure, timeout). Cached until the
     * pom's mtime changes.
     */
    public String resolveClasspath(Path projectRoot, long timeoutMs) {
        Path pomPath = projectRoot.resolve("pom.xml");
        long pomMtime;
        try {
            pomMtime = Files.exists(pomPath) ? Files.getLastModifiedTime(pomPath).toMillis() : 0L;
        } catch (IOException e) {
            log.warn("T4-BN: failed to read pom mtime: {}", e.getMessage());
            pomMtime = 0L;
        }

        ClasspathCacheEntry cached = classpathCache.get();
        if (cached != null
                && cached.workingDir().equals(projectRoot.toString())
                && cached.pomMtimeMs() == pomMtime) {
            return cached.classpath();
        }

        if (!Files.exists(pomPath)) {
            log.info("T4-BN: no pom.xml at {} — proceeding with empty classpath", pomPath);
            classpathCache.set(new ClasspathCacheEntry(projectRoot.toString(), 0L, ""));
            return "";
        }

        String resolved = shellMvnBuildClasspath(projectRoot, Math.min(timeoutMs, CLASSPATH_RESOLVE_TIMEOUT_MS));
        classpathCache.set(new ClasspathCacheEntry(projectRoot.toString(), pomMtime, resolved));
        return resolved;
    }

    private String shellMvnBuildClasspath(Path projectRoot, long timeoutMs) {
        Path outFile;
        try {
            outFile = Files.createTempFile("autobuilder-cp-", ".txt");
        } catch (IOException e) {
            log.warn("T4-BN: failed to create temp file for classpath resolve: {}", e.getMessage());
            return "";
        }

        long start = System.currentTimeMillis();
        Process process = null;
        try {
            // T4-BN-2: includeScope=test pulls in test-scoped deps (the test framework, mocking,
            // spring-boot-starter-test) so the test-compile pass can resolve them. It is a superset
            // of the compile/runtime classpath, so the main pass is unaffected by the extra jars.
            ProcessBuilder pb = new ProcessBuilder(
                    "mvn", "-q", "dependency:build-classpath",
                    "-DincludeScope=test",
                    "-Dmdep.outputFile=" + outFile.toAbsolutePath())
                    .directory(projectRoot.toFile())
                    .redirectErrorStream(true);
            process = pb.start();

            Deque<String> tail = new ArrayDeque<>();
            Thread drainer = drainerThread(process.getInputStream(), tail);
            drainer.start();

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("T4-BN: classpath resolve timed out after {}ms", timeoutMs);
                return "";
            }
            drainer.join(1000);

            long elapsed = System.currentTimeMillis() - start;
            int exit = process.exitValue();
            if (exit != 0) {
                log.warn("T4-BN: classpath resolve failed (exit={}, durationMs={}): {}",
                        exit, elapsed, String.join("\n", tail));
                return "";
            }

            String classpath = Files.exists(outFile) ? Files.readString(outFile).trim() : "";
            log.info("T4-BN: classpath resolved (entries={}, durationMs={})",
                    classpath.isBlank() ? 0 : classpath.split(File.pathSeparator).length, elapsed);
            return classpath;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            if (process != null && process.isAlive()) process.destroyForcibly();
            log.warn("T4-BN: classpath resolve invocation failed: {}", e.getMessage());
            return "";
        } finally {
            try { Files.deleteIfExists(outFile); } catch (IOException ignored) {}
        }
    }

    private static Thread drainerThread(InputStream in, Deque<String> tail) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (tail != null) {
                        tail.addLast(line);
                        while (tail.size() > 50) tail.removeFirst();
                    }
                }
            } catch (IOException ignored) {}
        });
        t.setDaemon(true);
        return t;
    }

    /** Test-only — clear the classpath cache so a fresh resolve happens on next call. */
    void __resetCacheForTest() {
        classpathCache.set(null);
    }
}
