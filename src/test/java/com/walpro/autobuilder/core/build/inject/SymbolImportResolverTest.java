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
        resolver = new SymbolImportResolver(fileService, lookup, grounding);
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
}
