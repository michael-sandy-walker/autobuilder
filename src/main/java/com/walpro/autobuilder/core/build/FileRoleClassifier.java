package com.walpro.autobuilder.core.build;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * T4-AU: single source of truth for file-role questions ("is this a build descriptor?", "is this
 * a test source?", "is this generated output?"). All answers derive from the build-tool registry
 * ({@link BuildToolProbeProperties} — yaml data), never from literals here; adding a build system
 * is a yaml-only change. Subsumes the per-caller {@code BUILD_CONFIG_FILES} lists previously in
 * {@code BuildFixAcceptanceHeuristic}, {@code FileVariantCollector} and {@code MergeAgent}, and
 * promotes {@code BuildFixTargets} (deleted) into the role-aware API it documented as its own
 * successor.
 *
 * <p>Pure string classification — no filesystem access, so callers can ask about paths that do
 * not exist yet (a repair target the LLM never proposed, a rolled-back file).
 */
@Component
@RequiredArgsConstructor
public class FileRoleClassifier {

    private final BuildToolProbeProperties props;

    /** Roles of {@code path} per the registry; {@link FileRole#UNKNOWN} when nothing matches. */
    public Set<FileRole> rolesOf(String path) {
        if (path == null || path.isBlank()) return Set.of(FileRole.UNKNOWN);
        String norm = normalise(path);
        String base = basename(norm);

        Set<FileRole> roles = EnumSet.noneOf(FileRole.class);
        if (props.getToolDetection().containsKey(base)) {
            roles.add(FileRole.BUILD_DESCRIPTOR);
        }
        if (underOutputDirectory(norm)) {
            roles.add(FileRole.GENERATED_OUTPUT);
        }
        if (hasSourceExtension(base)) {
            roles.add(FileRole.SOURCE);
            if (underAnyMarker(norm, allMarkers(props.getTestDirectories()))) {
                roles.add(FileRole.TEST);
            }
        }
        if (underAnyMarker(norm, allMarkers(props.getResourceDirectories()))) {
            roles.add(FileRole.RESOURCE);
        }
        if (roles.isEmpty()) roles.add(FileRole.UNKNOWN);
        return roles;
    }

    /**
     * The repair target the error class <em>structurally requires</em>, or {@code null} when the
     * class carries no such requirement (caller keeps its own resolution — typically the file the
     * compiler itself named, which is more precise than any role-based guess).
     *
     * <p>For {@link BuildErrorClassifier.Kind#MISSING_POM}: prefer a candidate carrying
     * {@link FileRole#BUILD_DESCRIPTOR}; otherwise name the registry's primary descriptor so the
     * BUILD_FIX description still points at the right artefact even when the LLM never proposed
     * one (yaml insertion order makes the first {@code tool-detection} entry the primary).
     */
    public String preferredRepairTarget(BuildErrorClassifier.Kind kind, List<String> candidates) {
        if (kind == null) return null;
        return switch (kind) {
            case MISSING_POM -> firstCandidateWithRole(candidates, FileRole.BUILD_DESCRIPTOR, null)
                    .orElse(primaryDescriptor());
            default -> null;
        };
    }

    /**
     * Role-aware replacement for "first non-blank candidate" fallbacks (consulted only after
     * more precise resolutions — e.g. the compiler's own file coordinate — found nothing).
     * Source-shaped error kinds prefer a non-test {@link FileRole#SOURCE} candidate, then any
     * source candidate, then the first non-blank one; other kinds go straight to first non-blank.
     */
    public String preferredFallbackTarget(BuildErrorClassifier.Kind kind, List<String> candidates) {
        if (kind == BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL
                || kind == BuildErrorClassifier.Kind.PACKAGE_DOES_NOT_EXIST) {
            return firstCandidateWithRole(candidates, FileRole.SOURCE, FileRole.TEST)
                    .or(() -> firstCandidateWithRole(candidates, FileRole.SOURCE, null))
                    .orElseGet(() -> firstNonBlank(candidates));
        }
        return firstNonBlank(candidates);
    }

    // -------------------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------------------

    private java.util.Optional<String> firstCandidateWithRole(List<String> candidates,
                                                              FileRole required, FileRole excluded) {
        if (candidates == null) return java.util.Optional.empty();
        for (String c : candidates) {
            if (c == null || c.isBlank()) continue;
            Set<FileRole> roles = rolesOf(c);
            if (roles.contains(required) && (excluded == null || !roles.contains(excluded))) {
                return java.util.Optional.of(c);
            }
        }
        return java.util.Optional.empty();
    }

    private static String firstNonBlank(List<String> candidates) {
        if (candidates == null) return null;
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c;
        }
        return null;
    }

    /** First registered detection filename — yaml insertion order names the primary ecosystem. */
    private String primaryDescriptor() {
        return props.getToolDetection().keySet().stream().findFirst().orElse(null);
    }

    private boolean underOutputDirectory(String norm) {
        // Root-segment match only: generated output sits at the top of a build module, and a
        // deeper match would misclassify package directories that happen to share the name.
        String first = norm.contains("/") ? norm.substring(0, norm.indexOf('/')) : norm;
        return allMarkers(props.getOutputDirectories()).contains(first);
    }

    private boolean hasSourceExtension(String basename) {
        String lower = basename.toLowerCase(Locale.ROOT);
        return props.getSourceExtensions().stream().anyMatch(lower::endsWith);
    }

    private static boolean underAnyMarker(String norm, Set<String> markers) {
        for (String marker : markers) {
            if (marker == null || marker.isBlank()) continue;
            String m = normalise(marker);
            if (norm.startsWith(m + "/") || norm.contains("/" + m + "/")) return true;
        }
        return false;
    }

    private static Set<String> allMarkers(java.util.Map<String, List<String>> perTool) {
        Set<String> markers = new java.util.LinkedHashSet<>();
        for (List<String> v : perTool.values()) {
            for (String s : v) {
                if (s != null && !s.isBlank()) markers.add(normalise(s));
            }
        }
        return markers;
    }

    private static String normalise(String path) {
        String norm = path.replace('\\', '/').trim();
        while (norm.startsWith("/")) norm = norm.substring(1);
        while (norm.endsWith("/")) norm = norm.substring(0, norm.length() - 1);
        return norm;
    }

    private static String basename(String norm) {
        return norm.contains("/") ? norm.substring(norm.lastIndexOf('/') + 1) : norm;
    }
}
