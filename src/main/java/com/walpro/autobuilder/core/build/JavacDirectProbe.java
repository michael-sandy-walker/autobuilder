package com.walpro.autobuilder.core.build;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * T4-BN Phase 1: in-process compile probe using the JDK's {@code javax.tools.JavaCompiler}
 * instead of shelling to {@code mvn compile}. The Maven probe pays a fixed JVM-startup +
 * dependency-resolution cost on every invocation (5–15s typical); this collaborator drops it
 * to near zero by:
 *
 * <ul>
 *   <li>Caching the project classpath once per session via {@code mvn dependency:build-classpath}
 *       (invalidated on {@code pom.xml} mtime change).</li>
 *   <li>Discovering sources under {@code src/main/java/**.java} directly from disk.</li>
 *   <li>Invoking {@code javac} in-process via {@link ToolProvider#getSystemJavaCompiler()}.</li>
 * </ul>
 *
 * <p>Annotation processors (Lombok, MapStruct, …) are auto-discovered via the classpath's
 * {@code META-INF/services/javax.annotation.processing.Processor} entries — no explicit
 * registration needed.
 *
 * <p>Diagnostic output uses native {@code javac} format. The downstream {@link BlockerExtractor}
 * and {@link CompileErrorSymbolExtractor} already accept both Maven and javac shapes
 * (the patterns key on tool-agnostic substrings like {@code error:}, {@code symbol:},
 * {@code package X does not exist}).
 *
 * <p>Requires a JDK (not a JRE) at runtime: {@link ToolProvider#getSystemJavaCompiler()} returns
 * {@code null} on a JRE. Autobuilder requires a JDK to build itself, so this is a non-issue in
 * deployment — but the probe degrades gracefully to {@link BuildResult#SKIPPED} if absent.
 */
@Slf4j
@Component
public class JavacDirectProbe {

    private static final int STDERR_TAIL_LINES = 50;
    private static final long CLASSPATH_RESOLVE_TIMEOUT_MS = 60_000L;

    /** Cached classpath keyed by {@code (workingDir, pomMtimeMs)}. Single-entry; the loop only
     *  ever probes one project, so a multi-entry cache would be overhead with no benefit. */
    private final AtomicReference<ClasspathCacheEntry> classpathCache = new AtomicReference<>();

    private record ClasspathCacheEntry(String workingDir, long pomMtimeMs, String classpath) {}

    /**
     * Runs an in-process javac compile against the project at {@code workingDir}. Mirrors
     * {@link BuildExecutor#execute(String, String)} in shape so callers can swap implementations
     * transparently.
     */
    public BuildOutcome execute(String workingDir, String cycleId, long timeoutMs) {
        if (workingDir == null || workingDir.isBlank()) {
            return BuildOutcome.skipped("no working directory");
        }
        Path projectRoot = Paths.get(workingDir);
        if (!Files.isDirectory(projectRoot)) {
            return BuildOutcome.skipped("working directory does not exist: " + workingDir);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            log.warn("T4-BN: javax.tools.JavaCompiler unavailable (running on JRE not JDK?). Falling back to SKIPPED.");
            return BuildOutcome.skipped("no system javac available (JRE only)");
        }

        long start = System.currentTimeMillis();

        // 1. Resolve classpath (cached per pom mtime).
        String classpath = resolveClasspath(projectRoot, timeoutMs);

        // 2. Discover sources under src/main/java.
        List<File> sources = discoverSources(projectRoot.resolve("src/main/java"));
        if (sources.isEmpty()) {
            long elapsed = System.currentTimeMillis() - start;
            return new BuildOutcome(BuildResult.COMPILE_OK, "", elapsed, "javac-direct", cycleId);
        }

        // 3. Prepare output dir (mirrors maven layout so downstream code that reads from
        //    target/classes for any reason sees a consistent location).
        Path classesDir = projectRoot.resolve("target/classes");
        try {
            Files.createDirectories(classesDir);
        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - start;
            return BuildOutcome.skipped("failed to create target/classes: " + e.getMessage());
        }

        // 4. Invoke javac in-process.
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classesDir.toFile()));
            if (!classpath.isBlank()) {
                List<File> classpathEntries = new ArrayList<>();
                for (String entry : classpath.split(File.pathSeparator)) {
                    if (!entry.isBlank()) classpathEntries.add(new File(entry));
                }
                fileManager.setLocation(StandardLocation.CLASS_PATH, classpathEntries);
            }
            Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromFiles(sources);

            List<String> options = List.of("-Xlint:none", "-nowarn");
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fileManager, diagnostics, options, null, compilationUnits);

            boolean ok = task.call();
            long elapsed = System.currentTimeMillis() - start;

            String stderrTail = formatDiagnostics(diagnostics.getDiagnostics());
            BuildResult result = ok ? BuildResult.COMPILE_OK : BuildResult.COMPILE_ERROR;
            log.info("T4-BN javac-direct: {} (sources={}, durationMs={}, diagnostics={})",
                    result, sources.size(), elapsed, diagnostics.getDiagnostics().size());
            return new BuildOutcome(result, stderrTail, elapsed, "javac-direct", cycleId);
        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("T4-BN: javac-direct invocation failed: {}", e.getMessage());
            return new BuildOutcome(BuildResult.SKIPPED, "javac-direct failed: " + e.getMessage(),
                    elapsed, "javac-direct", cycleId);
        }
    }

    private String resolveClasspath(Path projectRoot, long timeoutMs) {
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
            ProcessBuilder pb = new ProcessBuilder(
                    "mvn", "-q", "dependency:build-classpath",
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

    private static List<File> discoverSources(Path sourceRoot) {
        if (!Files.isDirectory(sourceRoot)) return List.of();
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            return walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .toList();
        } catch (IOException e) {
            log.warn("T4-BN: source discovery failed at {}: {}", sourceRoot, e.getMessage());
            return List.of();
        }
    }

    /**
     * Format javac {@link Diagnostic}s into the same line shape the existing Maven-stderr
     * extractors consume. Each ERROR-kind diagnostic emits one line of the form
     * {@code <file>:<line>: error: <message>} — native javac format, which
     * {@link BlockerExtractor#ERROR_DIAGNOSTIC_LINE} accepts via the {@code error:} branch.
     * NOTE/WARNING/MANDATORY_WARNING are dropped (the downstream extractors don't reason about
     * them and including them would dilute the {@code stderrTail} window).
     */
    private static String formatDiagnostics(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        Deque<String> tail = new ArrayDeque<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
            if (d.getKind() != Diagnostic.Kind.ERROR) continue;
            String location = d.getSource() != null ? d.getSource().toUri().getPath() : "(unknown)";
            long line = d.getLineNumber();
            String message = d.getMessage(Locale.ROOT);
            String formatted = location + ":" + (line > 0 ? line : 0) + ": error: " + message;
            for (String l : formatted.split("\\r?\\n")) {
                tail.addLast(l);
                while (tail.size() > STDERR_TAIL_LINES) tail.removeFirst();
            }
        }
        return String.join("\n", tail);
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

    /** Test-only — clear the classpath cache so a fresh resolve happens on next probe. */
    void __resetCacheForTest() {
        classpathCache.set(null);
    }
}
