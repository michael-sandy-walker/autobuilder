package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T6-T, the situational skeleton: what the tests need, deduced from their syntax tree -- the types each use implies, the
 * chain the tests demand -- with no compile. Invented coordinates; the shape is ladder-grind's (GrindDetector must call
 * BehaviourLogger.grindDetected, read from verify and from where the mock is handed in).
 */
class SkeletonDeductionTest {

    @Test
    void theTestsNeedsAndTheChainAreDeducedFromTheirSyntaxTree(@TempDir Path project) throws Exception {
        Path main = Files.createDirectories(project.resolve("src/main/java/org/ink"));
        Files.writeString(main.resolve("Logger.java"), "package org.ink;\npublic class Logger {\n}\n");
        Files.writeString(main.resolve("Inkwell.java"), "package org.ink;\npublic class Inkwell {\n  public String colour() { return \"blue\"; }\n}\n");
        Path test = Files.createDirectories(project.resolve("src/test/java/org/ink"));
        Files.writeString(test.resolve("QuillTest.java"), """
                package org.ink;
                import static org.mockito.Mockito.mock;
                import static org.mockito.Mockito.verify;
                class QuillTest {
                  void writes() {
                    Logger log = mock(Logger.class);
                    Quill quill = new Quill();
                    quill.setLogger(log);
                    quill.dip(3);
                    String line = quill.write("ab");
                    int left = Quill.inkLeft(quill);
                    verify(log).noted("ab", 2);
                    String c = new Inkwell().colour();
                  }
                  record Blot(String where) {}
                  void blots() { Blot b = new Blot("x"); }
                }""");

        SkeletonDeduction.Result r = SkeletonDeduction.deduce(project, "");

        assertThat(r.needs()).extracting(SkeletonDeduction.Need::describe).contains(
                "class Quill", "Quill()", "Quill.setLogger(org.ink.Logger): void", "Quill.dip(int): void",
                "Quill.write(String): String", "static Quill.inkLeft(org.ink.Quill): int", "Logger.noted(String, int): void");
        assertThat(r.needs()).extracting(SkeletonDeduction.Need::describe)
                .as("an existing member, and a type the tests declare themselves, are no needs")
                .noneMatch(d -> d.contains("colour") || d.contains("Blot"));
        assertThat(r.links()).singleElement().extracting(SkeletonDeduction.Link::describe).asString()
                .startsWith("Quill must call Logger.noted(String, int)");
    }
}
