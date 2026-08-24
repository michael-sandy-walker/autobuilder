package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.build.ProjectClasspathService;
import com.walpro.autobuilder.core.build.inject.SymbolImportLookupService.Resolution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T4-BM: discovery answers from the project's own observations — a fake local-repository layout
 * (real jars written into a {@code @TempDir}) plus real project sources. The registry never
 * participates in these tests; every hit must carry its provenance.
 */
class PackageDiscoveryServiceTest {

    @TempDir
    Path tmp;

    private Path projectRoot;
    private ProjectClasspathService classpathService;
    private CoordinateSearchClient searchClient;
    private PackageDiscoveryService discovery;
    private final List<String> classpathEntries = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        projectRoot = tmp.resolve("project");
        Files.createDirectories(projectRoot.resolve("src/main/java/com/example"));

        classpathService = mock(ProjectClasspathService.class);
        lenient().when(classpathService.resolveClasspath(any(), anyLong()))
                .thenAnswer(inv -> String.join(File.pathSeparator, classpathEntries));

        searchClient = mock(CoordinateSearchClient.class);
        lenient().when(searchClient.findByFullyQualifiedName(anyString())).thenReturn(Optional.empty());

        discovery = new PackageDiscoveryService(classpathService, searchClient);
    }

    /** Writes a jar with the given class entries into a fake local-repository layout. */
    private Path repositoryJar(String groupId, String artifactId, String version, String... classFqns)
            throws IOException {
        Path dir = tmp.resolve("repository")
                .resolve(groupId.replace('.', '/'))
                .resolve(artifactId)
                .resolve(version);
        Files.createDirectories(dir);
        Path jar = dir.resolve(artifactId + "-" + version + ".jar");
        try (OutputStream os = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(os)) {
            for (String fqn : classFqns) {
                jos.putNextEntry(new JarEntry(fqn.replace('.', '/') + ".class"));
                jos.write(new byte[]{(byte) 0xCA, (byte) 0xFE});
                jos.closeEntry();
            }
        }
        classpathEntries.add(jar.toString());
        return jar;
    }

    private void projectSource(String relPath, String content) throws IOException {
        Path p = projectRoot.resolve(relPath);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    // ── source 1: classpath ─────────────────────────────────────────────────

    @Test
    void lookupType_onClasspath_resolvesWithTheProjectsOwnVersion() throws IOException {
        // The acceptance-1 shape: the project resolved 5.11.0 — discovery reports THAT version,
        // not any registry's frozen literal.
        repositoryJar("org.mockito", "mockito-core", "5.11.0", "org.mockito.Mockito");

        Optional<PackageDiscoveryService.Discovery> hit = discovery.lookupType("Mockito", projectRoot);

        assertThat(hit).isPresent();
        assertThat(hit.get().source()).isEqualTo("classpath");
        Resolution r = hit.get().resolution();
        assertThat(r.fqn()).isEqualTo("org.mockito.Mockito");
        assertThat(r.groupId()).isEqualTo("org.mockito");
        assertThat(r.artifactId()).isEqualTo("mockito-core");
        assertThat(r.version()).isEqualTo("5.11.0");
    }

    @Test
    void lookupType_ambiguousOnClasspath_abstainsUnlessImportsDisambiguate() throws IOException {
        repositoryJar("com.alpha", "alpha-lib", "1.0", "com.alpha.util.Result");
        repositoryJar("com.beta", "beta-lib", "2.0", "com.beta.core.Result");

        assertThat(discovery.lookupType("Result", projectRoot))
                .as("two candidates, no project observation — never guess")
                .isEmpty();

        projectSource("src/main/java/com/example/Uses.java", """
                package com.example;
                import com.beta.core.Result;
                public class Uses { Result r; }
                """);
        Optional<PackageDiscoveryService.Discovery> hit = discovery.lookupType("Result", projectRoot);
        assertThat(hit).isPresent();
        assertThat(hit.get().resolution().fqn())
                .as("the project's own import picks among classpath candidates")
                .isEqualTo("com.beta.core.Result");
        assertThat(hit.get().resolution().artifactId()).isEqualTo("beta-lib");
    }

    @Test
    void lookupType_nestedTypesAndMultiReleaseEntries_doNotCount() throws IOException {
        Path dir = tmp.resolve("repository/com/g/lib/1.0");
        Files.createDirectories(dir);
        Path jar = dir.resolve("lib-1.0.jar");
        try (OutputStream os = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(os)) {
            jos.putNextEntry(new JarEntry("com/g/Outer$Builder.class"));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("META-INF/versions/17/com/g/Builder.class"));
            jos.closeEntry();
        }
        classpathEntries.add(jar.toString());

        assertThat(discovery.lookupType("Builder", projectRoot)).isEmpty();
    }

    // ── source 0: the JDK platform ───────────────────────────────────────────

    @Test
    void lookupType_javaBaseNameShadowedByAClasspathJar_abstains() throws IOException {
        // The field defect verbatim (2026-07-08/09, four runs): java.util.Optional is missing
        // its import; the jar scan cannot see the JDK, so a transitive jar republishing the
        // simple name was the ONLY hit and won — import org.apache.el.stream.Optional plus a
        // tomcat-embed-el pom dependency, churned until an unrelated rewrite dropped it.
        // With the platform visible this is a conflict no import observation resolves → abstain
        // (registry/LLM fallbacks own the usage context).
        repositoryJar("org.apache.tomcat.embed", "tomcat-embed-el", "10.1.24",
                "org.apache.el.stream.Optional");

        assertThat(discovery.lookupType("Optional", projectRoot)).isEmpty();
    }

    @Test
    void lookupType_javaBaseUniqueWithNoUsageTestimony_abstains() {
        // Was: resolve as import-only. Field 2026-07-15 flipped the contract — with no import
        // anywhere, java.base uniqueness cannot distinguish a platform type from a domain name
        // (see the Member case below); abstain and let the registry/LLM carry it.
        assertThat(discovery.lookupType("LocalDate", projectRoot)).isEmpty();
    }

    @Test
    void lookupType_domainNameCollidingWithJavaBase_abstains() throws IOException {
        // The field defect verbatim (2026-07-15, 3 firings in one run): the library goal's own
        // Member entity is missing (referenced from MemberController, not yet created), nothing
        // on the classpath claims the name, no import exists — and java.lang.reflect.Member is
        // a java.base export. The old rule injected `import java.lang.reflect.Member`, which
        // SHADOWED same-package resolution and kept the file broken even after Member.java was
        // created; the injector re-poisoned the file after every rewrite.
        projectSource("src/main/java/com/example/library/model/MemberController.java", """
                package com.example.library.model;
                public class MemberController {
                    private Member member;
                    public String email() { return member.getEmail(); }
                }
                """);

        assertThat(discovery.lookupType("Member", projectRoot)).isEmpty();
    }

    @Test
    void lookupType_projectImportPicksTheJdkOverAShadowingJar() throws IOException {
        repositoryJar("org.apache.tomcat.embed", "tomcat-embed-el", "10.1.24",
                "org.apache.el.stream.Optional");
        projectSource("src/main/java/com/example/Uses.java", """
                package com.example;
                import java.util.Optional;
                public class Uses { Optional<String> o; }
                """);

        Optional<PackageDiscoveryService.Discovery> hit = discovery.lookupType("Optional", projectRoot);

        assertThat(hit).isPresent();
        assertThat(hit.get().source()).isEqualTo("jdk-platform");
        assertThat(hit.get().resolution().fqn()).isEqualTo("java.util.Optional");
        assertThat(hit.get().resolution().hasGav()).isFalse();
    }

    @Test
    void lookupType_projectImportPicksAClasspathJarOverTheJdk() throws IOException {
        // Explicit project intent beats the platform: the import IS the project's answer.
        repositoryJar("org.apache.tomcat.embed", "tomcat-embed-el", "10.1.24",
                "org.apache.el.stream.Optional");
        projectSource("src/main/java/com/example/Uses.java", """
                package com.example;
                import org.apache.el.stream.Optional;
                public class Uses { Optional o; }
                """);

        Optional<PackageDiscoveryService.Discovery> hit = discovery.lookupType("Optional", projectRoot);

        assertThat(hit).isPresent();
        assertThat(hit.get().source()).isEqualTo("classpath");
        assertThat(hit.get().resolution().fqn()).isEqualTo("org.apache.el.stream.Optional");
        assertThat(hit.get().resolution().artifactId()).isEqualTo("tomcat-embed-el");
    }

    @Test
    void lookupType_classpathNameCollidingWithJavaBase_abstainsToTheFallbackChain() throws IOException {
        // Pattern: jakarta.validation.constraints.Pattern on the classpath vs
        // java.util.regex.Pattern in java.base. Field runs show the registry answering this
        // correctly when discovery abstains — only usage context (annotation vs regex) can
        // decide, and this source doesn't have it.
        repositoryJar("jakarta.validation", "jakarta.validation-api", "3.0.2",
                "jakarta.validation.constraints.Pattern");

        assertThat(discovery.lookupType("Pattern", projectRoot)).isEmpty();
    }

    @Test
    void lookupType_nonBaseModuleNames_doNotBlockAUniqueClasspathHit() throws IOException {
        // Entity collides with org.w3c.dom.Entity (java.xml module, NOT java.base) — no field
        // damage observed there, and blocking would regress measured-good classpath hits.
        repositoryJar("jakarta.persistence", "jakarta.persistence-api", "3.1.0",
                "jakarta.persistence.Entity");

        Optional<PackageDiscoveryService.Discovery> hit = discovery.lookupType("Entity", projectRoot);

        assertThat(hit).isPresent();
        assertThat(hit.get().source()).isEqualTo("classpath");
        assertThat(hit.get().resolution().fqn()).isEqualTo("jakarta.persistence.Entity");
    }

    // ── usage testimony: annotation form ─────────────────────────────────────

    @Test
    void lookupType_annotationUsageWithANonAnnotationClasspathHit_abstains() throws IOException {
        // The field defect verbatim (2026-07-16 20:41, corpus 2026-07-16_20-08-35): the code uses
        // @Param on repository method parameters; spring-data is not on the classpath (in-memory
        // goal), so the ONLY classpath claimant of the bare name is the JSP tag POJO
        // org.springframework.web.servlet.tags.Param — injected, then failed the re-probe with
        // "incompatible types: ... cannot be converted to java.lang.annotation.Annotation".
        // Unanimous @-form usage demands an annotation type; a POJO candidate is a wrong answer.
        repositoryJar("org.springframework", "spring-webmvc", "6.1.8",
                "org.springframework.web.servlet.tags.Param");
        projectSource("src/main/java/com/example/library/model/LoanRepository.java", """
                package com.example.library.model;
                import java.util.List;
                public class LoanRepository {
                    public List<String> findByMemberId(@Param("memberId") Long memberId) { return List.of(); }
                }
                """);

        assertThat(discovery.lookupType("Param", projectRoot)).isEmpty();
    }

    @Test
    void lookupType_annotationUsageWithAConfirmedAnnotationType_resolves() throws IOException {
        // Same testimony, right candidate: the jar entry's class bytes carry ACC_ANNOTATION
        // (real annotation bytes borrowed from the test classpath), so @-form usage and the
        // candidate agree — resolve exactly as before the gate existed.
        Path dir = tmp.resolve("repository/jakarta/validation/jakarta.validation-api/3.0.2");
        Files.createDirectories(dir);
        Path jar = dir.resolve("jakarta.validation-api-3.0.2.jar");
        byte[] realAnnotationBytes;
        try (var in = org.junit.jupiter.api.Test.class
                .getResourceAsStream("/org/junit/jupiter/api/Test.class")) {
            realAnnotationBytes = in.readAllBytes();
        }
        try (OutputStream os = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(os)) {
            jos.putNextEntry(new JarEntry("jakarta/validation/constraints/NotNull.class"));
            jos.write(realAnnotationBytes);
            jos.closeEntry();
        }
        classpathEntries.add(jar.toString());
        projectSource("src/main/java/com/example/library/model/Book.java", """
                package com.example.library.model;
                public class Book {
                    @NotNull
                    private String title;
                }
                """);

        Optional<PackageDiscoveryService.Discovery> hit = discovery.lookupType("NotNull", projectRoot);

        assertThat(hit).isPresent();
        assertThat(hit.get().source()).isEqualTo("classpath");
        assertThat(hit.get().resolution().fqn()).isEqualTo("jakarta.validation.constraints.NotNull");
    }

    @Test
    void lookupType_nonAnnotationUsage_neverConsultsTheClassBytes() throws IOException {
        // OTHER (or mixed) usage keeps the pre-gate behavior — the fixture jar's junk bytes
        // would fail the annotation check, and must not matter here.
        repositoryJar("org.mockito", "mockito-core", "5.11.0", "org.mockito.Mockito");
        projectSource("src/main/java/com/example/UsesMockito.java", """
                package com.example;
                public class UsesMockito {
                    Object o = Mockito.mock(Object.class);
                }
                """);

        Optional<PackageDiscoveryService.Discovery> hit = discovery.lookupType("Mockito", projectRoot);

        assertThat(hit).isPresent();
        assertThat(hit.get().resolution().fqn()).isEqualTo("org.mockito.Mockito");
    }

    // ── source 2: project imports (+ coordinate search) ─────────────────────

    @Test
    void lookupType_fromProjectImports_coordinatesViaSearch() throws IOException {
        // The acceptance-2 shape: a symbol in NO hardcoded map resolves from the project's own
        // import plus the fc: coordinate search — the registry never grew a row.
        projectSource("src/main/java/com/example/Text.java", """
                package com.example;
                import org.apache.commons.lang3.StringUtils;
                public class Text { boolean b = StringUtils.isBlank(""); }
                """);
        when(searchClient.findByFullyQualifiedName("org.apache.commons.lang3.StringUtils"))
                .thenReturn(Optional.of(new CoordinateSearchClient.Coordinates(
                        "org.apache.commons", "commons-lang3", "3.14.0")));

        Optional<PackageDiscoveryService.Discovery> hit = discovery.lookupType("StringUtils", projectRoot);

        assertThat(hit).isPresent();
        assertThat(hit.get().source()).isEqualTo("project-imports");
        Resolution r = hit.get().resolution();
        assertThat(r.fqn()).isEqualTo("org.apache.commons.lang3.StringUtils");
        assertThat(r.groupId()).isEqualTo("org.apache.commons");
        assertThat(r.artifactId()).isEqualTo("commons-lang3");
        assertThat(r.version()).isEqualTo("3.14.0");
    }

    @Test
    void lookupType_importedButNotOnClasspathAndUnsearchable_abstains() throws IOException {
        // Run-26 field lesson: an import that is neither on the classpath nor searchable is
        // a broken observation (the importing file can't compile either) — an import-only "hit"
        // here masked the registry fallback that knew the providing artifact. Abstain.
        projectSource("src/main/java/com/example/Text.java", """
                package com.example;
                import org.apache.commons.lang3.StringUtils;
                public class Text { boolean b = StringUtils.isBlank(""); }
                """);

        assertThat(discovery.lookupType("StringUtils", projectRoot)).isEmpty();
    }

    @Test
    void lookupType_importUnderTheProjectsOwnPackage_isNotEcosystemObservation() throws IOException {
        // Run-26 field lesson: 45 junk resolutions — files imported a not-yet-created type
        // from the project's own package tree; discovery treated the aspiration as an observation
        // and emitted import fixes for a nonexistent class. A missing PROJECT type belongs to
        // the skeleton/evolution paths.
        projectSource("src/main/java/com/example/library/controller/BookController.java", """
                package com.example.library.controller;
                import com.example.library.repository.BookRepository;
                public class BookController { BookRepository repo; }
                """);
        projectSource("src/main/java/com/example/library/repository/LoanRepository.java", """
                package com.example.library.repository;
                public class LoanRepository { }
                """);

        assertThat(discovery.lookupType("BookRepository", projectRoot))
                .as("com.example.library.repository is a project-declared package")
                .isEmpty();
    }

    @Test
    void lookupType_conflictingImports_abstain() throws IOException {
        projectSource("src/main/java/com/example/A.java",
                "package com.example;\nimport com.x.Widget;\npublic class A { Widget w; }\n");
        projectSource("src/main/java/com/example/B.java",
                "package com.example;\nimport com.y.Widget;\npublic class B { Widget w; }\n");

        assertThat(discovery.lookupType("Widget", projectRoot)).isEmpty();
    }

    @Test
    void lookupType_wildcardAndStaticImports_areNotObservations() throws IOException {
        projectSource("src/main/java/com/example/C.java", """
                package com.example;
                import com.x.util.*;
                import static com.y.Assertions.assertThat;
                public class C { }
                """);

        assertThat(discovery.lookupType("Assertions", projectRoot)).isEmpty();
        assertThat(discovery.lookupType("assertThat", projectRoot)).isEmpty();
    }

    // ── packages ─────────────────────────────────────────────────────────────

    @Test
    void lookupPackage_onClasspath_reportsTheProvidingArtifact() throws IOException {
        // Failure mode 3 inverted: the project already resolved javax.validation via ITS pom —
        // discovery reports the project's coordinates, not an assumption.
        repositoryJar("javax.validation", "validation-api", "2.0.1.Final",
                "javax.validation.constraints.NotNull");

        Optional<PackageDiscoveryService.Discovery> hit =
                discovery.lookupPackage("javax.validation.constraints", projectRoot);

        assertThat(hit).isPresent();
        assertThat(hit.get().source()).isEqualTo("classpath");
        assertThat(hit.get().resolution().artifactId()).isEqualTo("validation-api");
        assertThat(hit.get().resolution().version()).isEqualTo("2.0.1.Final");
    }

    // ── T2-FA: an undeclared dependency, named by the build's own two diagnostics ─────────

    @Test
    void lookupUndeclaredPackage_composesTheFqnFromBothDiagnosticsAndSearchesIt() {
        // The field shape, run 2026-08-24_00-07-06: `package org.springframework.data.jpa.repository
        // does not exist` on the import line, `symbol: class JpaRepository` on the use. Neither
        // half is searchable alone; together they are an exact FQN.
        when(searchClient.findByFullyQualifiedName("org.springframework.data.jpa.repository.JpaRepository"))
                .thenReturn(Optional.of(new CoordinateSearchClient.Coordinates(
                        "org.springframework.boot", "spring-boot-starter-data-jpa", "3.3.2")));

        Optional<PackageDiscoveryService.Discovery> hit = discovery.lookupUndeclaredPackage(
                "org.springframework.data.jpa.repository", "JpaRepository", projectRoot);

        assertThat(hit).isPresent();
        assertThat(hit.get().source()).isEqualTo("coordinate-search");
        assertThat(hit.get().resolution().artifactId()).isEqualTo("spring-boot-starter-data-jpa");
        assertThat(hit.get().resolution().version()).isEqualTo("3.3.2");
    }

    @Test
    void lookupUndeclaredPackage_prefersTheClasspathWhenItCanAnswer() throws IOException {
        repositoryJar("jakarta.validation", "jakarta.validation-api", "3.0.2",
                "jakarta.validation.constraints.NotNull");

        Optional<PackageDiscoveryService.Discovery> hit = discovery.lookupUndeclaredPackage(
                "jakarta.validation.constraints", "NotNull", projectRoot);

        assertThat(hit).isPresent();
        assertThat(hit.get().source()).isEqualTo("classpath");
        verifyNoInteractions(searchClient);
    }

    @Test
    void lookupUndeclaredPackage_neverSearchesAPackageTheProjectDeclaresItself() throws IOException {
        // A type the loop has not written yet. No dependency can supply it, so searching for one
        // can only return something wrong.
        projectSource("src/main/java/com/example/domain/service/Existing.java", """
                package com.example.domain.service;
                public class Existing {}
                """);

        assertThat(discovery.lookupUndeclaredPackage(
                "com.example.domain.service", "LoanService", projectRoot)).isEmpty();
        verifyNoInteractions(searchClient);
    }

    @Test
    void lookupUndeclaredPackage_treatsAnUnwrittenSiblingAsTheProjectsOwn() throws IOException {
        // The package that is MISSING is by definition one the project has not written yet, so an
        // exact membership check would send an unwritten sibling's name to a public artifact index.
        projectSource("src/main/java/com/example/library/domain/Book.java", """
                package com.example.library.domain;
                public class Book {}
                """);

        assertThat(discovery.lookupUndeclaredPackage(
                "com.example.library.domain.repository", "BookRepository", projectRoot)).isEmpty();
        verifyNoInteractions(searchClient);
    }

    @Test
    void lookupUndeclaredPackage_abstainsWhenTheSearchCannotAnswer() {
        assertThat(discovery.lookupUndeclaredPackage("com.absent.pkg", "Widget", projectRoot)).isEmpty();
    }

    @Test
    void lookupUndeclaredPackage_needsATypeName_andItMustNameAType() {
        assertThat(discovery.lookupUndeclaredPackage("com.absent.pkg", null, projectRoot)).isEmpty();
        assertThat(discovery.lookupUndeclaredPackage("com.absent.pkg", "findAll", projectRoot))
                .as("a member name is not the other half of an FQN")
                .isEmpty();
        verifyNoInteractions(searchClient);
    }

    @Test
    void lookupPackage_absentEverywhere_abstains() {
        assertThat(discovery.lookupPackage("com.absent.pkg", projectRoot)).isEmpty();
    }

    // ── GAV recovery from the repository layout ──────────────────────────────

    @Test
    void gavFromRepositoryPath_parsesStandardLayout_andRejectsOthers() {
        var gav = PackageDiscoveryService.gavFromRepositoryPath(
                "/home/u/.m2/repository/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar");
        assertThat(gav).isPresent();
        assertThat(gav.get().groupId()).isEqualTo("org.apache.commons");
        assertThat(gav.get().artifactId()).isEqualTo("commons-lang3");
        assertThat(gav.get().version()).isEqualTo("3.14.0");

        assertThat(PackageDiscoveryService.gavFromRepositoryPath("/opt/libs/some.jar")).isEmpty();
        assertThat(PackageDiscoveryService.gavFromRepositoryPath(
                "/repo/a/b/1.0/mismatched-name-1.0.jar")).isEmpty();
        assertThat(PackageDiscoveryService.gavFromRepositoryPath(null)).isEmpty();
    }
}
