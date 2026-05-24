package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.service.FileService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-BG: deterministic registry mapping well-known external Java package prefixes to the Maven
 * coordinates that provide them. Consulted by {@link SymbolImportResolver} before the LLM-backed
 * {@link LlmSymbolImportLookupService}, and by the {@link com.walpro.autobuilder.agent.evolution.EvolutionAgent}
 * coverage guard (T2-DN) so proposals referencing registry-covered symbols aren't rejected for
 * "missing pom dependency" when the resolver chain will resolve the dep deterministically on the
 * next merge.
 *
 * <p>Subsumes the hardcoded {@code KNOWN} table previously in {@code KnownStarterResolver}: every
 * package handled by that resolver lives here now and the resolver is gone (per
 * {@code feedback_capability_ticket_includes_delete} — capability tickets delete the substrate
 * they replace in the same scope).
 *
 * <p>Spring Boot version detection: parses {@code <parent><artifactId>spring-boot-starter-parent</artifactId><version>X.Y.Z</version></parent>}
 * from the project pom and picks jakarta (≥3) vs javax (&lt;3) coordinates for the {@code javax.validation}
 * cluster. Other entries are version-agnostic and use the Spring Boot BOM (no explicit
 * {@code <version>}).
 *
 * <p>Hallucination safety: {@link #markResolutionFailed(String)} adds a per-symbol bypass so the
 * registry isn't consulted again for that name; the resolver then falls through to the LLM path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WellKnownPackageRegistry {

    /** Maven coordinates that provide a registry-covered package. */
    public record Gav(String groupId, String artifactId, String version) {
        public boolean isBlank() {
            return groupId == null || groupId.isBlank() || artifactId == null || artifactId.isBlank();
        }
    }

    /**
     * T4-BI: a registry-known package replacement, e.g. {@code javax.validation} → {@code jakarta.validation}
     * on Spring Boot 3+. {@code oldPrefix} is the broadest registry key that covers the
     * caller's missing package; rewrite logic substitutes the leading {@code oldPrefix} with
     * {@code newPrefix} in every affected source import.
     */
    public record PackageReplacement(String oldPrefix, String newPrefix) {}

    /** Per-prefix entry with an optional Spring-Boot-2 alternative (jakarta vs javax split). */
    private record Entry(Gav modern, Gav springBoot2) {
        Entry(Gav modern) { this(modern, null); }
    }

    private static final Pattern SB_PARENT_VERSION = Pattern.compile(
            "<parent>\\s*(?:<[^>]+>[^<]*</[^>]+>\\s*)*?<artifactId>\\s*spring-boot-starter-parent\\s*</artifactId>"
                    + "\\s*(?:<[^>]+>[^<]*</[^>]+>\\s*)*?<version>\\s*(\\d+)(?:\\.(\\d+))?",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // Package prefix → entry. Order matters for longest-prefix matching, so we use an
    // insertion-ordered map and iterate.
    private static final Map<String, Entry> TABLE = buildTable();

    /**
     * Canonical FQN-prefix lookup for a handful of well-known *simple* names so the coverage
     * guard can recognise "NotNull is covered by javax.validation.constraints" without needing
     * to round-trip through the LLM lookup first. Intentionally tiny — only the most common
     * symbols from the same Spring Boot rejection cascades.
     */
    /**
     * T4-BI: deprecated-package → modern-package rewrite map for Spring Boot 3+ projects.
     * Consumed by {@link #replacedBy(String, String)} to drive {@link SymbolImportResolver}'s
     * import-rewrite mode when a {@code PACKAGE_DOES_NOT_EXIST} diagnostic names a package that
     * has a deterministic modern replacement (adding the new GAV alone isn't enough — the file
     * still imports from the dead prefix).
     *
     * <p>Longest-prefix matching: a missing {@code javax.validation.constraints} resolves against
     * the {@code javax.validation} key and rewrites the broader prefix, so {@code import javax.validation.Valid;}
     * AND {@code import javax.validation.constraints.NotNull;} in the same file both get migrated
     * in one pass. Suppressed on Spring Boot 2 projects, where the old prefix is still correct.
     */
    private static final Map<String, String> PACKAGE_REPLACED_BY = Map.of(
            "javax.validation",  "jakarta.validation",
            "javax.persistence", "jakarta.persistence"
    );

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

        // javax.validation cluster — SB3 → jakarta API jar; SB2 → javax API jar.
        Entry validation = new Entry(
                new Gav("jakarta.validation", "jakarta.validation-api", "3.0.2"),
                new Gav("javax.validation", "validation-api", "2.0.1.Final"));
        m.put("javax.validation",                 validation);
        m.put("javax.validation.constraints",     validation);
        m.put("jakarta.validation",               validation);
        m.put("jakarta.validation.constraints",   validation);
        m.put("org.hibernate.validator",          new Entry(
                new Gav("org.hibernate.validator", "hibernate-validator", "8.0.1.Final")));

        // Spring Boot starters — version comes from the SB BOM, so no explicit version.
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
     * Resolve a missing package or simple name to its Maven coordinates.
     *
     * @param symbol     either a dotted package name (e.g. {@code javax.validation}) or a Java
     *                   simple name (e.g. {@code NotNull}). Null / blank → empty.
     * @param pomXml     current {@code pom.xml} content, used for Spring Boot version detection
     *                   on the javax/jakarta split. May be null — defaults to SB3 / jakarta.
     */
    public Optional<Gav> lookup(String symbol, String pomXml) {
        if (symbol == null || symbol.isBlank()) return Optional.empty();
        if (failedResolutions.contains(symbol)) return Optional.empty();

        Entry entry = lookupEntry(symbol);
        if (entry == null) return Optional.empty();

        boolean isSpringBoot2 = isSpringBoot2(pomXml);
        Gav gav = (isSpringBoot2 && entry.springBoot2() != null) ? entry.springBoot2() : entry.modern();
        if (gav == null || gav.isBlank()) return Optional.empty();
        return Optional.of(gav);
    }

    /**
     * True iff {@code symbol} would be resolved by the registry — used by the coverage guard to
     * decide "is this missing symbol something the resolver chain will deterministically supply
     * post-merge?" Does NOT consult {@link #failedResolutions} (a failed prior resolution doesn't
     * mean the symbol is unsolvable; the LLM path may still handle it, so we conservatively
     * keep treating it as covered for guard purposes).
     */
    public boolean isCovered(String symbol) {
        return lookupEntry(symbol) != null;
    }

    /**
     * Filter a missing-symbols set down to those the registry does NOT cover. Used by the
     * coverage guard to relax the "all missing symbols must be addressed by this proposal"
     * rule for symbols a downstream resolver will handle deterministically.
     */
    public Set<String> filterCovered(Set<String> missingSymbols) {
        if (missingSymbols == null || missingSymbols.isEmpty()) return Set.of();
        Set<String> stillUnresolved = new LinkedHashSet<>();
        for (String s : missingSymbols) {
            if (!isCovered(s)) stillUnresolved.add(s);
        }
        return stillUnresolved;
    }

    /** Subset of {@code missingSymbols} that ARE covered — for telemetry / behaviour events. */
    public Set<String> coveredSubset(Set<String> missingSymbols) {
        if (missingSymbols == null || missingSymbols.isEmpty()) return Set.of();
        Set<String> covered = new LinkedHashSet<>();
        for (String s : missingSymbols) {
            if (isCovered(s)) covered.add(s);
        }
        return covered;
    }

    /**
     * T4-BI: returns the modern package prefix when {@code missingPackage} has a deterministic
     * source-text replacement (e.g. {@code javax.validation} → {@code jakarta.validation} on
     * Spring Boot 3+). The returned {@link PackageReplacement} carries both the broadest matching
     * old prefix and its replacement so a single rewrite covers every sub-package import in a file.
     *
     * <p>Returns empty for packages with no entry in {@link #PACKAGE_REPLACED_BY}, and also for
     * Spring Boot 2 projects (where the old prefix is still on the classpath via the {@code javax}
     * API jar — adding the new dep here would be wrong, and source rewrites would break the build).
     */
    public Optional<PackageReplacement> replacedBy(String missingPackage, String pomXml) {
        if (missingPackage == null || missingPackage.isBlank()) return Optional.empty();
        if (isSpringBoot2(pomXml)) return Optional.empty();
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
     *  registry-suggested pom edit). Idempotent. */
    public void markResolutionFailed(String symbol) {
        if (symbol == null || symbol.isBlank()) return;
        failedResolutions.add(symbol);
        log.info("T4-BG: WellKnownPackageRegistry marked {} as resolution-failed; future lookups bypass registry", symbol);
    }

    /** Hot-test reset for unit tests; never call from production code. */
    void __clearFailureMarkersForTest() {
        failedResolutions.clear();
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

    private static boolean isSpringBoot2(String pomXml) {
        if (pomXml == null || pomXml.isBlank()) return false;
        Matcher m = SB_PARENT_VERSION.matcher(pomXml);
        if (!m.find()) return false;
        try {
            int major = Integer.parseInt(m.group(1));
            return major < 3;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Lazy file read used by callers that didn't already have the pom string handy. */
    public String readPomOrEmpty(String pomRelativePath) {
        if (pomRelativePath == null) return "";
        try {
            if (!fileService.exists(pomRelativePath)) return "";
            String s = fileService.readString(pomRelativePath);
            return s == null ? "" : s;
        } catch (IOException e) {
            return "";
        }
    }
}
