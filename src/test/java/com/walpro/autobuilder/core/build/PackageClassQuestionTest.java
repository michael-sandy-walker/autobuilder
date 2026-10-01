package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.build.inject.PackageDiscoveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-DY: a class looked for in a named package -- "cannot find symbol class X in package P" -- is answered from
 * what the classpath declares: where X really is, or, when nowhere, which of P's classes look like it.
 * Invented coordinates (a quill library), the shapes from parameterized-rows stage 1.
 */
class PackageClassQuestionTest {

    private static final String PKG = "org.quill.ink";
    private static final Set<String> IN_PKG = Set.of(PKG + ".DryInkNib", PKG + ".WetInkNib", PKG + ".InkNib",
            PKG + ".BlotPad", PKG + ".SteelNibHolder");

    /** A classpath that holds IN_PKG and, elsewhere, org.quill.Cap. */
    private static final StuckQuestions.TypeAuthority CLASSPATH = new StuckQuestions.TypeAuthority() {
        @Override
        public Optional<Class<?>> resolve(String name, Map<String, String> sources) {
            return Optional.empty();
        }

        @Override
        public Set<String> classesNamed(String simple) {
            return simple.equals("Cap") ? Set.of("org.quill.Cap") : Set.of();
        }

        @Override
        public Set<String> classesIn(String packageName) {
            return packageName.equals(PKG) ? IN_PKG : Set.of();
        }
    };

    private static final Map<String, String> SOURCES = Map.of("src/main/java/org/quill/Pen.java", "package org.quill;\nclass Pen {}");

    private static Optional<StuckQuestions.Answer> ask(String cls) {
        return StuckQuestions.answer("the build fails: Pen.java: cannot find symbol class " + cls + " in package " + PKG,
                SOURCES, CLASSPATH);
    }

    @Test
    void aRealClassInTheWrongPackageIsAnsweredWithWhereItIs() {
        var a = ask("Cap").orElseThrow();
        assertThat(a.kind()).isEqualTo(StuckQuestions.Kind.PACKAGE_CLASS);
        assertThat(a.declared()).containsExactly("org.quill.Cap");
        assertThat(a.meant()).isNull();
    }

    @Test
    void anInventedClassIsAnsweredWithThePackagesClassesThatLookLikeIt() {
        var a = ask("SoftInkNib").orElseThrow();
        assertThat(a.declared()).as("the longest ending it shares: *InkNib")
                .containsExactly(PKG + ".DryInkNib", PKG + ".InkNib", PKG + ".WetInkNib");
        assertThat(a.meant()).isEqualTo("nowhere");

        assertThat(ask("SteelPad").orElseThrow().declared()).as("same first and last word first, then the ending")
                .containsExactly(PKG + ".BlotPad");
        assertThat(ask("WetSponge")).as("nothing alike: no answer, never a guess").isEmpty();
    }

    @Test
    void theRuleOrdersFirstAndLastWordBeforeTheEnding() {
        List<String> alike = StuckQuestions.endingLike("DryValueNib",
                List.of("p.DryInkNib", "p.WetValueNib", "p.ValueNib", "p.Other"));
        assertThat(alike).containsExactly("p.DryInkNib", "p.ValueNib", "p.WetValueNib");
    }

    @Test
    void aJarsPackageIsListedWithoutItsSubpackagesOrNestedTypes(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("ink.jar");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
            for (String e : List.of("org/quill/ink/InkNib.class", "org/quill/ink/InkNib$Tip.class",
                    "org/quill/ink/deep/Well.class", "org/quill/ink/package-info.class", "org/quill/Cap.class")) {
                out.putNextEntry(new JarEntry(e));
                out.closeEntry();
            }
        }
        assertThat(PackageDiscoveryService.scanJarsForPackage(List.of(jar.toString()), PKG))
                .containsExactly(PKG + ".InkNib");
    }
}
