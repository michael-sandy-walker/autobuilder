package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.core.build.BuildExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1-GN against a REAL Maven build: the bench tells a test that passed, one that failed and one that does not compile
 * apart, with the compiler's own words for the last. The unit tests pin the reading of hand-written output; this is the
 * output Maven actually writes. Off unless {@code -Dt1gn.live=true} (a Maven build inside the suite's, about 20 s,
 * and the project's test dependencies from the local repository).
 */
@EnabledIfSystemProperty(named = "t1gn.live", matches = "true")
class BuildBenchLiveTest {

    @TempDir
    Path tree;

    @Test
    void aPassingAFailingAndANonCompilingTestEndDifferently() throws Exception {
        // A build known to work offline here: the labelmaker toy case's module (JUnit 5 and AssertJ).
        Files.copy(Path.of("data/labelmaker/bootstrap/module/pom.xml"), tree.resolve("pom.xml"));
        Path main = tree.resolve("src/main/java/org/labelmaker/label/Dial.java");
        Files.createDirectories(main.getParent());
        Files.writeString(main, "package org.labelmaker.label;\npublic class Dial { public static int twice(int n) { return 2 * n; } }\n");
        String head = "package org.labelmaker.label;\nimport org.junit.jupiter.api.Test;\nimport static org.junit.jupiter.api.Assertions.*;\n";
        Map<String, String> tests = new LinkedHashMap<>();
        tests.put("src/test/java/org/labelmaker/label/HoldsTest.java", head + "class HoldsTest { @Test void t() { assertEquals(4, Dial.twice(2)); } }\n");
        tests.put("src/test/java/org/labelmaker/label/ContradictedTest.java", head + "class ContradictedTest { @Test void t() { assertEquals(5, Dial.twice(2)); } }\n");
        tests.put("src/test/java/org/labelmaker/label/BrokenTest.java", head + "class BrokenTest { @Test void t() { assertEquals(4, Dial.thrice(2)); } }\n");

        var bench = new DerivedTests.BuildBench(new BuildExecutor(true, "mvn -q -o compile", 300_000, ""), "mvn -o test");
        var out = bench.outcomes(tree, tests);
        System.out.println("T1GN " + out);

        assertThat(out.get("src/test/java/org/labelmaker/label/HoldsTest.java").kind()).isEqualTo(DerivedTests.Outcome.Kind.PASSED);
        assertThat(out.get("src/test/java/org/labelmaker/label/ContradictedTest.java").kind()).isEqualTo(DerivedTests.Outcome.Kind.FAILED);
        var broken = out.get("src/test/java/org/labelmaker/label/BrokenTest.java");
        assertThat(broken.kind()).isEqualTo(DerivedTests.Outcome.Kind.DID_NOT_COMPILE);
        assertThat(broken.detail()).startsWith("BrokenTest.java").contains("cannot find symbol");
    }
}
