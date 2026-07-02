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
 * outcome all return empty (no blocker) — packaging evidence is a closure-hardening signal,
 * and an environment hiccup must not wedge an otherwise-clean completion.
 */
@Slf4j
@Component
public class PackageGate {

    private final boolean enabled;
    private final String packageCommand;
    private final BuildExecutor buildExecutor;

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
            BuildExecutor buildExecutor) {
        this.enabled = enabled;
        this.packageCommand = packageCommand;
        this.buildExecutor = buildExecutor;
    }

    /**
     * Runs the package-level build once. Returns a short failure summary when the artifact
     * is not packageable (→ caller refuses completion and injects the summary as a work item),
     * or empty when the artifact packages clean — or when no verdict could be obtained
     * (gate/executor disabled, timeout): no evidence is never treated as failure.
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
            // the build output before accepting the packaging as evidence of a runnable artifact.
            return outcome.result() == BuildResult.COMPILE_OK
                    ? verifyDeclaredEntryPointExists()
                    : Optional.empty();
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
     * Best-effort like the rest of the gate: unreadable config = no verdict, never a block.
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
