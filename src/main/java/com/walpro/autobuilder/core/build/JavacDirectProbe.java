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
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 *   <li>Discovering sources under {@code src/main/java/**.java} (and {@code src/test/java} when
 *       test-compile coverage is enabled, T4-BN-2) directly from disk.</li>
 *   <li>Invoking {@code javac} in-process via {@link ToolProvider#getSystemJavaCompiler()}.</li>
 * </ul>
 *
 * <p>Annotation processors (Lombok, MapStruct, …) are auto-discovered via the classpath's
 * {@code META-INF/services/javax.annotation.processing.Processor} entries — no explicit
 * registration needed.
 *
 * <p>Diagnostic output uses native {@code javac} format. The downstream {@link BlockerExtractor}
 * and {@link MissingMemberExtractor} already accept both Maven and javac shapes
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

    /**
     * T4-BN-2: also compile {@code src/test/java} each probe. Without this the per-cycle probe is
     * blind to test-source breaks (the main sources compile clean, so the cycle reports COMPILE_OK)
     * and a {@code cannot find symbol} in a test only surfaces at the completion edge's package gate
     * (observed 2026-06-17: a test break drove an honest root GIVEN_UP after the evolver had no
     * cycles left to fix it). Compiling tests in-cycle lets the diagnostic steer evolution while
     * there is still budget. Default on; flip to false to restore main-only probing.
     */
    @org.springframework.beans.factory.annotation.Value("${autobuilder.build.include-test-compile:true}")
    @lombok.Setter
    private boolean includeTestCompile = true;

    /** Classpath resolution lives in {@link ProjectClasspathService} (one home — discovery
     *  consumes the same project-resolved classpath, T4-BM). */
    private final ProjectClasspathService classpathService;

    @org.springframework.beans.factory.annotation.Autowired
    public JavacDirectProbe(ProjectClasspathService classpathService) {
        this.classpathService = classpathService;
    }

    /** Convenience constructor for tests and non-Spring callers. */
    public JavacDirectProbe() {
        this(new ProjectClasspathService());
    }

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
        String classpath = classpathService.resolveClasspath(projectRoot, timeoutMs);

        // 2. Discover sources: src/main/java, plus src/test/java when test-compile coverage is on
        //    (T4-BN-2). Compiling both in one pass mirrors `mvn test-compile` — cross-references
        //    resolve naturally and a broken test fails the probe like a broken main source.
        List<File> sources = new ArrayList<>(discoverSources(projectRoot.resolve("src/main/java")));
        if (includeTestCompile) {
            sources.addAll(discoverSources(projectRoot.resolve("src/test/java")));
        }
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
     * them and including them would dilute the {@code stderrTail} window). Bounded by the same
     * {@link BuildOutputWindow} as the external build, so javac's first diagnostic — the cause —
     * is kept here too (T1-GE).
     */
    private static String formatDiagnostics(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        BuildOutputWindow window = new BuildOutputWindow();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
            if (d.getKind() != Diagnostic.Kind.ERROR) continue;
            String location = d.getSource() != null ? d.getSource().toUri().getPath() : "(unknown)";
            long line = d.getLineNumber();
            String message = d.getMessage(Locale.ROOT);
            String formatted = location + ":" + (line > 0 ? line : 0) + ": error: " + message;
            for (String l : formatted.split("\\r?\\n")) {
                window.add(l);
            }
        }
        return window.render();
    }

    /** Test-only — clear the classpath cache so a fresh resolve happens on next probe. */
    void __resetCacheForTest() {
        classpathService.__resetCacheForTest();
    }
}
