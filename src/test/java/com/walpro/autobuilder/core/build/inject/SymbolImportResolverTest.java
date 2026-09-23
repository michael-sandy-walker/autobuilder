package com.walpro.autobuilder.core.build.inject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;

import java.util.List;
import com.walpro.autobuilder.core.build.inject.SymbolImportLookupService.Resolution;
import com.walpro.autobuilder.core.grounding.SymbolGroundingService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SymbolImportResolverTest {

    @TempDir Path tmp;

    private FileService fileService;
    private SymbolImportLookupService lookup;
    private SymbolGroundingService grounding;
    private WellKnownPackageRegistry registry;
    private PackageDiscoveryService discovery;
    private BomManagedVersionsService bomVersions;
    private SymbolImportResolver resolver;
    private CycleContextData ctx;

    private static final String BASE_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
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
        lookup = mock(SymbolImportLookupService.class);
        grounding = mock(SymbolGroundingService.class);
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of());
        registry = mock(WellKnownPackageRegistry.class);
        when(registry.lookup(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.lenient()
                .when(registry.resolveSimpleName(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        when(registry.readManifestOrEmpty(org.mockito.ArgumentMatchers.anyString())).thenReturn("");
        // T4-BM: discovery abstains in these unit tests — they exercise the fallback chain.
        discovery = mock(PackageDiscoveryService.class);
        org.mockito.Mockito.lenient()
                .when(discovery.lookupType(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.lenient()
                .when(discovery.lookupPackage(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        bomVersions = mock(BomManagedVersionsService.class);
        org.mockito.Mockito.lenient()
                .when(bomVersions.managedVersion(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        resolver = new SymbolImportResolver(fileService, lookup, grounding, registry, discovery, bomVersions);
        ctx = CycleContextData.builder().cycleName("c-1").outputPath("output").build();
        Files.createDirectories(tmp.resolve("output"));
    }

    private void writeOutput(String rel, String content) throws IOException {
        Path target = tmp.resolve("output/" + rel);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    /**
     * T2-FA: stderr verbatim from run {@code 2026-08-24_00-07-06}, whose artifact imported a
     * package its own pom never declared. The registry happens to hold a row for this one; the
     * test denies it that row, because the question is what happens for the packages nobody wrote
     * a row for — before this path, nothing at all, and the symbol path spent the build adding an
     * import beside one that cannot resolve.
     */
    private static final String UNDECLARED_DEPENDENCY_STDERR = """
            [ERROR] /out/src/main/java/com/example/library/domain/repository/LoanRepository.java:[3,47] package org.springframework.data.jpa.repository does not exist
            [ERROR] /out/src/main/java/com/example/library/domain/repository/LoanRepository.java:[5,41] cannot find symbol
            [ERROR]   symbol: class JpaRepository
            """;

    @Test
    void resolve_undeclaredDependency_addsTheSearchedCoordinateToThePom() throws IOException {
        writeOutput("pom.xml", BASE_POM);
        when(discovery.lookupUndeclaredPackage(
                "org.springframework.data.jpa.repository", "JpaRepository", tmp.resolve("output")))
                .thenReturn(Optional.of(new PackageDiscoveryService.Discovery(
                        new Resolution(
                                "org.springframework.data.jpa.repository.JpaRepository",
                                "org.springframework.boot", "spring-boot-starter-data-jpa", "3.3.2"),
                        "coordinate-search")));

        Optional<FixProposal> proposal = resolver.resolve(
                cls(Kind.PACKAGE_DOES_NOT_EXIST), "org.springframework.data.jpa.repository",
                UNDECLARED_DEPENDENCY_STDERR, ctx);

        assertThat(proposal).isPresent();
        assertThat(proposal.get().record().rule()).isEqualTo("coordinate-search:spring-boot-starter-data-jpa");
        assertThat(proposal.get().mutations()).hasSize(1);
        assertThat(proposal.get().mutations().get(0).content())
                .contains("spring-boot-starter-data-jpa");
    }

    // ── T1-GF: an unmanaged dependency always gets the most suitable version ──────────────

    private static final String ACME_STDERR = """
            [ERROR] /out/src/main/java/x/App.java:[3,20] package org.acme.web does not exist
            [ERROR]   symbol: class Router
            """;

    private void discoveredWithoutVersion() {
        when(discovery.lookupUndeclaredPackage(
                org.mockito.ArgumentMatchers.eq("org.acme.web"), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(new PackageDiscoveryService.Discovery(
                        new Resolution("org.acme.web.Router", "org.acme", "acme-web", null), "coordinate-search")));
    }

    @Test
    void resolve_unversionedRowOnABomlessPom_alignsToTheGroupVersionThePomAlreadyUses() throws IOException {
        writeOutput("pom.xml", """
                <project><dependencies>
                    <dependency><groupId>org.acme</groupId><artifactId>acme-core</artifactId><version>2.4.1</version></dependency>
                </dependencies></project>
                """);
        discoveredWithoutVersion();
        when(discovery.suitableVersion("org.acme", "acme-web", "2.4.1")).thenReturn(Optional.of("2.4.1"));

        Optional<FixProposal> proposal = resolver.resolve(cls(Kind.PACKAGE_DOES_NOT_EXIST), "org.acme.web", ACME_STDERR, ctx);

        assertThat(proposal).isPresent();
        assertThat(proposal.get().mutations().get(0).content())
                .contains("<artifactId>acme-web</artifactId>").contains("<version>2.4.1</version>");
    }

    @Test
    void resolve_unversionedRowWithNothingToAlignTo_asksForTheMostSuitableVersion_ratherThanWritingNone()
            throws IOException {
        // Before: a row carrying no version (it assumed a BOM) became a <dependency> with no
        // <version> on a pom that has no BOM — a manifest the build tool rejects.
        writeOutput("pom.xml", "<project><dependencies></dependencies></project>");
        discoveredWithoutVersion();
        when(discovery.suitableVersion("org.acme", "acme-web", null)).thenReturn(Optional.of("5.0.0"));

        Optional<FixProposal> proposal = resolver.resolve(cls(Kind.PACKAGE_DOES_NOT_EXIST), "org.acme.web", ACME_STDERR, ctx);

        assertThat(proposal).isPresent();
        assertThat(proposal.get().mutations().get(0).content()).contains("<version>5.0.0</version>");
    }

    @Test
    void resolve_undeclaredDependency_abstainsWhenNoProbeCanNameIt() throws IOException {
        writeOutput("pom.xml", BASE_POM);
        // Discovery, registry and search all silent — the mocks' default. The old path had the
        // same outcome for a different reason: it never asked.
        assertThat(resolver.resolve(cls(Kind.PACKAGE_DOES_NOT_EXIST),
                "org.springframework.data.jpa.repository", UNDECLARED_DEPENDENCY_STDERR, ctx)).isEmpty();
    }

    @Test
    void resolve_wrongKind_returnsEmpty() {
        assertThat(resolver.resolve(cls(Kind.MISSING_POM), "X", null, ctx)).isEmpty();
        // PACKAGE_DOES_NOT_EXIST is now handled (T4-BG) — but the default registry mock
        // returns empty, so without a registry hit the resolver still returns empty.
        assertThat(resolver.resolve(cls(Kind.PACKAGE_DOES_NOT_EXIST), "p.q", null, ctx)).isEmpty();
        assertThat(resolver.resolve(cls(Kind.UNKNOWN), null, null, ctx)).isEmpty();
    }

    /**
     * T2-FA: stderr verbatim from run {@code 2026-08-01_20-38-08} at 21:10 — a controller whose
     * dead {@code javax.validation} import produces BOTH a package error and the symbol errors it
     * was meant to supply. The classifier names the symbol, and the symbol path used to add a
     * second import beside the broken one, which cannot compile: measured 111 fires and 85 red
     * re-probes (77%) across 32 field runs, each failure counting toward T2-EN's cap.
     */
    private static final String JAVAX_AND_SYMBOL_STDERR = """
            [ERROR] /out/src/main/java/com/example/demo/controller/BooksController.java:[11,24] package javax.validation does not exist
            [ERROR] /out/src/main/java/com/example/demo/controller/BooksController.java:[12,36] package javax.validation.constraints does not exist
            [ERROR] /out/src/main/java/com/example/demo/controller/BooksController.java:[24,45] cannot find symbol
              symbol:   class Valid
              location: class com.example.demo.controller.BooksController
            """;

    @Test
    void resolve_symbolErrorBesideAMigratablePackage_migratesInsteadOfAddingASecondImport()
            throws IOException {
        writeOutput("pom.xml", BASE_POM);
        writeOutput("src/main/java/com/example/demo/controller/BooksController.java", """
                package com.example.demo.controller;
                import javax.validation.Valid;
                public class BooksController { public void create(@Valid Object o) {} }
                """);
        when(registry.replacementFor(org.mockito.ArgumentMatchers.eq("javax.validation"),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(new WellKnownPackageRegistry.PackageReplacement(
                        "javax.validation", "jakarta.validation")));
        when(registry.lookup(org.mockito.ArgumentMatchers.eq("javax.validation"),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(new WellKnownPackageRegistry.Gav(
                        "jakarta.validation", "jakarta.validation-api", "3.0.2")));

        Optional<FixProposal> proposal =
                resolver.resolve(cls(Kind.CANNOT_FIND_SYMBOL), "Valid", JAVAX_AND_SYMBOL_STDERR, ctx);

        assertThat(proposal).isPresent();
        assertThat(proposal.get().record().rule())
                .as("the migration is the fix that can clear this build; a second import cannot")
                .startsWith("package-replace:javax.validation->jakarta.validation");
        String rewritten = proposal.get().mutations().stream()
                .filter(m -> m.path().endsWith("BooksController.java"))
                .findFirst().orElseThrow().content();
        assertThat(rewritten)
                .as("the DEAD import is gone — that is what the old path left in place")
                .doesNotContain("javax.validation")
                .contains("jakarta.validation.Valid");
        // The lookup chain must never have been consulted: deferring happens before it.
        org.mockito.Mockito.verify(lookup, org.mockito.Mockito.never())
                .lookup(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void resolve_symbolErrorWithNoMigratablePackage_takesTheOrdinaryPath() {
        // The narrowing condition: a package error the registry has no replacement for must not
        // divert anything. Without this the test above would pass for a rule that always defers.
        when(registry.replacementFor(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        when(lookup.lookup("Valid")).thenReturn(Optional.empty());

        assertThat(resolver.resolve(cls(Kind.CANNOT_FIND_SYMBOL), "Valid", JAVAX_AND_SYMBOL_STDERR, ctx))
                .isEmpty();
        org.mockito.Mockito.verify(lookup).lookup("Valid");
    }

    @Test
    void resolve_nonSimpleIdentifier_returnsEmpty() {
        assertThat(resolver.resolve(cls(Kind.CANNOT_FIND_SYMBOL), "has.a.dot", null, ctx)).isEmpty();
        assertThat(resolver.resolve(cls(Kind.CANNOT_FIND_SYMBOL), " ", null, ctx)).isEmpty();
    }

    @Test
    void resolve_memberShapedToken_neverReachesExternalLookup() {
        // T4-BM step 6b: `cannot find symbol: method isPresent` cannot be fixed by a type
        // import. These tokens previously flowed to the LLM and left NONE rows in the
        // SymbolImportCache (22 of the 25 inspected entries were exactly this class).
        assertThat(resolver.resolve(cls(Kind.CANNOT_FIND_SYMBOL), "isPresent", null, ctx)).isEmpty();
        assertThat(resolver.resolve(cls(Kind.CANNOT_FIND_SYMBOL), "memberRepository", null, ctx)).isEmpty();

        org.mockito.Mockito.verifyNoInteractions(lookup);
        org.mockito.Mockito.verify(discovery, org.mockito.Mockito.never())
                .lookupType(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void resolve_projectLocalLowercaseType_stillGetsLocalImport() throws IOException {
        // The member-token gate must not break local cross-package imports for a project that
        // (unconventionally) names a type in lowercase — local resolution runs first.
        when(grounding.definedSymbolsInOutput()).thenReturn(
                java.util.Set.of("widget", "com.example.util.widget"));
        writeOutput("src/main/java/com/example/util/widget.java",
                "package com.example.util;\npublic class widget {}\n");
        writeOutput("src/main/java/com/example/Uses.java",
                "package com.example;\npublic class Uses { widget w; }\n");

        var proposal = resolver.resolve(cls(Kind.CANNOT_FIND_SYMBOL), "widget", null, ctx);

        assertThat(proposal).isPresent();
        assertThat(proposal.get().record().rule()).isEqualTo("symbol-import:local");
        org.mockito.Mockito.verifyNoInteractions(lookup);
    }

    @Test
    void resolve_discoveryHit_winsOverRegistryAndLlm() throws IOException {
        // T4-BM: project observation first. A discovery hit builds the proposal without the
        // registry fallback or the LLM being consulted at all.
        when(discovery.lookupType(org.mockito.ArgumentMatchers.eq("StringUtils"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(new PackageDiscoveryService.Discovery(
                        new Resolution("org.apache.commons.lang3.StringUtils",
                                "org.apache.commons", "commons-lang3", "3.14.0"),
                        "classpath")));
        writeOutput("src/main/java/com/example/Text.java", """
                package com.example;
                public class Text { boolean b = StringUtils.isBlank(""); }
                """);
        writeOutput("pom.xml", "<project><dependencies></dependencies></project>");

        var proposal = resolver.resolve(cls(Kind.CANNOT_FIND_SYMBOL), "StringUtils", null, ctx);

        assertThat(proposal).isPresent();
        assertThat(proposal.get().record().rule()).isEqualTo("symbol-import:commons-lang3");
        org.mockito.Mockito.verifyNoInteractions(lookup);
        org.mockito.Mockito.verify(registry, org.mockito.Mockito.never())
                .resolveSimpleName(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void resolveMissingPackage_discoveryGav_winsOverRegistry() throws IOException {
        // Failure mode 3: the package is already on the project's resolved classpath — the
        // project's own coordinates are used; the registry's row never consulted.
        when(discovery.lookupPackage(org.mockito.ArgumentMatchers.eq("javax.validation"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(new PackageDiscoveryService.Discovery(
                        new Resolution("javax.validation", "javax.validation", "validation-api", "2.0.1.Final"),
                        "classpath")));
        when(registry.replacementFor(org.mockito.ArgumentMatchers.eq("javax.validation"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        writeOutput("pom.xml", "<project><dependencies></dependencies></project>");

        var proposal = resolver.resolve(cls(Kind.PACKAGE_DOES_NOT_EXIST), "javax.validation", null, ctx);

        assertThat(proposal).isPresent();
        assertThat(proposal.get().record().rule()).isEqualTo("registry:validation-api");
        org.mockito.Mockito.verify(registry, org.mockito.Mockito.never())
                .lookup(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void resolve_lookupReturnsEmpty_resolverReturnsEmpty() {
        when(lookup.lookup("Foo")).thenReturn(Optional.empty());

        assertThat(resolver.resolve(cls(Kind.CANNOT_FIND_SYMBOL), "Foo", null, ctx)).isEmpty();
    }

    @Test
    void resolve_lookupHit_addsImportToReferencingFile() throws IOException {
        when(lookup.lookup("NotBlank"))
                .thenReturn(Optional.of(new Resolution(
                        "jakarta.validation.constraints.NotBlank", null, null, null)));
        writeOutput("src/main/java/com/example/Foo.java", """
                package com.example;
                public class Foo {
                    @NotBlank private String name;
                }
                """);

        Optional<FixProposal> out = resolver.resolve(cls(Kind.CANNOT_FIND_SYMBOL), "NotBlank", null, ctx);

        assertThat(out).isPresent();
        FixProposal p = out.get();
        assertThat(p.mutations()).hasSize(1);
        assertThat(p.mutations().get(0).path()).isEqualTo("src/main/java/com/example/Foo.java");
        assertThat(p.mutations().get(0).content())
                .contains("import jakarta.validation.constraints.NotBlank;");
        assertThat(p.record().rule()).isEqualTo("symbol-import:import-only");
    }

    @Test
    void resolve_lookupHitWithGav_addsImportAndDependency() throws IOException {
        when(lookup.lookup("RestController"))
                .thenReturn(Optional.of(new Resolution(
                        "org.springframework.web.bind.annotation.RestController",
                        "org.springframework.boot",
                        "spring-boot-starter-validation",
                        null)));
        writeOutput("src/main/java/com/example/Foo.java", """
                package com.example;
                public class Foo {
                    @RestController class Inner {}
                }
                """);
        writeOutput("pom.xml", BASE_POM);

        Optional<FixProposal> out = resolver.resolve(cls(Kind.CANNOT_FIND_SYMBOL), "RestController", null, ctx);

        assertThat(out).isPresent();
        FixProposal p = out.get();
        // 2 mutations: one for Foo.java, one for pom.xml.
        assertThat(p.mutations()).hasSize(2);
        assertThat(p.mutations()).anyMatch(m -> m.path().equals("pom.xml")
                && m.content().contains("<artifactId>spring-boot-starter-validation</artifactId>"));
        assertThat(p.mutations()).anyMatch(m -> m.path().endsWith("Foo.java")
                && m.content().contains("import org.springframework.web.bind.annotation.RestController;"));
        assertThat(p.record().rule()).isEqualTo("symbol-import:spring-boot-starter-validation");
    }

    @Test
    void resolve_bomManagedArtifact_omitsTheVersionLiteral() throws IOException {
        // T4-BM step 4, the field shape (2026-07-09..14): the resolution carries the coordinate
        // search's latest-version pick (a milestone) while the project's own BOM chain manages
        // the artifact — the pom edit must declare NO version and let the BOM govern.
        when(lookup.lookup("NotBlank"))
                .thenReturn(Optional.of(new Resolution(
                        "jakarta.validation.constraints.NotBlank",
                        "jakarta.validation", "jakarta.validation-api", "3.1.0-M1")));
        org.mockito.Mockito.lenient()
                .when(bomVersions.managedVersion(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("jakarta.validation"),
                        org.mockito.ArgumentMatchers.eq("jakarta.validation-api")))
                .thenReturn(Optional.of("3.0.2"));
        writeOutput("src/main/java/com/example/Foo.java", """
                package com.example;
                public class Foo {
                    @NotBlank private String name;
                }
                """);
        writeOutput("pom.xml", BASE_POM);

        Optional<FixProposal> out = resolver.resolve(cls(Kind.CANNOT_FIND_SYMBOL), "NotBlank", null, ctx);

        assertThat(out).isPresent();
        assertThat(out.get().mutations()).anyMatch(m -> m.path().equals("pom.xml")
                && m.content().contains("<artifactId>jakarta.validation-api</artifactId>")
                && !m.content().contains("3.1.0-M1"));
    }

    @Test
    void resolve_alreadyImported_noDuplicate() throws IOException {
        when(lookup.lookup("NotBlank"))
                .thenReturn(Optional.of(new Resolution(
                        "jakarta.validation.constraints.NotBlank", null, null, null)));
        writeOutput("src/main/java/com/example/Foo.java", """
                package com.example;
                import jakarta.validation.constraints.NotBlank;
                public class Foo {
                    @NotBlank private String name;
                }
                """);

        Optional<FixProposal> out = resolver.resolve(cls(Kind.CANNOT_FIND_SYMBOL), "NotBlank", null, ctx);

        // No referencing files left after the import-already-present filter — nothing to inject.
        assertThat(out).isEmpty();
    }

    @Test
    void resolve_dependencyAlreadyInPom_emitsImportOnly() throws IOException {
        when(lookup.lookup("WebMvcTest"))
                .thenReturn(Optional.of(new Resolution(
                        "org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest",
                        "org.springframework.boot",
                        "spring-boot-starter-web",  // dep is already in BASE_POM
                        null)));
        writeOutput("src/main/java/com/example/Foo.java", """
                package com.example;
                public class Foo {
                    @WebMvcTest class T {}
                }
                """);
        writeOutput("pom.xml", BASE_POM);

        Optional<FixProposal> out = resolver.resolve(cls(Kind.CANNOT_FIND_SYMBOL), "WebMvcTest", null, ctx);

        assertThat(out).isPresent();
        assertThat(out.get().mutations()).hasSize(1);
        assertThat(out.get().mutations().get(0).path()).endsWith("Foo.java");
    }

    @Test
    void resolve_localCrossPackageImport_doesNotConsultLookup() throws IOException {
        // User class BookRepository exists in another package — emit local import without LLM.
        writeOutput("src/main/java/com/example/repo/BookRepository.java", """
                package com.example.repo;
                public interface BookRepository {}
                """);
        writeOutput("src/main/java/com/example/service/BookService.java", """
                package com.example.service;
                public class BookService {
                    private BookRepository repo;
                }
                """);
        // SymbolGroundingService.definedSymbolsInOutput() returns both simple names and FQNs —
        // see SymbolGroundingService.collectDeclarations.
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of(
                "BookRepository", "com.example.repo.BookRepository",
                "BookService", "com.example.service.BookService"));

        Optional<FixProposal> out = resolver.resolve(cls(Kind.CANNOT_FIND_SYMBOL), "BookRepository", null, ctx);

        assertThat(out).isPresent();
        assertThat(out.get().record().rule()).isEqualTo("symbol-import:local");
        assertThat(out.get().mutations()).hasSize(1);
        assertThat(out.get().mutations().get(0).content())
                .contains("import com.example.repo.BookRepository;");
        // Lookup must not have been called — local-class case bypasses it.
        org.mockito.Mockito.verifyNoInteractions(lookup);
    }

    @Test
    void resolve_noReferencingFile_returnsEmpty() throws IOException {
        // FQN resolves, but no file references the bare name → nothing to import.
        when(lookup.lookup("Foo")).thenReturn(Optional.of(new Resolution("a.b.Foo", null, null, null)));
        writeOutput("src/main/java/com/example/Unrelated.java", """
                package com.example;
                public class Unrelated {}
                """);

        assertThat(resolver.resolve(cls(Kind.CANNOT_FIND_SYMBOL), "Foo", null, ctx)).isEmpty();
    }

    @Test
    void insertImport_afterLastExistingImport() {
        String src = """
                package x;
                import a.b.C;
                public class T {}
                """;
        String out = SymbolImportResolver.insertImport(src, "d.e.F");
        assertThat(out).contains("import a.b.C;\nimport d.e.F;");
    }

    @Test
    void insertImport_afterPackageWhenNoImportsYet() {
        String src = """
                package x;
                public class T {}
                """;
        String out = SymbolImportResolver.insertImport(src, "d.e.F");
        assertThat(out).contains("package x;\n\nimport d.e.F;");
    }

    @Test
    void alreadyImports_detectsWildcard() {
        String src = "import a.b.*;";
        assertThat(SymbolImportResolver.alreadyImports(src, "a.b.Foo", "Foo")).isTrue();
        assertThat(SymbolImportResolver.alreadyImports(src, "x.y.Foo", "Foo")).isFalse();
    }

    // ----- T4-BI: package-replace (registry-known package replacement) ---------------

    @Test
    void packageReplace_emitsPomEditAndRewritesSourceImports() throws IOException {
        when(registry.lookup(org.mockito.ArgumentMatchers.eq("javax.validation"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(new WellKnownPackageRegistry.Gav(
                        "jakarta.validation", "jakarta.validation-api", "3.0.2")));
        when(registry.replacementFor(org.mockito.ArgumentMatchers.eq("javax.validation"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(new WellKnownPackageRegistry.PackageReplacement(
                        "javax.validation", "jakarta.validation")));
        writeOutput("pom.xml", BASE_POM);
        writeOutput("src/main/java/com/example/GlobalExceptionHandler.java", """
                package com.example;
                import javax.validation.ConstraintViolationException;
                public class GlobalExceptionHandler {
                    public void handle(ConstraintViolationException e) {}
                }
                """);

        Optional<FixProposal> out = resolver.resolve(cls(Kind.PACKAGE_DOES_NOT_EXIST), "javax.validation", null, ctx);

        assertThat(out).isPresent();
        FixProposal p = out.get();
        assertThat(p.record().rule()).isEqualTo("package-replace:javax.validation->jakarta.validation");
        assertThat(p.mutations()).hasSize(2);
        assertThat(p.mutations()).anyMatch(m -> m.path().equals("pom.xml")
                && m.content().contains("<artifactId>jakarta.validation-api</artifactId>"));
        assertThat(p.mutations()).anyMatch(m -> m.path().endsWith("GlobalExceptionHandler.java")
                && m.content().contains("import jakarta.validation.ConstraintViolationException;")
                && !m.content().contains("import javax.validation."));
    }

    @Test
    void packageReplace_sourceAlreadyOnJakartaAndPomHasDep_returnsEmpty() throws IOException {
        when(registry.lookup(org.mockito.ArgumentMatchers.eq("javax.validation"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(new WellKnownPackageRegistry.Gav(
                        "jakarta.validation", "jakarta.validation-api", "3.0.2")));
        when(registry.replacementFor(org.mockito.ArgumentMatchers.eq("javax.validation"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(new WellKnownPackageRegistry.PackageReplacement(
                        "javax.validation", "jakarta.validation")));
        String pomWithDep = BASE_POM.replace("</dependencies>", """
                    <dependency>
                        <groupId>jakarta.validation</groupId>
                        <artifactId>jakarta.validation-api</artifactId>
                    </dependency>
                </dependencies>""");
        writeOutput("pom.xml", pomWithDep);
        writeOutput("src/main/java/com/example/Clean.java", """
                package com.example;
                import jakarta.validation.constraints.NotBlank;
                public class Clean {
                    @NotBlank private String name;
                }
                """);

        Optional<FixProposal> out = resolver.resolve(cls(Kind.PACKAGE_DOES_NOT_EXIST), "javax.validation", null, ctx);

        assertThat(out).isEmpty();
    }

    @Test
    void packageReplace_multipleSourceFiles_allRewrittenInOneProposal() throws IOException {
        when(registry.lookup(org.mockito.ArgumentMatchers.eq("javax.validation"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(new WellKnownPackageRegistry.Gav(
                        "jakarta.validation", "jakarta.validation-api", "3.0.2")));
        when(registry.replacementFor(org.mockito.ArgumentMatchers.eq("javax.validation"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(new WellKnownPackageRegistry.PackageReplacement(
                        "javax.validation", "jakarta.validation")));
        writeOutput("pom.xml", BASE_POM);
        writeOutput("src/main/java/com/example/A.java", """
                package com.example;
                import javax.validation.Valid;
                public class A { public void x(@Valid String s) {} }
                """);
        writeOutput("src/main/java/com/example/B.java", """
                package com.example;
                import javax.validation.constraints.NotNull;
                public class B { @NotNull private String n; }
                """);

        Optional<FixProposal> out = resolver.resolve(cls(Kind.PACKAGE_DOES_NOT_EXIST), "javax.validation", null, ctx);

        assertThat(out).isPresent();
        // pom.xml + A.java + B.java = 3 mutations.
        assertThat(out.get().mutations()).hasSize(3);
        assertThat(out.get().mutations()).anyMatch(m -> m.path().endsWith("A.java")
                && m.content().contains("import jakarta.validation.Valid;"));
        assertThat(out.get().mutations()).anyMatch(m -> m.path().endsWith("B.java")
                && m.content().contains("import jakarta.validation.constraints.NotNull;"));
    }

    @Test
    void packageReplace_registryEntryWithoutReplacement_emitsPomOnly() throws IOException {
        // Pure-add case: jakarta.persistence has a GAV but no replacementFor mapping.
        when(registry.lookup(org.mockito.ArgumentMatchers.eq("jakarta.persistence"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(new WellKnownPackageRegistry.Gav(
                        "jakarta.persistence", "jakarta.persistence-api", "3.1.0")));
        when(registry.replacementFor(org.mockito.ArgumentMatchers.eq("jakarta.persistence"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        writeOutput("pom.xml", BASE_POM);
        writeOutput("src/main/java/com/example/Entity.java", """
                package com.example;
                import jakarta.persistence.Entity;
                @Entity public class Entity {}
                """);

        Optional<FixProposal> out = resolver.resolve(cls(Kind.PACKAGE_DOES_NOT_EXIST), "jakarta.persistence", null, ctx);

        assertThat(out).isPresent();
        assertThat(out.get().record().rule()).isEqualTo("registry:jakarta.persistence-api");
        assertThat(out.get().mutations()).hasSize(1);
        assertThat(out.get().mutations().get(0).path()).isEqualTo("pom.xml");
    }

    @Test
    void rewriteImportLines_specificTokenAndWildcard() {
        String src = """
                package x;
                import javax.validation.Valid;
                import javax.validation.constraints.NotNull;
                import javax.validation.*;
                import static javax.validation.Validation.byProvider;
                import org.unrelated.Thing;
                public class T {}
                """;
        String out = SymbolImportResolver.rewriteImportLines(src, "javax.validation", "jakarta.validation");
        assertThat(out).contains("import jakarta.validation.Valid;");
        assertThat(out).contains("import jakarta.validation.constraints.NotNull;");
        assertThat(out).contains("import jakarta.validation.*;");
        assertThat(out).contains("import static jakarta.validation.Validation.byProvider;");
        assertThat(out).contains("import org.unrelated.Thing;");
        assertThat(out).doesNotContain("import javax.validation");
    }

    @Test
    void rewriteImportLines_noMatch_returnsNull() {
        String src = """
                package x;
                import org.unrelated.Thing;
                public class T {}
                """;
        assertThat(SymbolImportResolver.rewriteImportLines(src, "javax.validation", "jakarta.validation"))
                .isNull();
    }

    @Test
    void packageReplace_idempotent_secondInvocationNoOps() throws IOException {
        when(registry.lookup(org.mockito.ArgumentMatchers.eq("javax.validation"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(new WellKnownPackageRegistry.Gav(
                        "jakarta.validation", "jakarta.validation-api", "3.0.2")));
        when(registry.replacementFor(org.mockito.ArgumentMatchers.eq("javax.validation"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(new WellKnownPackageRegistry.PackageReplacement(
                        "javax.validation", "jakarta.validation")));
        writeOutput("pom.xml", BASE_POM);
        writeOutput("src/main/java/com/example/E.java", """
                package com.example;
                import javax.validation.Valid;
                public class E { public void x(@Valid String s) {} }
                """);

        Optional<FixProposal> first = resolver.resolve(cls(Kind.PACKAGE_DOES_NOT_EXIST), "javax.validation", null, ctx);
        assertThat(first).isPresent();
        // Simulate the merge by writing the patched content back to disk.
        for (com.walpro.autobuilder.agent.evolution.model.FileMutation m : first.get().mutations()) {
            writeOutput(m.path(), m.content());
        }

        Optional<FixProposal> second = resolver.resolve(cls(Kind.PACKAGE_DOES_NOT_EXIST), "javax.validation", null, ctx);
        assertThat(second).isEmpty();
    }

    /** T4-AN-7: builds a pattern-path Classification from a Kind (category derived via defaultCategoryFor). */
    private static Classification cls(Kind kind) {
        return new Classification(kind, List.of());
    }
}
