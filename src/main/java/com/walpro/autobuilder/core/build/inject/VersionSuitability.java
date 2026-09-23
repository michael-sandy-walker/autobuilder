package com.walpro.autobuilder.core.build.inject;

import java.util.List;
import java.util.Optional;

/**
 * T1-GF: the one rule for which published version of an artifact to write into a manifest,
 * whoever supplied the list — the remote index or the local artifact repository.
 *
 * <ol>
 *   <li><b>Aligned</b> — a version the manifest already declares for the same group, when it is
 *       among the candidates. Artifacts of one group are released together; mixing the newest of
 *       one with an older sibling is how a manifest that "resolves" still fails at link time.</li>
 *   <li><b>Newest plain release</b> — the newest version whose string is dotted digits only. A
 *       qualified version (milestone, release candidate) written into a manifest propagates into
 *       every later resolution (field 2026-07-09..14: a {@code -M1} milestone did exactly that).</li>
 *   <li><b>Newest</b> — when nothing plain is published.</li>
 * </ol>
 *
 * <p>Reads only a version string's SHAPE, never an ordering: the candidates arrive newest first,
 * ordered by a fact the source states (publish time, or a jar's own build time).
 */
public final class VersionSuitability {

    private VersionSuitability() {}

    public static Optional<String> choose(List<String> newestFirst, String aligned) {
        if (newestFirst == null || newestFirst.isEmpty()) return Optional.empty();
        if (aligned != null && !aligned.isBlank() && newestFirst.contains(aligned)) return Optional.of(aligned);
        for (String v : newestFirst) {
            if (v != null && v.matches("\\d+(\\.\\d+)*")) return Optional.of(v);
        }
        return Optional.ofNullable(newestFirst.get(0));
    }
}
