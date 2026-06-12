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
            return Optional.empty();
        }
        return Optional.of(summarise(outcome.stderrTail()));
    }

    /** First diagnostic-bearing lines of the packaging output, capped — feeds a subgoal description. */
    private static String summarise(String stderrTail) {
        if (stderrTail == null || stderrTail.isBlank()) return "packaging step failed (no diagnostic output)";
        return stderrTail.lines()
                .map(String::strip)
                .filter(l -> l.contains("ERROR") || l.toLowerCase(java.util.Locale.ROOT).contains("fail"))
                .filter(l -> !l.replaceAll("\\[ERROR\\]", "").isBlank())
                .limit(3)
                .reduce((a, b) -> a + " | " + b)
                .orElse("packaging step failed (exit != 0)");
    }
}
