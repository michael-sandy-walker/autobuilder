package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * T4-AN-8: runnable-artifact gate. The per-cycle probes are compile-only, and an application
 * entry point is not needed to compile — so an artifact with every endpoint present at source
 * level but no startable application class sails through every gate (observed 2026-06-12: run-2
 * terminal artifact had no bootstrap class and no {@code main}; recurring across the backup
 * corpus). The build tool's own packaging step is the deterministic, framework-neutral check:
 * for executable-application projects it fails when no entry point exists, and the project's
 * build config decides what "packageable" means.
 *
 * <p>Runs ONCE per completion claim — only at the root-closure edge, where "this is a finished
 * application" is asserted. Never per-cycle (cost).
 *
 * <p>Best-effort by contract: disabled gate, disabled build executor, or a SKIPPED/TIMEOUT
 * outcome all return empty (no blocker) — packaging observation is a closure-hardening signal,
 * and an environment hiccup must not wedge an otherwise-clean completion.
 */
@Slf4j
@Component
public class PackageGate {

    private final boolean enabled;
    private final String packageCommand;
    private final BuildExecutor buildExecutor;

    /**
     * Comma-separated build-config substrings that state application-packaging intent — ecosystem
     * data rows (T4-BM discovery territory), not framework vocabulary. Empty disables the
     * launchable-archive check.
     */
    private final String applicationPackagingMarkers;

    @Value("${file.base-path}")
    @Setter
    private String fileBasePath;

    /** T5-N: optional behaviour trace — emits {@code build_fix_attempt} with source {@code package-gate}. */
    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    @Autowired
    public PackageGate(
            @Value("${autobuilder.build.package-gate.enabled:true}") boolean enabled,
            @Value("${autobuilder.build.package-gate.command:mvn package -DskipTests}") String packageCommand,
            @Value("${autobuilder.build.package-gate.application-packaging-markers:spring-boot-maven-plugin}")
            String applicationPackagingMarkers,
            BuildExecutor buildExecutor) {
        this.enabled = enabled;
        this.packageCommand = packageCommand;
        this.applicationPackagingMarkers = applicationPackagingMarkers;
        this.buildExecutor = buildExecutor;
    }

    /** Marker-less constructor kept for existing tests; check disabled unless markers provided. */
    public PackageGate(boolean enabled, String packageCommand, BuildExecutor buildExecutor) {
        this(enabled, packageCommand, "", buildExecutor);
    }

    /**
     * Runs the package-level build once. Returns a short failure summary when the artifact
     * is not packageable (→ caller refuses completion and injects the summary as a work item),
     * or empty when the artifact packages clean — or when no answer could be obtained
     * (gate/executor disabled, timeout): no observation is never treated as failure.
     */
    public Optional<String> verifyPackageable(String cycleId) {
        if (!enabled || !buildExecutor.isEnabled()) {
            return Optional.empty();
        }
        BuildOutcome outcome = buildExecutor.execute(fileBasePath, cycleId, packageCommand);
        log.info("T4-AN-8: package gate result={} (durationMs={})", outcome.result(), outcome.durationMs());
        if (behaviourLogger != null) {
            String errorKind = null;
            if (outcome.result() == BuildResult.COMPILE_ERROR) {
                BuildErrorClassifier.Classification c = BuildErrorClassifier.classify(outcome.stderrTail());
                errorKind = c != null && c.kind() != null ? c.kind().name() : null;
            }
            behaviourLogger.buildFixAttempt(cycleId, "package-gate", 0, null, errorKind,
                    null, List.of(), outcome.result().name(), outcome.durationMs());
        }
        if (outcome.result() != BuildResult.COMPILE_ERROR) {
            // A successful packaging can still be unstartable: on 2026-07-02 the loop "resolved"
            // an `Unable to find main class` failure by PINNING a nonexistent class in the build
            // config — with an explicit entry-point declaration the packaging step stops
            // searching and writes the manifest unverified, and the produced jar dies at launch
            // with ClassNotFoundException (false completion #2). Verify the declaration against
            // the build output before accepting the packaging as proof of a runnable artifact.
            if (outcome.result() != BuildResult.COMPILE_OK) {
                return Optional.empty();
            }
            Optional<String> declaredEntryPointFailure = verifyDeclaredEntryPointExists();
            if (declaredEntryPointFailure.isPresent()) {
                return declaredEntryPointFailure;
            }
            Optional<String> archiveFailure = verifyArchiveLaunchable();
            if (archiveFailure.isPresent()) {
                return archiveFailure;
            }
            return verifyInjectionWiring();
        }
        return Optional.of(summarise(outcome.stderrTail()));
    }

    /** Entry-point declaration in the build config (build-tool generalisation is T2-CW's scope). */
    private static final java.util.regex.Pattern DECLARED_ENTRY_POINT =
            java.util.regex.Pattern.compile("<mainClass>\\s*([\\w.$]+)\\s*</mainClass>");

    /**
     * When the build config itself declares an entry-point class, that class must exist in the
     * compiled build output — the declaration silences the packaging step's own search, so it is
     * the gate's job to check the declaration is satisfied. No declaration = empty (the packaging
     * step's own search already verified an entry point exists, or packaging would have failed).
     * Best-effort like the rest of the gate: unreadable config = no answer, never a block.
     */
    private Optional<String> verifyDeclaredEntryPointExists() {
        try {
            java.nio.file.Path buildDir = java.nio.file.Path.of(buildExecutor.resolveWorkingDir(fileBasePath));
            java.nio.file.Path buildConfig = buildDir.resolve("pom.xml");
            if (!java.nio.file.Files.isRegularFile(buildConfig)) return Optional.empty();
            var matcher = DECLARED_ENTRY_POINT.matcher(java.nio.file.Files.readString(buildConfig));
            if (!matcher.find()) return Optional.empty();
            String declared = matcher.group(1);
            java.nio.file.Path classFile = buildDir.resolve("target/classes/"
                    + declared.replace('.', '/') + ".class");
            if (java.nio.file.Files.isRegularFile(classFile)) return Optional.empty();
            String failure = "packaging succeeded but the build config declares entry point '" + declared
                    + "' and no such class exists in the build output — create the application entry-point class"
                    + " the build config names (or correct the declaration)";
            log.warn("T4-AN-8: {}", failure);
            return Optional.of(failure);
        } catch (Exception e) {
            log.debug("T4-AN-8: declared-entry-point check skipped ({})", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Evasion #3 (2026-07-03 field run 2): the packaging step can succeed while producing an
     * UNLAUNCHABLE plain archive — the build config declared an application-packaging plugin
     * WITHOUT binding its execution (and no parent inheritance bound it), so the packaging goal
     * never ran: exit 0, no entry-point manifest attribute, {@code java -jar} dies with "no main
     * manifest attribute". That defeats {@link #verifyDeclaredEntryPointExists}'s assumption
     * ("no declaration = the packaging step's own search already ran" — no search happened,
     * because the step itself never executed). Deterministic and execution-free: read the
     * produced archive's manifest and require an entry-point attribute.
     *
     * <p>Self-gated the ecosystem-marker way ([[feedback-ecosystem-gated-heuristics]]): the check
     * only applies when the target's OWN build config declares an application-packaging marker —
     * a library project (no marker) produces a plain archive legitimately and gets no opinion.
     * The marker list is config data, not framework vocabulary (T4-BM discovery territory).
     * Best-effort like the rest of the gate: unreadable config/archive = no answer, never a block.
     */
    private Optional<String> verifyArchiveLaunchable() {
        if (applicationPackagingMarkers == null || applicationPackagingMarkers.isBlank()) {
            return Optional.empty();
        }
        try {
            java.nio.file.Path buildDir = java.nio.file.Path.of(buildExecutor.resolveWorkingDir(fileBasePath));
            java.nio.file.Path buildConfig = buildDir.resolve("pom.xml");
            if (!java.nio.file.Files.isRegularFile(buildConfig)) return Optional.empty();
            String configText = java.nio.file.Files.readString(buildConfig);
            boolean applicationIntent = java.util.Arrays.stream(applicationPackagingMarkers.split(","))
                    .map(String::trim)
                    .filter(m -> !m.isEmpty())
                    .anyMatch(configText::contains);
            if (!applicationIntent) return Optional.empty();

            Optional<java.nio.file.Path> archive = newestArchive(buildDir.resolve("target"));
            if (archive.isEmpty()) return Optional.empty();
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(archive.get().toFile())) {
                java.util.jar.Manifest manifest = jar.getManifest();
                String mainClass = manifest == null ? null
                        : manifest.getMainAttributes().getValue("Main-Class");
                if (mainClass != null && !mainClass.isBlank()) return Optional.empty();
            }
            String failure = "packaging succeeded but the produced archive " + archive.get().getFileName()
                    + " carries no entry-point manifest attribute and cannot be launched — the build config"
                    + " declares an application-packaging plugin but its packaging execution never ran;"
                    + " bind the plugin's execution in the build config (or inherit a parent that binds it)"
                    + " so packaging produces a launchable application archive";
            log.warn("T4-AN-8: {}", failure);
            return Optional.of(failure);
        } catch (Exception e) {
            log.debug("T4-AN-8: launchable-archive check skipped ({})", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Evasion #4 — false completion #3 (field run 13, 2026-07-04): packaging and both compile
     * gates passed while the artifact could not START — the last merged mutation full-rewrote a
     * service class dropping its container marker annotation while a controller still injected
     * it, so context refresh fails before a single request is served. Compilation never needs
     * the marker; the container's resolution rules do, and those are statically provable:
     * {@link com.walpro.autobuilder.analysis.structure.InjectionWiringQuery} checks every
     * injection point whose dependency type is project-declared against the bean sources.
     * Execution-free by design (this is NOT a boot gate). Self-gated: marker-free projects get
     * no opinion. Best-effort like the rest of the gate: unreadable sources = no answer,
     * never a block.
     */
    private Optional<String> verifyInjectionWiring() {
        try {
            java.nio.file.Path sourceRoot = java.nio.file.Path.of(buildExecutor.resolveWorkingDir(fileBasePath))
                    .resolve("src/main/java");
            if (!java.nio.file.Files.isDirectory(sourceRoot)) return Optional.empty();
            java.util.Map<String, String> sources = new java.util.LinkedHashMap<>();
            try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(sourceRoot)) {
                for (java.nio.file.Path p : walk.filter(f -> f.toString().endsWith(".java"))
                        .filter(java.nio.file.Files::isRegularFile).toList()) {
                    sources.put(sourceRoot.relativize(p).toString(), java.nio.file.Files.readString(p));
                }
            }
            var unresolved = com.walpro.autobuilder.analysis.structure.InjectionWiringQuery
                    .unresolvedInjections(sources);
            if (unresolved.isEmpty()) return Optional.empty();
            String details = unresolved.stream()
                    .limit(3)
                    .map(com.walpro.autobuilder.analysis.structure.InjectionWiringQuery.UnresolvedInjection::describe)
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("");
            String failure = "packaging succeeded but the artifact cannot start: " + details
                    + " — give the injected type a container marker annotation (or declare a factory"
                    + " method providing it) so the dependency resolves at startup";
            log.warn("T4-BW: {}", failure);
            return Optional.of(failure);
        } catch (Exception e) {
            log.debug("T4-BW: injection-wiring check skipped ({})", e.getMessage());
            return Optional.empty();
        }
    }

    /** Newest top-level archive in the build output, ignoring the classifier archives. */
    private static Optional<java.nio.file.Path> newestArchive(java.nio.file.Path target) throws java.io.IOException {
        if (!java.nio.file.Files.isDirectory(target)) return Optional.empty();
        try (java.util.stream.Stream<java.nio.file.Path> entries = java.nio.file.Files.list(target)) {
            return entries
                    .filter(java.nio.file.Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        return name.endsWith(".jar")
                                && !name.endsWith("-sources.jar")
                                && !name.endsWith("-javadoc.jar")
                                && !name.endsWith(".jar.original");
                    })
                    .max(java.util.Comparator.comparingLong(p -> p.toFile().lastModified()));
        }
    }

    /** First diagnostic-bearing lines of the packaging output, capped — feeds a subgoal description.
     *  ANSI colour codes are stripped: the summary lands verbatim in a goal description, where
     *  escape sequences are prompt noise and degrade description-similarity matching. */
    private static String summarise(String stderrTail) {
        if (stderrTail == null || stderrTail.isBlank()) return "packaging step failed (no diagnostic output)";
        stderrTail = BuildErrorClassifier.ANSI_ESCAPE.matcher(stderrTail).replaceAll("");
        return stderrTail.lines()
                .map(String::strip)
                .filter(l -> l.contains("ERROR") || l.toLowerCase(java.util.Locale.ROOT).contains("fail"))
                .filter(l -> !l.replaceAll("\\[ERROR\\]", "").isBlank())
                .limit(3)
                .reduce((a, b) -> a + " | " + b)
                .orElse("packaging step failed (exit != 0)");
    }
}
