package com.walpro.autobuilder.core.stuck;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The user's decision 2026-10-01 ("values too"): what a failing test's call executed, with its values. The rendering
 * and the discovery of what to trace are pinned here; the debugger run itself is a live test, run with
 * {@code -Dexecution.trace.live=true} (it forks a Maven build under JDI, about a minute).
 */
class ExecutionTraceTest {

    private static final Map<String, String> SOURCES = Map.of("src/main/java/org/ink/Rows.java", String.join("\n",
            "package org.ink;", "class Rows {", "  static String find(String name) {", "    String simple = name;",
            "    return null;", "  }", "  static String of(String name) {", "    return find(name);", "  }", "}"));

    @Test
    void aTraceShowsEachCallsArgumentsResultReturnLineAndValuesThere() {
        List<ExecutionTrace.Call> calls = List.of(
                new ExecutionTrace.Call(1, "org.ink.Rows", "find", List.of("name = \"org.ink.Quill\""), "null", false, 5,
                        List.of("simple = \"org.ink.Quill\"")),
                new ExecutionTrace.Call(0, "org.ink.Rows", "of", List.of("name = \"org.ink.Quill\""), "null", false, 8, List.of()));

        String trace = ExecutionTrace.render(calls, SOURCES, 10);

        assertThat(trace).isEqualTo(String.join("\n",
                "    Rows.find(name = \"org.ink.Quill\") -> null  [returned at line 5: return null;]  where simple = \"org.ink.Quill\"",
                "  Rows.of(name = \"org.ink.Quill\") -> null  [returned at line 8: return find(name);]"));
    }

    @Test
    void aCallThatThrewSaysSoAndALongTraceIsCountedNotCut() {
        List<ExecutionTrace.Call> calls = List.of(
                new ExecutionTrace.Call(0, "org.ink.Rows$Cell", "split", List.of(), "threw java.lang.StringIndexOutOfBoundsException at line 4",
                        true, 4, List.of()),
                new ExecutionTrace.Call(0, "org.ink.Rows", "of", List.of(), "null", false, 8, List.of()));

        String trace = ExecutionTrace.render(calls, SOURCES, 1);

        assertThat(trace).contains("Rows.Cell.split() -> threw java.lang.StringIndexOutOfBoundsException at line 4  [at line 4: String simple = name;]")
                .contains("... and 1 more call(s)").doesNotContain("Rows.of(");
    }

    @Test
    void onlyWhatTheMainSourcesDeclareIsTraced(@TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve("src/main/java/org/ink"));
        Files.createDirectories(project.resolve("src/test/java/org/ink"));
        Files.writeString(project.resolve("src/main/java/org/ink/Rows.java"), "package org.ink; class Rows {}");
        Files.writeString(project.resolve("src/main/java/org/ink/package-info.java"), "package org.ink;");
        Files.writeString(project.resolve("src/test/java/org/ink/RowsTest.java"), "package org.ink; class RowsTest {}");

        assertThat(ExecutionTrace.mainTypes(project)).containsExactly("org.ink.Rows");
        assertThat(ExecutionTrace.trace(project, "org.ink.RowsTest", "x", 1_000)).as("no pom: not a Maven project, no trace").isEmpty();
    }

    @Test
    void aLiveFailingTestIsTracedWithItsValues(@TempDir Path project) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("execution.trace.live"), "live: -Dexecution.trace.live=true");
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>org.ink</groupId><artifactId>rows</artifactId><version>1</version>
                <properties><maven.compiler.release>21</maven.compiler.release></properties>
                <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>
                <version>5.10.2</version><scope>test</scope></dependency></dependencies>
                <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
                <version>3.12.1</version></plugin><plugin><groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version></plugin></plugins></build></project>""");
        Path main = Files.createDirectories(project.resolve("src/main/java/org/ink"));
        Path test = Files.createDirectories(project.resolve("src/test/java/org/ink"));
        Files.writeString(main.resolve("Rows.java"), SOURCES.get("src/main/java/org/ink/Rows.java"));
        Files.writeString(test.resolve("RowsTest.java"), """
                package org.ink;
                class RowsTest {
                  @org.junit.jupiter.api.Test void finds() { org.junit.jupiter.api.Assertions.assertEquals("Quill", Rows.of("org.ink.Quill")); }
                }""");

        var calls = ExecutionTrace.trace(project, "org.ink.RowsTest", "finds", 240_000).orElseThrow();

        assertThat(ExecutionTrace.render(calls, SOURCES, 10))
                .contains("Rows.find(name = \"org.ink.Quill\") -> null  [returned at line 5: return null;]  where simple = \"org.ink.Quill\"")
                .contains("Rows.of(name = \"org.ink.Quill\") -> null");
    }
    /** The failing test comes from the observation's own lines, which stay right however the tree changed since. */
    @Test
    void theFailingTestIsReadFromTheObservationsEvidence() {
        var t = StuckLayer.failingTestIn(List.of(
                "RowsSpecTest.dries(String, String, int)[2] -- ran with: green | Nib | 4 -- expected: \"green\" but was: null",
                "RowsSpecTest.plain -- boom")).orElseThrow();
        assertThat(t.testClass()).isEqualTo("RowsSpecTest");
        assertThat(t.method()).isEqualTo("dries");
        assertThat(t.message()).isEqualTo("expected: \"green\" but was: null");

        assertThat(StuckLayer.failingTestIn(List.of("no test here", ""))).isEmpty();
        assertThat(StuckLayer.failingTestIn(null)).isEmpty();
    }
}
