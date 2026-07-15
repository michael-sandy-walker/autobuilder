package com.walpro.autobuilder.core.build.inject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;

import java.util.List;
import com.walpro.autobuilder.core.build.inject.SymbolImportLookupService.Resolution;
import com.walpro.autobuilder.core.grounding.SymbolGroundingService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T4-BG: end-to-end tests for the well-known package registry + resolver integration +
 * coverage-guard relaxation + cache eviction + behaviour event surface.
 *
 * <p>Eight tests, mirroring the eight cases in the ticket spec.
 */
class WellKnownPackageRegistryTest {

    @TempDir Path tmp;

    private FileService fileService;
    private SymbolImportCache cache;
    private WellKnownPackageRegistry registry;

    private static final String POM_SB3 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.3.0</version>
                </parent>
                <dependencies>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                </dependencies>
            </project>
            """;

    private static final String POM_SB2 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>2.7.18</version>
                </parent>
                <dependencies>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                </dependencies>
            </project>
            """;

    @BeforeEach
    void setUp() throws IOException {
        fileService = new FileService(tmp.toString(), new ObjectMapper());
        cache = new SymbolImportCache(fileService);
        ReflectionTestUtils.setField(cache, "cachePath", "cache/symbol-imports.json");
        cache.load();
        registry = new WellKnownPackageRegistry(fileService, cache);
        Files.createDirectories(tmp.resolve("output"));
    }

    private CycleContextData ctx() {
        return CycleContextData.builder().cycleName("c-1").outputPath("output").build();
    }

    private void writeOutput(String rel, String content) throws IOException {
        Path target = tmp.resolve("output/" + rel);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    // --- Tests 1 + 2 — registry hit on javax.validation, version-dependent GAV --------

    @Test
    void registryHit_javaxValidation_springBoot3_emitsJakartaApi() throws IOException {
        writeOutput("pom.xml", POM_SB3);
        Optional<WellKnownPackageRegistry.Gav> hit = registry.lookup("javax.validation", POM_SB3);

        assertThat(hit).isPresent();
        assertThat(hit.get().groupId()).isEqualTo("jakarta.validation");
        assertThat(hit.get().artifactId()).isEqualTo("jakarta.validation-api");
        assertThat(hit.get().version()).isEqualTo("3.0.2");
    }

    @Test
    void registryHit_javaxValidation_springBoot2_emitsJavaxApi() throws IOException {
        writeOutput("pom.xml", POM_SB2);
        Optional<WellKnownPackageRegistry.Gav> hit = registry.lookup("javax.validation", POM_SB2);

        assertThat(hit).isPresent();
        assertThat(hit.get().groupId()).isEqualTo("javax.validation");
        assertThat(hit.get().artifactId()).isEqualTo("validation-api");
        assertThat(hit.get().version()).isEqualTo("2.0.1.Final");
    }

    // --- Test 3 — registry miss falls through to LLM ----------------------------------

    @Test
    void registryMiss_fallsThroughToLlm() throws IOException {
        writeOutput("pom.xml", POM_SB3);
        SymbolImportLookupService llm = mock(SymbolImportLookupService.class);
        SymbolGroundingService grounding = mock(SymbolGroundingService.class);
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of());
        when(llm.lookup("UnknownLib")).thenReturn(Optional.of(
                new Resolution("com.example.UnknownLib", null, null, null)));

        SymbolImportResolver resolver = new SymbolImportResolver(fileService, llm, grounding, registry, abstainingDiscovery(), abstainingBomVersions());
        // No referencing file exists, so the resolver returns empty — but the important
        // assertion is that the LLM was consulted (proving registry miss → fallthrough).
        resolver.resolve(cls(Kind.CANNOT_FIND_SYMBOL), "UnknownLib", null, ctx());
        verify(llm).lookup("UnknownLib");
    }

    // --- Test 4 — cache eviction on boot for registry-covered names -------------------

    @Test
    void cacheEvictionOnBoot_removesStaleEntriesForRegistryCoveredNames() {
        // Seed a stale javax-variant resolution for NotNull (registry now wants jakarta).
        cache.putHit("NotNull", new Resolution("javax.validation.constraints.NotNull",
                "javax.validation", "validation-api", "2.0.1.Final"));
        assertThat(cache.contains("NotNull")).isTrue();

        // Boot a fresh registry → @PostConstruct evicts the stale entry.
        WellKnownPackageRegistry boot = new WellKnownPackageRegistry(fileService, cache);
        boot.evictStaleCacheEntries();

        assertThat(cache.contains("NotNull"))
                .as("registry boot must evict cache entries it now owns")
                .isFalse();
    }

    // --- Test 7 — markResolutionFailed bypass -----------------------------------------

    @Test
    void markResolutionFailed_subsequentLookupsBypassRegistry() {
        // First lookup hits the registry.
        Optional<WellKnownPackageRegistry.Gav> first = registry.lookup("javax.validation", POM_SB3);
        assertThat(first).isPresent();

        // Caller proves the registry's pick wrong (e.g. compile probe still failed).
        registry.markResolutionFailed("javax.validation");

        // Second lookup skips the registry → empty → caller falls through to LLM.
        Optional<WellKnownPackageRegistry.Gav> second = registry.lookup("javax.validation", POM_SB3);
        assertThat(second).isEmpty();
    }

    // --- T4-BI: replacementFor() — package-rewrite map driven by SB version --------------

    @Test
    void replacementFor_javaxValidationOnSpringBoot3_returnsJakartaReplacement() {
        Optional<WellKnownPackageRegistry.PackageReplacement> r = registry.replacementFor("javax.validation", POM_SB3);
        assertThat(r).isPresent();
        assertThat(r.get().oldPrefix()).isEqualTo("javax.validation");
        assertThat(r.get().newPrefix()).isEqualTo("jakarta.validation");
    }

    @Test
    void replacementFor_javaxValidationOnSpringBoot2_returnsEmpty() {
        // SB2 still has the javax API on classpath via validation-api; rewriting would break it.
        assertThat(registry.replacementFor("javax.validation", POM_SB2)).isEmpty();
    }

    @Test
    void replacementFor_longestPrefixMatch_constraintsSubpackageResolvesToBroadestKey() {
        // The compiler diagnostic might say `javax.validation.constraints does not exist`; the
        // replacement should still anchor on the broadest registry key so a single rewrite covers
        // both `javax.validation.Valid` and `javax.validation.constraints.NotNull` imports.
        Optional<WellKnownPackageRegistry.PackageReplacement> r = registry.replacementFor("javax.validation.constraints", POM_SB3);
        assertThat(r).isPresent();
        assertThat(r.get().oldPrefix()).isEqualTo("javax.validation");
        assertThat(r.get().newPrefix()).isEqualTo("jakarta.validation");
    }

    @Test
    void replacementFor_packageWithoutReplacement_returnsEmpty() {
        // jakarta.persistence is a pure-add — no source rewrite needed.
        assertThat(registry.replacementFor("jakarta.persistence", POM_SB3)).isEmpty();
        assertThat(registry.replacementFor("org.springframework.web", POM_SB3)).isEmpty();
    }

    // --- Test 8 — behaviour event smoke ----------------------------------------------

    @Test
    void registryHit_emitsSymbolResolverRegistryHitBehaviourEvent() throws IOException {
        writeOutput("pom.xml", POM_SB3);
        BehaviourLogger behaviourLogger = mock(BehaviourLogger.class);
        SymbolImportLookupService llm = mock(SymbolImportLookupService.class);
        SymbolGroundingService grounding = mock(SymbolGroundingService.class);
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of());

        SymbolImportResolver resolver = new SymbolImportResolver(fileService, llm, grounding, registry, abstainingDiscovery(), abstainingBomVersions());
        resolver.setBehaviourLogger(behaviourLogger);

        // PACKAGE_DOES_NOT_EXIST → registry-only path → emits the registry-hit event.
        resolver.resolve(cls(Kind.PACKAGE_DOES_NOT_EXIST), "javax.validation", null, ctx());

        ArgumentCaptor<String> symbol = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> group = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> artifact = ArgumentCaptor.forClass(String.class);
        verify(behaviourLogger).symbolResolverRegistryHit(
                symbol.capture(), group.capture(), artifact.capture(), any());

        assertThat(symbol.getValue()).isEqualTo("javax.validation");
        assertThat(group.getValue()).isEqualTo("jakarta.validation");
        assertThat(artifact.getValue()).isEqualTo("jakarta.validation-api");
        // LLM was never consulted — registry pre-empted it.
        verify(llm, never()).lookup(anyString());
    }

    /** T4-AN-7: builds a pattern-path Classification from a Kind (category derived via defaultCategoryFor). */
    private static Classification cls(Kind kind) {
        return new Classification(kind, List.of());
    }


    @Test
    void resolveSimpleName_buildsFqnFromTheTableRow_sb3AndSb2() {
        // T4-BM step 2: the registry owns FQN construction (the resolver-side canonicalFqnFor
        // that reconstructed prefixes from GAVs is deleted). SB2 gets the legacy prefix via
        // the PACKAGE_REPLACED_BY inverse.
        Optional<Resolution> sb3 = registry.resolveSimpleName("NotNull", POM_SB3);
        assertThat(sb3).isPresent();
        assertThat(sb3.get().fqn()).isEqualTo("jakarta.validation.constraints.NotNull");
        assertThat(sb3.get().artifactId()).isEqualTo("jakarta.validation-api");

        Optional<Resolution> sb2 = registry.resolveSimpleName("NotNull", POM_SB2);
        assertThat(sb2).isPresent();
        assertThat(sb2.get().fqn()).isEqualTo("javax.validation.constraints.NotNull");
        assertThat(sb2.get().artifactId()).isEqualTo("validation-api");
    }

    @Test
    void resolveSimpleName_unknownName_orFailureMarked_abstains() {
        assertThat(registry.resolveSimpleName("TotallyUnknown", POM_SB3)).isEmpty();

        assertThat(registry.resolveSimpleName("Valid", POM_SB3)).isPresent();
        registry.markResolutionFailed("Valid");
        assertThat(registry.resolveSimpleName("Valid", POM_SB3))
                .as("failure marker bypasses the registry so the LLM path takes over")
                .isEmpty();
    }

    /** T4-BM: a discovery stub that always abstains, so these tests exercise the fallback chain. */
    private static PackageDiscoveryService abstainingDiscovery() {
        PackageDiscoveryService d = mock(PackageDiscoveryService.class);
        org.mockito.Mockito.lenient()
                .when(d.lookupType(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.lenient()
                .when(d.lookupPackage(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        return d;
    }

    private BomManagedVersionsService abstainingBomVersions() {
        BomManagedVersionsService bom = org.mockito.Mockito.mock(BomManagedVersionsService.class);
        org.mockito.Mockito.lenient()
                .when(bom.managedVersion(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.empty());
        return bom;
    }

    // --- T3-BU: no ecosystem-version jargon in the registry's declared identifiers ---------

    /**
     * T3-BU: the data tables are where ecosystem specifics live; the class's declared
     * identifiers (methods, parameters, fields, nested types, record components) must not
     * name a specific ecosystem or manifest format. Guards against the jargon regressing.
     */
    @Test
    void identifiers_carryNoEcosystemVersionJargon() {
        List<String> banned = List.of("springboot", "javax", "jakarta", "pomxml");
        Set<String> identifiers = new LinkedHashSet<>();

        Class<?> c = WellKnownPackageRegistry.class;
        for (var m : c.getDeclaredMethods()) {
            identifiers.add(m.getName());
            for (var p : m.getParameters()) identifiers.add(p.getName());
        }
        for (var f : c.getDeclaredFields()) identifiers.add(f.getName());
        for (Class<?> nested : c.getDeclaredClasses()) {
            identifiers.add(nested.getSimpleName());
            if (nested.isRecord()) {
                for (var rc : nested.getRecordComponents()) identifiers.add(rc.getName());
            }
            for (var m : nested.getDeclaredMethods()) identifiers.add(m.getName());
        }

        for (String id : identifiers) {
            String lower = id.toLowerCase(java.util.Locale.ROOT);
            for (String jargon : banned) {
                assertThat(lower)
                        .as("declared identifier '%s' must not contain ecosystem jargon '%s'", id, jargon)
                        .doesNotContain(jargon);
            }
        }
    }

}
