package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T6-T: the classes a build reports missing and the classpath provides are imported together. The jars are
 * written by the test with invented coordinates -- an "inkwell" library, a "ledger" library -- so the rule is
 * shown to follow the file's own imports and no library's name.
 */
class ClasspathTypeImportsResolverTest {

    @TempDir
    Path tmp;

    private Path output;
    private String inkwellJar;
    private String ledgerJar;
    private final FileService fileService = mock(FileService.class);
    private final PackageDiscoveryService discovery = mock(PackageDiscoveryService.class);
    private ClasspathTypeImportsResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        output = Files.createDirectories(tmp.resolve("output"));
        when(fileService.resolveSecurePath("output")).thenReturn(output);
        inkwellJar = jar("inkwell.jar", "org/inkwell/ast/Nib.class", "org/inkwell/ast/Blot.class",
                "org/inkwell/ast/expr/Stroke.class", "org/inkwell/ast/expr/Flourish.class",
                "org/inkwell/print/Blot.class");
        ledgerJar = jar("ledger.jar", "org/ledger/Blot.class", "org/ledger/Entry.class");
        resolver = new ClasspathTypeImportsResolver(fileService, discovery);
    }

    private String jar(String name, String... entries) throws IOException {
        Path jar = tmp.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String entry : entries) {
                out.putNextEntry(new JarEntry(entry));
                out.closeEntry();
            }
        }
        return jar.toString();
    }

    private Path source(String rel, String content) throws IOException {
        Path p = output.resolve(rel);
        Files.createDirectories(p.getParent());
        return Files.writeString(p, content);
    }

    private void onClasspath(String simpleName, Map<String, String> fqnToJar) {
        when(discovery.classpathTypes(eq(simpleName), any())).thenReturn(fqnToJar);
    }

    private static String missing(Path file, int line, String type) {
        return "[ERROR] " + file.toAbsolutePath() + ":[" + line + ",31] cannot find symbol\n"
                + "  symbol:   class " + type + "\n  location: class zeta.Quill\n";
    }

    private Optional<FixProposal> resolve(String stderr) {
        return resolver.resolve(new Classification(Kind.CANNOT_FIND_SYMBOL, List.of()), null, stderr,
                CycleContextData.builder().cycleName("c-1").outputPath("output").build());
    }

    /**
     * Ladder-grind 2026-10-13_01-53-56: three library classes used and none imported; one was repaired per pass
     * and the second pass was the last. All of a candidate's missing imports are one fix.
     */
    @Test
    void everyMissingClassTheClasspathProvidesIsImportedInOneFix() throws IOException {
        Path quill = source("src/main/java/zeta/Quill.java",
                "package zeta;\n\nimport org.inkwell.ast.Nib;\n\npublic class Quill { Stroke s; Flourish f; Nib n; }\n");
        onClasspath("Stroke", Map.of("org.inkwell.ast.expr.Stroke", inkwellJar));
        onClasspath("Flourish", Map.of("org.inkwell.ast.expr.Flourish", inkwellJar));

        Optional<FixProposal> fix = resolve(missing(quill, 5, "Stroke") + missing(quill, 5, "Flourish"));

        assertThat(fix).isPresent();
        assertThat(fix.get().mutations()).hasSize(1);
        assertThat(fix.get().mutations().get(0).path()).isEqualTo("src/main/java/zeta/Quill.java");
        assertThat(fix.get().mutations().get(0).content())
                .contains("import org.inkwell.ast.expr.Stroke;", "import org.inkwell.ast.expr.Flourish;",
                        "import org.inkwell.ast.Nib;");
        assertThat(fix.get().record().rule()).isEqualTo("classpath-type-imports");
    }

    /**
     * Same run: thirteen classes on the classpath were called Node, so none was imported and an empty class of
     * that name was created in the project. The file says which is meant: the one in the jar its imports
     * already come from, and of two in that jar the one nearest to those imports.
     */
    @Test
    void ofSeveralClassesWithTheName_theOneTheFilesOwnImportsPointAtIsChosen() throws IOException {
        Path quill = source("src/main/java/zeta/Quill.java",
                "package zeta;\n\nimport org.inkwell.ast.Nib;\n\npublic class Quill { Blot b; Nib n; }\n");
        Map<String, String> blots = new LinkedHashMap<>();
        blots.put("org.ledger.Blot", ledgerJar);
        blots.put("org.inkwell.print.Blot", inkwellJar);
        blots.put("org.inkwell.ast.Blot", inkwellJar);
        onClasspath("Blot", blots);

        Optional<FixProposal> fix = resolve(missing(quill, 5, "Blot"));

        assertThat(fix).isPresent();
        assertThat(fix.get().mutations().get(0).content()).contains("import org.inkwell.ast.Blot;")
                .doesNotContain("org.ledger.Blot", "org.inkwell.print.Blot");
    }

    @Test
    void whenTheFilesImportsSingleOutNoCandidate_nothingIsImported() throws IOException {
        Path quill = source("src/main/java/zeta/Quill.java", "package zeta;\n\npublic class Quill { Blot b; }\n");
        Map<String, String> blots = new LinkedHashMap<>();
        blots.put("org.ledger.Blot", ledgerJar);
        blots.put("org.inkwell.ast.Blot", inkwellJar);
        onClasspath("Blot", blots);

        assertThat(resolve(missing(quill, 3, "Blot"))).isEmpty();
    }

    /** A class the project declares itself is the project's, whatever a jar calls its own. */
    @Test
    void aNameTheProjectDeclaresIsNotImportedFromAJar() throws IOException {
        Path quill = source("src/main/java/zeta/Quill.java", "package zeta;\n\npublic class Quill { Entry e; }\n");
        source("src/main/java/zeta/books/Entry.java", "package zeta.books;\npublic class Entry {}\n");
        onClasspath("Entry", Map.of("org.ledger.Entry", ledgerJar));

        assertThat(resolve(missing(quill, 3, "Entry"))).isEmpty();
    }

    @Test
    void aMissingMethodOrAFileOutsideTheProjectIsNotThisRulesBusiness() throws IOException {
        Path quill = source("src/main/java/zeta/Quill.java", "package zeta;\n\npublic class Quill { }\n");
        String method = "[ERROR] " + quill.toAbsolutePath() + ":[3,10] cannot find symbol\n"
                + "  symbol:   method drain()\n  location: class zeta.Quill\n";
        onClasspath("Stroke", Map.of("org.inkwell.ast.expr.Stroke", inkwellJar));

        assertThat(resolve(method)).isEmpty();
        assertThat(resolve(missing(tmp.resolve("elsewhere/Other.java"), 3, "Stroke"))).isEmpty();
    }
}
