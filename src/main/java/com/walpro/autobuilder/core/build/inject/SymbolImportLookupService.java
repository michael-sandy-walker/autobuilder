package com.walpro.autobuilder.core.build.inject;

import java.util.Optional;

/**
 * T4-AZ: resolves a Java simple-name to its fully-qualified name and (when known) the Maven
 * artifact that provides it. The autobuilder ships no enumerated table of framework symbols —
 * implementations populate themselves from external sources (LLM lookup, cache, etc.).
 *
 * <p>The contract is intentionally narrow:
 * <ul>
 *   <li>{@link #lookup(String)} returns a resolution if the symbol is recognised as a standard
 *       library or well-known third-party type, otherwise {@link Optional#empty()}. The caller
 *       cannot distinguish "not asked yet" from "asked, answered NONE" — that distinction is an
 *       implementation detail of caching.</li>
 *   <li>{@link #markResolutionFailed(String)} is the hallucination safety net: when a downstream
 *       compile probe proves a previous resolution wrong (e.g. the GAV could not be downloaded),
 *       the caller invalidates the cached hit so the resolver does not keep emitting the same
 *       broken fix.</li>
 * </ul>
 */
public interface SymbolImportLookupService {

    /**
     * @param simpleName Java simple identifier (no dots). May be {@code null} or blank — the
     *                   service treats those as {@link Optional#empty()}.
     */
    Optional<Resolution> lookup(String simpleName);

    /**
     * Invalidate a prior positive resolution. Subsequent calls to {@link #lookup(String)} for the
     * same simple name will not return the failed entry. Idempotent and safe on names never seen.
     */
    void markResolutionFailed(String simpleName);

    /**
     * Fully-qualified name plus the Maven coordinates of the artifact that provides it.
     * {@code groupId} and {@code artifactId} are nullable independently — a resolution may name
     * a JDK type (no GAV) or a third-party type whose coordinates we don't track.
     */
    record Resolution(String fqn, String groupId, String artifactId, String version) {
        public Resolution {
            if (fqn == null || fqn.isBlank()) {
                throw new IllegalArgumentException("fqn required");
            }
        }

        public boolean hasGav() {
            return groupId != null && !groupId.isBlank()
                    && artifactId != null && !artifactId.isBlank();
        }
    }
}
