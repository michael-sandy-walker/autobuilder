package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-DY, the user's decision 2026-10-01: the dependency API a change uses, read from the project's jars and shown
 * before the model writes. An invented library (org.quillfixture) packed into a jar laid out as a local repository.
 */
class DependencyApiTest {

    @TempDir
    static Path tmp;
    static String jar;
    static ClassLoader loader;

    @BeforeAll
    static void packTheLibrary() throws Exception {
        Path classes = Path.of(DependencyApiTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path j = tmp.resolve("repository/org/quillfixture/quill-ink/1.4/quill-ink-1.4.jar");
        Files.createDirectories(j.getParent());
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(j.toFile()))) {
            for (String c : List.of("org/quillfixture/ink/InkNib.class", "org/quillfixture/ink/DryInkNib.class",
                    "org/quillfixture/pad/BlotPad.class")) {
                out.putNextEntry(new JarEntry(c));
                out.write(Files.readAllBytes(classes.resolve(c)));
                out.closeEntry();
            }
        }
        jar = j.toString();
        loader = new URLClassLoader(new java.net.URL[]{j.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }

    private static final String ATTEMPT = """
            package org.sample;
            import org.quillfixture.ink.InkNib;
            import org.quillfixture.ink.SoftInkNib;
            import java.util.List;
            class Pen { }
            """;

    @Test
    void anImportedPackageIsListed_anInventedClassIsShownAbsent_andAnImportedClassesMembersFollow() {
        String block = DependencyApi.block(List.of(ATTEMPT), "", List.of(jar), loader, 4_000);

        assertThat(block).contains("package org.quillfixture.ink declares: DryInkNib, InkNib",
                "(it declares no SoftInkNib)", "org.quillfixture.ink.InkNib: ", "width(): int", "widen(int): InkNib");
        assertThat(block.indexOf("package org.quillfixture.ink")).as("package lists come first")
                .isLessThan(block.indexOf("org.quillfixture.ink.InkNib: "));
        assertThat(block).as("the type-cast family and non-public members are left out")
                .doesNotContain("isInkNib", "asDryInkNib", "hidden");
        assertThat(block).as("the JDK's packages are not the dependency's").doesNotContain("java.util");
    }

    @Test
    void beforeAnyImport_aLibraryTheWorkNamesIsShownByItsPackages() {
        String block = DependencyApi.block(List.of("package org.sample;\nclass Pen {}"),
                "Read the rows with the Quill library the project already depends on.", List.of(jar), loader, 4_000);

        assertThat(block).contains("quill-ink (1.4) declares the packages: org.quillfixture.ink, org.quillfixture.pad");
        assertThat(DependencyApi.block(List.of("package org.sample;\nclass Pen {}"), "Nothing named here.",
                List.of(jar), loader, 4_000)).as("no import, no name: nothing to show").isNull();
    }

    @Test
    void aSubclassShowsWhatItExtendsAndItsConstructors() {
        String block = DependencyApi.block(List.of("import org.quillfixture.ink.DryInkNib;"), "", List.of(jar), loader, 4_000);
        assertThat(block).contains("org.quillfixture.ink.DryInkNib (extends org.quillfixture.ink.InkNib): colour(): String; new DryInkNib(String)");
    }

    @Test
    void anEntryThatDoesNotFitIsLeftOutWholeAndCounted() {
        String block = DependencyApi.block(List.of(ATTEMPT), "", List.of(jar), loader, 60);
        assertThat(block).contains("package org.quillfixture.ink declares").contains("more left out: no room in this prompt")
                .doesNotContain("width(): int");
    }
}
