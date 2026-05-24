package com.walpro.autobuilder.core.build.inject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
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
        when(registry.readPomOrEmpty(org.mockito.ArgumentMatchers.anyString())).thenReturn("");
        resolver = new SymbolImportResolver(fileService, lookup, grounding, registry);
        ctx = CycleContextData.builder().cycleName("c-1").outputPath("output").build();
        Files.createDirectories(tmp.resolve("output"));
    }

    private void writeOutput(String rel, String content) throws IOException {
        Path target = tmp.resolve("output/" + rel);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    @Test
    void resolve_wrongKind_returnsEmpty() {
        assertThat(resolver.resolve(Kind.MISSING_POM, "X", ctx)).isEmpty();
        // PACKAGE_DOES_NOT_EXIST is now handled (T4-BG) — but the default registry mock
        // returns empty, so without a registry hit the resolver still returns empty.
        assertThat(resolver.resolve(Kind.PACKAGE_DOES_NOT_EXIST, "p.q", ctx)).isEmpty();
        assertThat(resolver.resolve(Kind.UNKNOWN, null, ctx)).isEmpty();
    }

    @Test
    void resolve_nonSimpleIdentifier_returnsEmpty() {
        assertThat(resolver.resolve(Kind.CANNOT_FIND_SYMBOL, "has.a.dot", ctx)).isEmpty();
        assertThat(resolver.resolve(Kind.CANNOT_FIND_SYMBOL, " ", ctx)).isEmpty();
    }

    @Test
    void resolve_lookupReturnsEmpty_resolverReturnsEmpty() {
        when(lookup.lookup("Foo")).thenReturn(Optional.empty());

        assertThat(resolver.resolve(Kind.CANNOT_FIND_SYMBOL, "Foo", ctx)).isEmpty();
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

        Optional<FixProposal> out = resolver.resolve(Kind.CANNOT_FIND_SYMBOL, "NotBlank", ctx);

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

        Optional<FixProposal> out = resolver.resolve(Kind.CANNOT_FIND_SYMBOL, "RestController", ctx);

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

        Optional<FixProposal> out = resolver.resolve(Kind.CANNOT_FIND_SYMBOL, "NotBlank", ctx);

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

        Optional<FixProposal> out = resolver.resolve(Kind.CANNOT_FIND_SYMBOL, "WebMvcTest", ctx);

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

        Optional<FixProposal> out = resolver.resolve(Kind.CANNOT_FIND_SYMBOL, "BookRepository", ctx);

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

        assertThat(resolver.resolve(Kind.CANNOT_FIND_SYMBOL, "Foo", ctx)).isEmpty();
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
        when(registry.replacedBy(org.mockito.ArgumentMatchers.eq("javax.validation"), org.mockito.ArgumentMatchers.any()))
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

        Optional<FixProposal> out = resolver.resolve(Kind.PACKAGE_DOES_NOT_EXIST, "javax.validation", ctx);

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
        when(registry.replacedBy(org.mockito.ArgumentMatchers.eq("javax.validation"), org.mockito.ArgumentMatchers.any()))
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

        Optional<FixProposal> out = resolver.resolve(Kind.PACKAGE_DOES_NOT_EXIST, "javax.validation", ctx);

        assertThat(out).isEmpty();
    }

    @Test
    void packageReplace_multipleSourceFiles_allRewrittenInOneProposal() throws IOException {
        when(registry.lookup(org.mockito.ArgumentMatchers.eq("javax.validation"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(new WellKnownPackageRegistry.Gav(
                        "jakarta.validation", "jakarta.validation-api", "3.0.2")));
        when(registry.replacedBy(org.mockito.ArgumentMatchers.eq("javax.validation"), org.mockito.ArgumentMatchers.any()))
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

        Optional<FixProposal> out = resolver.resolve(Kind.PACKAGE_DOES_NOT_EXIST, "javax.validation", ctx);

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
        // Pure-add case: jakarta.persistence has a GAV but no replacedBy mapping.
        when(registry.lookup(org.mockito.ArgumentMatchers.eq("jakarta.persistence"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(new WellKnownPackageRegistry.Gav(
                        "jakarta.persistence", "jakarta.persistence-api", "3.1.0")));
        when(registry.replacedBy(org.mockito.ArgumentMatchers.eq("jakarta.persistence"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        writeOutput("pom.xml", BASE_POM);
        writeOutput("src/main/java/com/example/Entity.java", """
                package com.example;
                import jakarta.persistence.Entity;
                @Entity public class Entity {}
                """);

        Optional<FixProposal> out = resolver.resolve(Kind.PACKAGE_DOES_NOT_EXIST, "jakarta.persistence", ctx);

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
        when(registry.replacedBy(org.mockito.ArgumentMatchers.eq("javax.validation"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(new WellKnownPackageRegistry.PackageReplacement(
                        "javax.validation", "jakarta.validation")));
        writeOutput("pom.xml", BASE_POM);
        writeOutput("src/main/java/com/example/E.java", """
                package com.example;
                import javax.validation.Valid;
                public class E { public void x(@Valid String s) {} }
                """);

        Optional<FixProposal> first = resolver.resolve(Kind.PACKAGE_DOES_NOT_EXIST, "javax.validation", ctx);
        assertThat(first).isPresent();
        // Simulate the merge by writing the patched content back to disk.
        for (com.walpro.autobuilder.agent.evolution.model.FileMutation m : first.get().mutations()) {
            writeOutput(m.path(), m.content());
        }

        Optional<FixProposal> second = resolver.resolve(Kind.PACKAGE_DOES_NOT_EXIST, "javax.validation", ctx);
        assertThat(second).isEmpty();
    }
}
