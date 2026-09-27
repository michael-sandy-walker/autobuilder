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
