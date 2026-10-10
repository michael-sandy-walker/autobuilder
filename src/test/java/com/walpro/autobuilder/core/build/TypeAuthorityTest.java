package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.build.StuckQuestions.Answer;
import com.walpro.autobuilder.core.build.inject.PackageDiscoveryService;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stuck router's type authority (port review 2026-09-27): which class a type name in a failure denotes.
 * Invented projects; the probes are the review packet's (Base64, Timer, List, String) plus a dependency type.
 */
class TypeAuthorityTest {

    private static final Map<String, String> SHADOW = Map.of(
            "src/main/java/org/ink/Base64.java", "package org.ink;\npublic class Base64 {\n  public String encode(String s) { return s; }\n}\n",
            "src/main/java/org/ink/Timer.java", "package org.ink;\npublic class Timer {\n  public void fire() {}\n}\n",
            "src/main/java/org/ink/Clock.java", "package org.ink;\npublic record Clock(Timer t, Base64 code) {}\n");
    private static final Map<String, String> PLAIN = Map.of("src/main/java/org/ink/Quill.java", "package org.ink;\npublic record Quill(String nib) {}\n");

    @Test
    void theProjectsOwnTypeIsNeverAnsweredWithAPlatformClassOfTheSameName() {
        assertThat(StuckQuestions.answer("the build fails: Clock.java: cannot find symbol method decode(String) in record component code of type Base64", SHADOW))
                .as("java.util.Base64 is unique in the JDK, and still not the project's Base64").isEmpty();
        assertThat(StuckQuestions.answer("the build fails: Clock.java: cannot find symbol method stop() in record component t of type Timer", SHADOW)).isEmpty();
    }

    @Test
    void aWrittenPackageIsExactAndJavaLangNeedsNoImport() {
        Answer map = StuckQuestions.answer("the build fails: Quill.java: cannot find symbol method indexOf(Quill) in variable active of type java.util.Map<java.lang.String,org.ink.Quill>", PLAIN).orElseThrow();
        assertThat(map.type()).isEqualTo("Map");
        assertThat(map.declared()).contains("get(Object)").noneMatch(d -> d.startsWith("indexOf("));
        Answer string = StuckQuestions.answer("the build fails: String has no getLength()", PLAIN).orElseThrow();
        assertThat(string.declared()).contains("length()").doesNotContain("compareTo(Object)");
    }

    @Test
    void anAmbiguousNameIsSettledByTheProjectsImportsOrNotAtAll() {
        assertThat(StuckQuestions.answer("the build fails: List has no getFirstItem()", PLAIN)).as("java.util and java.awt; no import").isEmpty();
        Map<String, String> importsUtil = Map.of("src/main/java/org/ink/Well.java",
                "package org.ink;\nimport java.util.List;\npublic class Well { List<String> drops; }\n");
        Answer list = StuckQuestions.answer("the build fails: List has no getFirstItem()", importsUtil).orElseThrow();
        assertThat(list.declared()).contains("add(Object)").noneMatch(d -> d.startsWith("addActionListener("));
    }

    @Test
    void aDependencyTypeIsAnsweredFromTheProjectsOwnClasspath() throws Exception {
        String jar = System.getProperty("user.home")
                + "/.m2/repository/com/github/javaparser/javaparser-core/3.25.7/javaparser-core-3.25.7.jar";
        org.junit.jupiter.api.Assumptions.assumeTrue(new File(jar).isFile(), "javaparser-core 3.25.7 in the local repository");
        List<String> jars = List.of(jar);
        URLClassLoader loader = new URLClassLoader(new URL[]{new File(jar).toURI().toURL()}, ClassLoader.getPlatformClassLoader());
        StuckQuestions.TypeAuthority project = (name, sources) -> StuckQuestions.resolveAgainst(name, sources, loader, simple -> {
            Set<String> c = new LinkedHashSet<>(JdkPlatformTypeIndex.exportedTypes(simple));
            c.addAll(PackageDiscoveryService.scanJarsForType(jars, simple).keySet());
            return c;
        });

        Answer a = StuckQuestions.answer("the build fails: RecordDeclaration has no getComponents()", PLAIN, project).orElseThrow();

        assertThat(a.type()).isEqualTo("RecordDeclaration");
        assertThat(a.declared()).contains("getParameters()").noneMatch(d -> d.startsWith("getComponents("));
        assertThat(StuckQuestions.answer("the build fails: RecordDeclaration has no getComponents()", PLAIN))
                .as("the JDK alone does not know a dependency type").isEmpty();
    }

    /**
     * T6-T, ladder-grind 2026-10-10_19-22-18: the answer held getParameters() among some 250 members, alphabetical and
     * without return types, and the model guessed a second wrong name. Shown for a prompt, the type's own members
     * named like the call come first, each with what it returns, and what it inherits is counted, not listed.
     */
    @Test
    void aDependencyTypesMembersAreShownArrangedForFindingOne() throws Exception {
        String jar = System.getProperty("user.home")
                + "/.m2/repository/com/github/javaparser/javaparser-core/3.25.7/javaparser-core-3.25.7.jar";
        org.junit.jupiter.api.Assumptions.assumeTrue(new File(jar).isFile(), "javaparser-core 3.25.7 in the local repository");
        List<String> jars = List.of(jar);
        URLClassLoader loader = new URLClassLoader(new URL[]{new File(jar).toURI().toURL()}, ClassLoader.getPlatformClassLoader());
        StuckQuestions.TypeAuthority project = (name, sources) -> StuckQuestions.resolveAgainst(name, sources, loader, simple -> {
            Set<String> c = new LinkedHashSet<>(JdkPlatformTypeIndex.exportedTypes(simple));
            c.addAll(PackageDiscoveryService.scanJarsForType(jars, simple).keySet());
            return c;
        });
        String signature = "the build fails: RecordDeclaration has no getRecordComponents()";

        String shown = StuckQuestions.receiverShown(signature, PLAIN, project).orElseThrow();
        String whole = String.join("; ", StuckQuestions.answer(signature, PLAIN, project).orElseThrow().declared());

        assertThat(shown).startsWith("RecordDeclaration has no getRecordComponents().");
        String namedLike = shown.substring(shown.indexOf("named like the call"), shown.indexOf("Its other own members"));
        assertThat(namedLike).contains("getParameters() : NodeList<Parameter>");
        assertThat(shown).contains("It inherits further members not listed here:").contains(" from Object")
                .doesNotContain("wait(").doesNotContain("com.github.");
        assertThat(shown.length()).as("short enough to read: a fraction of the whole list").isLessThan(whole.length() / 2);
    }

    @Test
    void aPlatformTypesMembersAreArrangedTheSameWayAndAProjectTypeIsLeftToItsSources() {
        String shown = StuckQuestions.receiverShown(
                "the build fails: Quill.java: cannot find symbol method getLength() in variable nib of type java.lang.String",
                PLAIN, StuckQuestions.TypeAuthority.platform()).orElseThrow();

        assertThat(shown).startsWith("String has no getLength().").contains("getBytes() : byte[]").contains("length() : int");
        assertThat(shown.indexOf("getBytes() : byte[]")).isLessThan(shown.indexOf("length() : int"));
        assertThat(StuckQuestions.receiverShown("the build fails: Timer has no getFire()", PLAIN,
                StuckQuestions.TypeAuthority.platform())).as("the project's own type: its sources answer").isEmpty();
        assertThat(StuckQuestions.simpleTypeName("java.util.Map<java.lang.String, java.util.List<a.b.Quill>>"))
                .isEqualTo("Map<String, List<Quill>>");
    }

    /** The independent review of the first draft: an explicit import outranks java.lang and a unique platform name. */
    @Test
    void anExplicitImportOutranksJavaLangAndAUniquePlatformName() {
        Map<String, String> guice = Map.of("src/main/java/org/ink/Wiring.java",
                "package org.ink;\nimport com.google.inject.Module;\npublic class Wiring { Module m; }\n");
        assertThat(StuckQuestions.answer("the build fails: Module has no configure()", guice))
                .as("com.google.inject.Module is meant, and the JDK alone cannot load it").isEmpty();
        Map<String, String> codec = Map.of("src/main/java/org/ink/Seal.java",
                "package org.ink;\nimport org.apache.commons.codec.binary.Base64;\npublic class Seal { Base64 b; }\n");
        assertThat(StuckQuestions.answer("the build fails: Base64 has no decodeBase64(String)", codec)).isEmpty();
        Map<String, String> twoLists = Map.of(
                "src/main/java/org/ink/A.java", "package org.ink;\nimport java.util.List;\nclass A {}\n",
                "src/main/java/org/ink/B.java", "package org.ink;\nimport java.awt.List;\nclass B {}\n");
        assertThat(StuckQuestions.answer("the build fails: List has no getFirstItem()", twoLists))
                .as("two files import two different Lists: no answer").isEmpty();
    }
}
