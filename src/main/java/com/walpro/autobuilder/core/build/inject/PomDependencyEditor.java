package com.walpro.autobuilder.core.build.inject;

import java.util.regex.Pattern;

/**
 * T4-AZ: shared utility for splicing {@code <dependency>} blocks into a {@code pom.xml} string.
 * Shared by every resolver that needs to splice a {@code <dependency>} block — the same edit
 * shouldn't duplicate XML knowledge in each resolver.
 *
 * <p>The presence check matches groupId + artifactId inside the same {@code <dependency>} block
 * (in either order). A duplicate {@code <dependency>} block would be tolerated by Maven, but a
 * false positive (skipping a needed addition because an unrelated artifact happens to share the
 * same artifactId) silently breaks the build — strictly worse — so the check is intentionally
 * conservative.
 */
public final class PomDependencyEditor {

    /**
     * Matches a single {@code <dependency>...</dependency>} block. DOTALL so the body can span
     * multiple lines (the standard format). Used to scope the groupId/artifactId conjunction.
     */
    private static final Pattern DEPENDENCY_BLOCK = Pattern.compile(
            "<dependency>(.*?)</dependency>", Pattern.DOTALL);

    private PomDependencyEditor() {}

    /**
     * Returns true if {@code pom} already declares a dependency with the given {@code groupId}
     * AND {@code artifactId} inside the same {@code <dependency>} block. Order of the two
     * elements within the block does not matter.
     *
     * <p>Pure-textual scan over each block; tolerant of whitespace around tags but not of
     * comments or attributes inside the {@code <groupId>}/{@code <artifactId>} elements (rare in
     * pom files). For our usage that is precise enough — see class doc for the tradeoff.
     */
    public static boolean containsDependency(String pom, String groupId, String artifactId) {
        if (pom == null) return false;
        if (groupId == null || groupId.isBlank()) return false;
        if (artifactId == null || artifactId.isBlank()) return false;
        String groupTag = "<groupId>" + groupId + "</groupId>";
        String artifactTag = "<artifactId>" + artifactId + "</artifactId>";
        var matcher = DEPENDENCY_BLOCK.matcher(pom);
        while (matcher.find()) {
            String block = matcher.group(1);
            if (block.contains(groupTag) && block.contains(artifactTag)) return true;
        }
        return false;
    }

    private static final Pattern GROUP_ID = Pattern.compile("<groupId>\\s*([^<\\s]+)\\s*</groupId>");
    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");
    private static final Pattern VERSION = Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>");

    /**
     * T4-BM step 6: a stable fingerprint of the pom's declared dependency set — sorted
     * {@code g:a:v} triples, one line each. Changes exactly when a dependency is added, removed,
     * or re-versioned; unrelated pom edits (properties, plugins, whitespace) leave it unchanged.
     * That distinction matters: raw-digest keying re-fires on every pom touch (the T2-DY
     * blacklist-recleared-each-cycle lesson), while THIS key only moves when the resolution
     * context actually did. Empty string for a null/dependency-less pom.
     */
    public static String dependencyFingerprint(String pom) {
        if (pom == null || pom.isBlank()) return "";
        java.util.List<String> coords = new java.util.ArrayList<>();
        var matcher = DEPENDENCY_BLOCK.matcher(pom);
        while (matcher.find()) {
            String block = matcher.group(1);
            var g = GROUP_ID.matcher(block);
            var a = ARTIFACT_ID.matcher(block);
            var v = VERSION.matcher(block);
            if (!g.find() || !a.find()) continue;
            coords.add(g.group(1) + ":" + a.group(1) + ":" + (v.find() ? v.group(1) : ""));
        }
        java.util.Collections.sort(coords);
        return String.join("\n", coords);
    }

    /**
     * Splices a new {@code <dependency>} block just before the closing {@code </dependencies>}
     * tag. Returns {@code null} if no closing tag is present (caller should treat as a no-op).
     * Version may be {@code null} or blank to omit the {@code <version>} element.
     */
    public static String appendDependency(String pom, String groupId, String artifactId, String version) {
        if (pom == null) return null;
        int closeIdx = pom.lastIndexOf("</dependencies>");
        if (closeIdx < 0) return null;
        StringBuilder dep = new StringBuilder();
        dep.append("        <dependency>\n");
        dep.append("            <groupId>").append(groupId).append("</groupId>\n");
        dep.append("            <artifactId>").append(artifactId).append("</artifactId>\n");
        if (version != null && !version.isBlank()) {
            dep.append("            <version>").append(version).append("</version>\n");
        }
        dep.append("        </dependency>\n");
        return pom.substring(0, closeIdx) + dep + pom.substring(closeIdx);
    }
}
