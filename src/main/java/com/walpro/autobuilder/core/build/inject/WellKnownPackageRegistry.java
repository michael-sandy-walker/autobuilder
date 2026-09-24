package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;

import com.walpro.autobuilder.core.service.FileService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-BG: deterministic registry mapping well-known external Java package prefixes to the build
 * coordinates that provide them. Consulted by {@link SymbolImportResolver} before the LLM-backed
 * {@link LlmSymbolImportLookupService}.
 *
 * <p><b>T4-BM: FALLBACK ONLY.</b> {@link PackageDiscoveryService} (the project's own classpath,
 * imports, and the coordinate search) is consulted FIRST; these tables answer only when
 * discovery abstains — the artifact is absent from the classpath and no project source imports
 * it. The rows freeze a worldview the loop cannot correct (version drift, incomplete coverage,
 * authoritative-but-wrong — see the T4-BM ticket), so: file a discovery improvement instead of
 * adding rows here. Remaining rows shrink as discovery coverage is field-proven.
 *
 * <p>Subsumes the hardcoded {@code KNOWN} table previously in {@code KnownStarterResolver}: every
 * package handled by that resolver lives here now and the resolver is gone (per
 * {@code feedback_capability_ticket_includes_delete} — capability tickets delete the substrate
 * they replace in the same scope).
 *
 * <p>Legacy-generation detection: some table rows carry a legacy variant whose package prefix and
 * coordinates differ by platform generation. {@link #legacyGenerationDeclared(String)} consults
 * the project's build manifest for the registered generation marker and picks the legacy variant
 * when the declared generation predates the prefix migration. The marker and threshold are data
 * rows, not framework knowledge.
 *
 * <p>Hallucination safety: {@link #markResolutionFailed(String)} adds a per-symbol bypass so the
 * registry isn't consulted again for that name; the resolver then falls through to the LLM path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "a negative memo over static tables — re-deriving it reaches the same answer")
public class WellKnownPackageRegistry {

    /** Build coordinates that provide a registry-covered package. */
    public record Gav(String groupId, String artifactId, String version) {
        public boolean isBlank() {
            return groupId == null || groupId.isBlank() || artifactId == null || artifactId.isBlank();
        }
    }

    /**
     * T4-BI: a registry-known package replacement — a legacy prefix with a deterministic modern
     * successor. {@code oldPrefix} is the broadest registry key that covers the caller's missing
     * package; rewrite logic substitutes the leading {@code oldPrefix} with {@code newPrefix} in
     * every affected source import.
     */
    public record PackageReplacement(String oldPrefix, String newPrefix) {}

    /** Per-prefix entry with an optional legacy-generation alternative (modern vs legacy prefix split). */
    private record Entry(Gav modern, Gav legacy) {
        Entry(Gav modern) { this(modern, null); }
    }

    // ---------------------------------------------------------------------------------------
    // Data rows. Ecosystem specifics live HERE and only here; the methods below must not name
    // any of them ([[feedback-no-ecosystem-jargon-in-framework-code]]).
    // ---------------------------------------------------------------------------------------

    /** Manifest marker whose declared major version identifies the platform generation. */
    private static final String GENERATION_MARKER_ARTIFACT = "spring-boot-starter-parent";

    /** First platform generation that uses the modern package prefixes. */
    private static final int FIRST_MODERN_GENERATION = 3;

    private static final Pattern PARENT_GENERATION_VERSION = Pattern.compile(
            "<parent>\\s*(?:<[^>]+>[^<]*</[^>]+>\\s*)*?<artifactId>\\s*" + GENERATION_MARKER_ARTIFACT
                    + "\\s*</artifactId>\\s*(?:<[^>]+>[^<]*</[^>]+>\\s*)*?<version>\\s*(\\d+)(?:\\.(\\d+))?",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // Package prefix → entry. Order matters for longest-prefix matching, so we use an
    // insertion-ordered map and iterate.
    private static final Map<String, Entry> TABLE = buildTable();

    /**
     * T4-BI: legacy-package → modern-package rewrite map. Consumed by
     * {@link #replacementFor(String, String)} to drive {@link SymbolImportResolver}'s
     * import-rewrite mode when a {@code PACKAGE_DOES_NOT_EXIST} diagnostic names a package that
     * has a deterministic modern replacement (adding the new GAV alone isn't enough — the file
     * still imports from the dead prefix).
     *
     * <p>Longest-prefix matching: a missing sub-package resolves against its broadest registered
     * key and rewrites that broader prefix, so every import under the legacy prefix in the same
     * file gets migrated in one pass. Suppressed on legacy-generation projects, where the old
     * prefix is still correct.
     */
    private static final Map<String, String> PACKAGE_REPLACED_BY = Map.of(
            "javax.validation",  "jakarta.validation",
            "javax.persistence", "jakarta.persistence"
    );

    /**
     * Canonical FQN-prefix lookup for a handful of well-known *simple* names so the coverage
     * guard can recognise a common annotation as covered by its registered prefix without
     * needing to round-trip through the LLM lookup first. Intentionally tiny — only the most
     * common symbols from the recurring rejection cascades.
     */
    private static final Map<String, String> SIMPLE_NAME_TO_PREFIX = Map.ofEntries(
            Map.entry("NotNull",   "jakarta.validation.constraints"),
            Map.entry("NotBlank",  "jakarta.validation.constraints"),
            Map.entry("NotEmpty",  "jakarta.validation.constraints"),
            Map.entry("Size",      "jakarta.validation.constraints"),
            Map.entry("Pattern",   "jakarta.validation.constraints"),
            Map.entry("Email",     "jakarta.validation.constraints"),
            Map.entry("Min",       "jakarta.validation.constraints"),
            Map.entry("Max",       "jakarta.validation.constraints"),
            Map.entry("Valid",     "jakarta.validation"),
            Map.entry("Entity",    "jakarta.persistence"),
            Map.entry("Id",        "jakarta.persistence"),
            Map.entry("GeneratedValue", "jakarta.persistence"),
            Map.entry("Column",    "jakarta.persistence"),
            Map.entry("Table",     "jakarta.persistence")
    );

    private final FileService fileService;
    private final SymbolImportCache symbolImportCache;

    /** Symbols (package or simple name) for which a prior registry-emitted fix proved wrong;
     *  the registry skips these on subsequent lookups so the LLM path can take over. */
    private final Set<String> failedResolutions = ConcurrentHashMap.newKeySet();

    private static Map<String, Entry> buildTable() {
        Map<String, Entry> m = new LinkedHashMap<>();

        // Validation API cluster — modern generation → modern API jar; legacy generation → legacy API jar.
        Entry validation = new Entry(
                new Gav("jakarta.validation", "jakarta.validation-api", "3.0.2"),
                new Gav("javax.validation", "validation-api", "2.0.1.Final"));
        m.put("javax.validation",                 validation);
        m.put("javax.validation.constraints",     validation);
        m.put("jakarta.validation",               validation);
        m.put("jakarta.validation.constraints",   validation);
        m.put("org.hibernate.validator",          new Entry(
                new Gav("org.hibernate.validator", "hibernate-validator", "8.0.1.Final")));

        // Platform-managed entries — version comes from the platform BOM, so no explicit version.
        m.put("org.springframework.security",     new Entry(
                new Gav("org.springframework.boot", "spring-boot-starter-security", null)));
        m.put("org.springframework.data.jpa",     new Entry(
                new Gav("org.springframework.boot", "spring-boot-starter-data-jpa", null)));
        m.put("org.springframework.data.repository", new Entry(
                new Gav("org.springframework.boot", "spring-boot-starter-data-jpa", null)));
        m.put("org.springframework.web",          new Entry(
                new Gav("org.springframework.boot", "spring-boot-starter-web", null)));

        m.put("jakarta.persistence",              new Entry(
                new Gav("jakarta.persistence", "jakarta.persistence-api", "3.1.0")));
        m.put("javax.persistence",                new Entry(
                new Gav("org.springframework.boot", "spring-boot-starter-data-jpa", null)));
        m.put("org.junit.jupiter",                new Entry(
                new Gav("org.junit.jupiter", "junit-jupiter", "5.10.1")));
        return Map.copyOf(m);
    }

    /**
     * Boot-time cache eviction: any {@link SymbolImportCache} entry whose cached FQN falls under a
     * registry-covered prefix is removed so subsequent lookups go through the deterministic
     * registry path instead of an older (potentially wrong) LLM-cached resolution.
     */
    @PostConstruct
    void evictStaleCacheEntries() {
        if (symbolImportCache == null) return;
        // Snapshot covered simple names; cache iteration via the existing get/invalidate API.
        // The cache doesn't expose its keyset, so we evict the SIMPLE_NAME_TO_PREFIX entries
        // directly (the explicit set whose FQN we know would resolve under the registry).
        int evicted = 0;
        for (String simple : SIMPLE_NAME_TO_PREFIX.keySet()) {
            if (symbolImportCache.contains(simple)) {
                symbolImportCache.invalidate(simple);
                evicted++;
            }
        }
        if (evicted > 0) {
            log.info("T4-BG: WellKnownPackageRegistry evicted {} stale SymbolImportCache entries", evicted);
        }
    }

    /**
     * Resolve a missing package or simple name to its build coordinates.
     *
     * @param symbol   either a dotted package name or a Java simple name. Null / blank → empty.
     * @param manifest current build-manifest content, used for legacy-generation detection on
     *                 rows that carry a legacy variant. May be null — defaults to the modern
     *                 generation.
     */
    public Optional<Gav> lookup(String symbol, String manifest) {
        if (symbol == null || symbol.isBlank()) return Optional.empty();
        if (failedResolutions.contains(symbol)) return Optional.empty();

        Entry entry = lookupEntry(symbol);
        if (entry == null) return Optional.empty();

        boolean legacy = legacyGenerationDeclared(manifest);
        Gav gav = (legacy && entry.legacy() != null) ? entry.legacy() : entry.modern();
        if (gav == null || gav.isBlank()) return Optional.empty();
        return Optional.of(gav);
    }

    /**
     * T4-BM: resolves a registry-known simple name to its canonical FQN + coordinates in one
     * step, so callers need no ecosystem knowledge of their own (the resolver-side
     * {@code canonicalFqnFor} that reconstructed prefixes from GAVs is deleted — the
     * {@code SIMPLE_NAME_TO_PREFIX} rows already store the full prefix). Legacy-generation
     * projects get the legacy prefix via the {@code PACKAGE_REPLACED_BY} inverse.
     *
     * <p>FALLBACK ONLY: consulted after {@link PackageDiscoveryService} abstains. Kept for the
     * case discovery cannot see — the artifact is not on the classpath yet AND no project source
     * imports it (the classic fresh-validation-cascade). File a discovery improvement instead of
     * adding more rows here.
     */
    public Optional<SymbolImportLookupService.Resolution> resolveSimpleName(String simpleName, String manifest) {
        if (simpleName == null || simpleName.isBlank()) return Optional.empty();
        if (failedResolutions.contains(simpleName)) return Optional.empty();
        String prefix = SIMPLE_NAME_TO_PREFIX.get(simpleName);
        if (prefix == null) return Optional.empty();
        boolean legacy = legacyGenerationDeclared(manifest);
        if (legacy) {
            for (Map.Entry<String, String> e : PACKAGE_REPLACED_BY.entrySet()) {
                if (prefix.equals(e.getValue()) || prefix.startsWith(e.getValue() + ".")) {
                    prefix = e.getKey() + prefix.substring(e.getValue().length());
                    break;
                }
            }
        }
        Entry entry = lookupEntry(prefix);
        if (entry == null) return Optional.empty();
        Gav gav = (legacy && entry.legacy() != null) ? entry.legacy() : entry.modern();
        if (gav == null || gav.isBlank()) return Optional.empty();
        return Optional.of(new SymbolImportLookupService.Resolution(
                prefix + "." + simpleName, gav.groupId(), gav.artifactId(), gav.version()));
    }

    /**
     * T4-BI: returns the modern package prefix when {@code missingPackage} has a deterministic
     * source-text replacement. The returned {@link PackageReplacement} carries both the broadest
     * matching old prefix and its replacement so a single rewrite covers every sub-package import
     * in a file.
     *
     * <p>Returns empty for packages with no entry in {@link #PACKAGE_REPLACED_BY}, and also for
     * legacy-generation projects (where the old prefix is still on the classpath via the legacy
     * API jar — adding the new dep there would be wrong, and source rewrites would break the build).
     */
    public Optional<PackageReplacement> replacementFor(String missingPackage, String manifest) {
        if (missingPackage == null || missingPackage.isBlank()) return Optional.empty();
        if (legacyGenerationDeclared(manifest)) return Optional.empty();
        String lower = missingPackage.toLowerCase(Locale.ROOT);
        String bestKey = null;
        for (String key : PACKAGE_REPLACED_BY.keySet()) {
            if (lower.equals(key) || lower.startsWith(key + ".")) {
                if (bestKey == null || key.length() > bestKey.length()) bestKey = key;
            }
        }
        if (bestKey == null) return Optional.empty();
        return Optional.of(new PackageReplacement(bestKey, PACKAGE_REPLACED_BY.get(bestKey)));
    }

    /** Mark a registry resolution as failed (e.g. compile probe still rejected after the
     *  registry-suggested manifest edit). Idempotent. */
    public void markResolutionFailed(String symbol) {
        if (symbol == null || symbol.isBlank()) return;
        failedResolutions.add(symbol);
        log.info("T4-BG: WellKnownPackageRegistry marked {} as resolution-failed; future lookups bypass registry", symbol);
    }

    // -------------------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------------------

    private Entry lookupEntry(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        String lower = symbol.toLowerCase(Locale.ROOT);
        // Package-shape: longest-prefix match against TABLE keys.
        if (lower.contains(".")) {
            String best = null;
            for (String key : TABLE.keySet()) {
                if (lower.equals(key) || lower.startsWith(key + ".")) {
                    if (best == null || key.length() > best.length()) best = key;
                }
            }
            if (best != null) return TABLE.get(best);
        }
        // Simple-name shape: look up the canonical FQN-prefix and re-enter the package path.
        String prefix = SIMPLE_NAME_TO_PREFIX.get(symbol);
        if (prefix == null) return null;
        return TABLE.get(prefix);
    }

    /** True when the manifest declares a platform generation older than the prefix migration
     *  ({@link #GENERATION_MARKER_ARTIFACT} major version below {@link #FIRST_MODERN_GENERATION}).
     *  Absent / unparseable manifests default to the modern generation. */
    private static boolean legacyGenerationDeclared(String manifest) {
        if (manifest == null || manifest.isBlank()) return false;
        Matcher m = PARENT_GENERATION_VERSION.matcher(manifest);
        if (!m.find()) return false;
        try {
            int major = Integer.parseInt(m.group(1));
            return major < FIRST_MODERN_GENERATION;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Lazy file read used by callers that didn't already have the manifest string handy. */
    public String readManifestOrEmpty(String manifestRelativePath) {
        if (manifestRelativePath == null) return "";
        try {
            if (!fileService.exists(manifestRelativePath)) return "";
            String s = fileService.readString(manifestRelativePath);
            return s == null ? "" : s;
        } catch (IOException e) {
            return "";
        }
    }
}
