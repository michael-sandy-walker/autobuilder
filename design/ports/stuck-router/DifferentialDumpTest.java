package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Port review instrument (design/ports/stuck-router): asks this module's StuckQuestions every archived
 * signature and writes one line per signature -- kind, type, member, meant, declared -- so versions of the
 * class can be compared on inputs beyond their specification. Copied into a module copy by
 * differential.sh; never part of any suite.
 */
class DifferentialDumpTest {

    private static Map<String, String> tree(String project) throws Exception {
        if (project.equals("none")) return Map.of("src/main/java/org/ink/Quill.java", "package org.ink;\npublic record Quill(String nib) {}\n");
        // Probe trees from the stuck-questions assessment (2026-09-27), one shape each.
        if (project.equals("two-pkgs")) return new java.util.LinkedHashMap<>(Map.of(
                "src/main/java/a/x/Foo.java", "package a.x;\npublic record Foo(int n) {}\n",
                "src/main/java/b/y/Foo.java", "package b.y;\npublic record Foo(String s) {}\n"));
        if (project.equals("generic-box")) return Map.of("src/main/java/a/Box.java",
                "package a;\npublic class Box<T> {\n  public void put(T t) {}\n  public void put(T t, int i) {}\n}\n");
        if (project.equals("iface")) return Map.of("src/main/java/a/Shape.java", "package a;\npublic interface Shape {\n  void m(int x);\n}\n");
        if (project.equals("overloads-vis")) return Map.of("src/main/java/a/Foo.java",
                "package a;\npublic class Foo {\n  void bar(int a) {}\n  public void bar(String... s) {}\n}\n");
        if (project.equals("explicit-record")) return Map.of("src/main/java/a/P.java",
                "package a;\npublic record P(int x) {\n  public P(int x) { this.x = x; }\n}\n");
        if (project.equals("main-test-dup")) return new java.util.LinkedHashMap<>(Map.of(
                "src/main/java/a/Foo.java", "package a;\npublic class Foo {\n  public int baz() { return 1; }\n}\n",
                "src/test/java/a/Foo.java", "package a;\npublic class Foo {\n  public int qux() { return 2; }\n}\n"));
        // A project that imports java.util.List: the import is what says which List it means.
        if (project.equals("imports-util")) return Map.of(
                "src/main/java/org/ink/Well.java", "package org.ink;\nimport java.util.List;\npublic class Well { List<String> drops; }\n");
        // A project importing dependency classes whose simple names java.lang / the JDK also declare.
        if (project.equals("imports-foreign")) return Map.of(
                "src/main/java/org/ink/Wiring.java", "package org.ink;\nimport com.google.inject.Module;\nimport org.apache.commons.codec.binary.Base64;\npublic class Wiring { Module m; Base64 b; }\n");
        // A project class whose simple name the JDK also declares (java.util.Timer): the project's own answers first.
        if (project.equals("shadow")) return Map.of(
                "src/main/java/org/ink/Timer.java", "package org.ink;\npublic class Timer {\n  public void fire() {}\n  public int ticks() { return 0; }\n}\n",
                "src/main/java/org/ink/Clock.java", "package org.ink;\npublic record Clock(Timer t, Base64 code) {}\n",
                "src/main/java/org/ink/Base64.java", "package org.ink;\npublic class Base64 {\n  public String encode(String s) { return s; }\n}\n");
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(DifferentialDumpTest.class.getResourceAsStream("/stuck/trees.zip"))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (!e.isDirectory() && e.getName().startsWith(project + "/")) {
                    files.put(e.getName().substring(project.length() + 1), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return files;
    }

    @Test
    void dump() throws Exception {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(System.getProperty("diff.in")))) {
            if (line.startsWith("#") || line.isBlank()) continue;
            String[] p = line.split("\t", 3);
            String answer;
            try {
                answer = StuckQuestions.answer(p[2], tree(p[1]))
                        .map(a -> a.kind() + " | " + a.type() + " | " + a.member() + " | meant=" + a.meant() + " | " + a.declared())
                        .orElse("(no answer)");
            } catch (RuntimeException e) {
                answer = "(threw " + e.getClass().getSimpleName() + ")";
            }
            out.add(p[1] + "\t" + p[2] + "\t" + answer);
        }
        Files.write(Path.of(System.getProperty("diff.out")), out);
    }
}
