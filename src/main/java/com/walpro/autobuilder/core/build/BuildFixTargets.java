package com.walpro.autobuilder.core.build;

import java.util.List;

/**
 * T4-AU (initial slice): error-class-aware repair-target selection. Given a
 * {@link BuildErrorClassifier.Kind} and a list of candidate paths from the rollback /
 * merged set, returns the path the BUILD_FIX subgoal should name as the repair target.
 *
 * <p>Today this answers exactly one question — MISSING_POM means {@code pom.xml} regardless
 * of what the candidates contain — but the shape is deliberately the one T4-AU's full
 * {@code FileRoleClassifier} grows into:</p>
 *
 * <ol>
 *   <li>Each new error kind that benefits from a class-aware target gets its own case here.</li>
 *   <li>When a yaml-backed registry of build-tool descriptors lands (T2-CW), the
 *       hardcoded {@code "pom.xml"} resolves to a registry lookup — same call sites,
 *       new implementation.</li>
 *   <li>When file roles (BUILD_DESCRIPTOR / SOURCE / TEST / GENERATED_OUTPUT) become
 *       useful to other callers, this class promotes to a {@code FileRoleClassifier}
 *       whose API is a superset of the present method.</li>
 * </ol>
 *
 * <p>The intentional decision today: do NOT add ecosystem-specific filename lists at the
 * caller layer. Callers ask "what should I tell the LLM to fix?" and trust the answer.</p>
 */
public final class BuildFixTargets {

    private BuildFixTargets() {}

    /**
     * Returns the preferred repair-target path for {@code kind} given the available
     * {@code candidates}, or {@code null} when no class-aware preference applies (caller
     * falls back to its own default — stderr file-coord prefix or first non-blank candidate).
     *
     * <p>For {@link BuildErrorClassifier.Kind#MISSING_POM}: prefer a candidate whose basename
     * is {@code pom.xml}; otherwise return the literal {@code "pom.xml"} so the BUILD_FIX
     * description still names the right artefact even when the LLM never proposed one.</p>
     */
    public static String preferredRepairTarget(BuildErrorClassifier.Kind kind, List<String> candidates) {
        if (kind == null) return null;
        return switch (kind) {
            case MISSING_POM -> findCandidateByBasename(candidates, "pom.xml")
                    .orElse("pom.xml");
            default -> null;
        };
    }

    private static java.util.Optional<String> findCandidateByBasename(List<String> candidates, String basename) {
        if (candidates == null) return java.util.Optional.empty();
        for (String p : candidates) {
            if (p == null || p.isBlank()) continue;
            String fname = p.contains("/") ? p.substring(p.lastIndexOf('/') + 1) : p;
            if (fname.equals(basename)) return java.util.Optional.of(p);
        }
        return java.util.Optional.empty();
    }
}
