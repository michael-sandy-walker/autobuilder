package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T6-T, skeleton first: the specification's missing surface stubbed from the compiler's own errors. Invented
 * coordinates; the shape is ladder-grind's, whose specification compiled from such stubs in four passes (41/51 on
 * stubs alone).
 */
class SkeletonStubberTest {

    /** The build tool prints each error with its symbol and location lines, twice; each is read once. */
    @Test
    void theCompilersErrorsAreReadWithTheirSymbolLocationAndFoundLines() {
        String out = """
                [ERROR] COMPILATION ERROR :
                [ERROR] /p/src/test/java/org/ink/QuillTest.java:[12,9] cannot find symbol
                [ERROR]   symbol:   method pour(java.lang.String,int)
                [ERROR]   location: variable well of type org.ink.Inkwell
                [ERROR] /p/src/test/java/org/ink/QuillTest.java:[14,20] cannot find symbol
                [ERROR]   symbol:   class Nib
                [ERROR]   location: class org.ink.QuillTest
                [ERROR] /p/src/test/java/org/ink/QuillTest.java:[16,5] constructor Blot in class org.ink.Blot cannot be applied to given types;
                [ERROR]   required: no arguments
                [ERROR]   found:    java.lang.String
                [ERROR] Failed to execute goal ... Compilation failure:
                [ERROR] /p/src/test/java/org/ink/QuillTest.java:[12,9] cannot find symbol
                [ERROR]   symbol:   method pour(java.lang.String,int)
                [ERROR]   location: variable well of type org.ink.Inkwell
                """;

        List<SkeletonStubber.CompileError> errs = SkeletonStubber.errors(out);

        assertThat(errs).hasSize(3);
        assertThat(errs.get(0).symbolKind()).isEqualTo("method");
        assertThat(errs.get(0).symbol()).isEqualTo("pour(java.lang.String,int)");
        assertThat(errs.get(0).location()).isEqualTo("org.ink.Inkwell");
        assertThat(errs.get(1).symbol()).isEqualTo("Nib");
        assertThat(errs.get(1).locationKind()).isEqualTo("class");
        assertThat(errs.get(2).found()).isEqualTo("java.lang.String");
        assertThat(SkeletonStubber.params("pour(java.lang.String,int)")).isEqualTo("java.lang.String a0, int a1");
        assertThat(SkeletonStubber.argTypes("of(java.util.Map<java.lang.String,java.lang.Integer>,<nulltype>)"))
                .containsExactly("java.util.Map<java.lang.String,java.lang.Integer>", "Object");
    }

    /** Only the loop's own main sources are stubbed: never a test, never a protected path. */
    @Test
    void onlyWritableMainSourcesAreStubbed() {
        SkeletonFirst first = new SkeletonFirst();
        assertThat(first.writable("src/main/java/org/ink/Nib.java")).isTrue();
        assertThat(first.writable("src/test/java/org/ink/NibTest.java")).isFalse();
        assertThat(first.writable("pom.xml")).isFalse();
    }

    /** The work item for a stubbed class names the test classes that use it. */
    @Test
    void theTestsThatUseAStubbedClassAreFoundByName(@TempDir Path project) throws Exception {
        Path test = Files.createDirectories(project.resolve("src/test/java/org/ink"));
        Files.writeString(test.resolve("NibTest.java"), "class NibTest { Nib n; }");
        Files.writeString(test.resolve("NibbleTest.java"), "class NibbleTest { Nibble n; }");
        Files.writeString(test.resolve("InkTest.java"), "class InkTest { }");

        assertThat(SkeletonFirst.testsUsing(project, "Nib")).containsExactly("NibTest");
    }

    /**
     * Live: a real compiler and the real assertion library on the project's classpath. The test needs a class
     * called statically, a method returning a list it asserts on, and a method returning text it assigns -- all
     * stubbed until it compiles.
     */
    @Test
    void aSpecificationCompilesFromItsStubs(@TempDir Path project) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("skeleton.live"), "live: -Dskeleton.live=true");
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>org.ink</groupId><artifactId>quill</artifactId><version>1</version>
                <properties><maven.compiler.release>21</maven.compiler.release></properties>
                <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>
                <version>5.10.2</version><scope>test</scope></dependency>
                <dependency><groupId>org.assertj</groupId><artifactId>assertj-core</artifactId>
                <version>%s</version><scope>test</scope></dependency></dependencies>
                <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
                <version>3.12.1</version></plugin></plugins></build></project>""".formatted(System.getProperty("skeleton.assertj", "3.25.3")));
        Path main = Files.createDirectories(project.resolve("src/main/java/org/ink"));
        Files.writeString(main.resolve("Inkwell.java"), "package org.ink;\npublic class Inkwell {\n}\n");
        Path test = Files.createDirectories(project.resolve("src/test/java/org/ink"));
        Files.writeString(test.resolve("QuillTest.java"), """
                package org.ink;
                import static org.assertj.core.api.Assertions.assertThat;
                class QuillTest {
                  @org.junit.jupiter.api.Test void writes() {
                    assertThat(Quill.strokes("ab")).containsExactly("a", "b");
                    String colour = new Inkwell().colour();
                    assertThat(colour).isEqualTo("blue");
                  }
                }""");
        String cp = new ProjectClasspathService().resolveClasspath(project, 180_000);

        SkeletonStubber.Result r = SkeletonStubber.stub(project, p -> p.startsWith("src/main/"), cp, 300_000);

        assertThat(r.compiles()).as("remaining: %s", r.remaining()).isTrue();
        assertThat(Files.readString(main.resolve("Quill.java")))
                .contains("public static java.util.List<String> strokes(java.lang.String a0)", "\"skeleton\"");
        assertThat(Files.readString(main.resolve("Inkwell.java"))).contains("public java.lang.String colour()");
        assertThat(Files.exists(test.resolve("Quill.java"))).isFalse();
    }
}
