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

    private static final String POM = """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>org.ink</groupId><artifactId>rows</artifactId><version>1</version>
                <properties><maven.compiler.release>21</maven.compiler.release></properties>
                <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>
                <version>5.10.2</version><scope>test</scope></dependency></dependencies>
                <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
                <version>3.12.1</version></plugin><plugin><groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version></plugin></plugins></build></project>""";

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
        Files.writeString(project.resolve("pom.xml"), POM);
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
    /**
     * receiver-questions run 2026-10-02_14-55-46 hung 945 s: a JDK list of the project's records, rendered by its
     * own toString(), called the records' toString() -- a method entry the trace suspended inside its own call.
     */
    @Test
    void aJdkValueHoldingTheProjectsObjectsIsRenderedWithoutHanging(@TempDir Path project) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("execution.trace.live"), "live: -Dexecution.trace.live=true");
        Files.writeString(project.resolve("pom.xml"), POM);
        Path main = Files.createDirectories(project.resolve("src/main/java/org/ink"));
        Path test = Files.createDirectories(project.resolve("src/test/java/org/ink"));
        Files.writeString(main.resolve("Nibs.java"), """
                package org.ink;
                class Nibs {
                  record Nib(String name) {}
                  static java.util.List<Nib> all() { return java.util.List.of(new Nib("fine"), new Nib("broad")); }
                }""");
        Files.writeString(test.resolve("NibsTest.java"), """
                package org.ink;
                class NibsTest {
                  @org.junit.jupiter.api.Test void three() { org.junit.jupiter.api.Assertions.assertEquals(3, Nibs.all().size()); }
                }""");
        long t0 = System.currentTimeMillis();

        var calls = ExecutionTrace.trace(project, "org.ink.NibsTest", "three", 120_000).orElseThrow();

        assertThat(System.currentTimeMillis() - t0).isLessThan(120_000);
        assertThat(ExecutionTrace.render(calls, Map.of(), 10)).contains("Nibs.all() -> ").contains("fine");
    }

    /**
     * parameterized-rows field24: the method under test caught a library's exception and returned empty; the
     * trace, filtered to exceptions thrown in project code, showed only the empty result. A caught exception is
     * part of the call that caught it, wherever it was thrown -- and that call returned, it did not throw.
     */
    @Test
    void anExceptionAProjectMethodCaughtIsShownOnThatCall(@TempDir Path project) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("execution.trace.live"), "live: -Dexecution.trace.live=true");
        Files.writeString(project.resolve("pom.xml"), POM);
        Path main = Files.createDirectories(project.resolve("src/main/java/org/ink"));
        Path test = Files.createDirectories(project.resolve("src/test/java/org/ink"));
        Files.writeString(main.resolve("Seconds.java"), """
                package org.ink;
                class Seconds {
                  static int of(String text) {
                    try {
                      return Integer.parseInt(text);
                    } catch (NumberFormatException e) {
                      return -1;
                    }
                  }
                }""");
        Files.writeString(test.resolve("SecondsTest.java"), """
                package org.ink;
                class SecondsTest {
                  @org.junit.jupiter.api.Test void nine() { org.junit.jupiter.api.Assertions.assertEquals(9, Seconds.of(" 9")); }
                }""");

        var calls = ExecutionTrace.trace(project, "org.ink.SecondsTest", "nine", 120_000).orElseThrow();

        assertThat(ExecutionTrace.render(calls, Map.of(), 10))
                .contains("Seconds.of(text = \" 9\") -> -1  after it caught java.lang.NumberFormatException at line 6 (thrown in java.lang.Integer): For input string: \" 9\"")
                .doesNotContain("threw");
    }

    /**
     * ladder-rows field32: a request per type stopped the trace from seeing any call once the tree had about twenty
     * types and the test ran a mocking library -- 250 s, nothing recorded, the fact the fix needed never shown.
     */
    @Test
    void aTreeOfManyTypesWithAMockedTestIsStillTraced(@TempDir Path project) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("execution.trace.live"), "live: -Dexecution.trace.live=true");
        Files.writeString(project.resolve("pom.xml"), POM.replace("</dependencies>",
                "<dependency><groupId>org.mockito</groupId><artifactId>mockito-core</artifactId><version>5.7.0</version>"
                        + "<scope>test</scope></dependency>"
                        // Autobuilder's own Byte Buddy, so the fixture resolves offline from the local repository
                        + "<dependency><groupId>net.bytebuddy</groupId><artifactId>byte-buddy</artifactId><version>1.14.16</version><scope>test</scope></dependency>"
                        + "<dependency><groupId>net.bytebuddy</groupId><artifactId>byte-buddy-agent</artifactId><version>1.14.16</version><scope>test</scope></dependency>"
                        + "</dependencies>"));
        Path main = Files.createDirectories(project.resolve("src/main/java/org/ink"));
        Path test = Files.createDirectories(project.resolve("src/test/java/org/ink"));
        for (int i = 0; i < 24; i++) {
            Files.writeString(main.resolve("Part" + i + ".java"), "package org.ink; public class Part" + i + " { public int n() { return " + i + "; } }");
        }
        Files.writeString(main.resolve("Gate.java"), """
                package org.ink;
                public class Gate {
                  private final Part0 part;
                  public Gate(Part0 part) { this.part = part; }
                  public String blocker() { return "failing: " + part.n(); }
                }""");
        Files.writeString(test.resolve("GateTest.java"), """
                package org.ink;
                class GateTest {
                  @org.junit.jupiter.api.Test void blocks() {
                    Part0 part = org.mockito.Mockito.mock(Part0.class);
                    org.mockito.Mockito.when(part.n()).thenReturn(7);
                    org.junit.jupiter.api.Assertions.assertEquals("ran with: 7", new Gate(part).blocker());
                  }
                }""");

        var calls = ExecutionTrace.trace(project, "org.ink.GateTest", "blocks", 200_000).orElseThrow();

        assertThat(ExecutionTrace.render(calls, Map.of(), 20)).contains("Gate.blocker() -> \"failing: 7\"");
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

    /** T6-T: every failing test the evidence names, once each, in its order -- not only the first (ladder-grind field47). */
    @Test
    void everyFailingTestIsReadFromTheEvidence_onceEach() {
        var tests = StuckLayer.failingTestsIn(List.of(
                "RowsSpecTest.dries(String, String, int)[2] -- ran with: green | Nib | 4 -- expected: \"green\" but was: null",
                "RowsSpecTest.dries(String, String, int)[3] -- ran with: blue | Nib | 4 -- expected: \"blue\" but was: null",
                "no test here",
                "InkwellTest.pours -- boom"));
        assertThat(tests).extracting(t -> t.testClass() + "." + t.method()).containsExactly("RowsSpecTest.dries", "InkwellTest.pours");
        assertThat(StuckLayer.failingTestsIn(null)).isEmpty();
    }

    /** T6-T: with no trace measured yet, one at a time; never fewer than one, never more than the tests. */
    @Test
    void howManyTracesRunAtOnce_isOneUntilATraceIsMeasured() {
        org.junit.jupiter.api.Assumptions.assumeFalse(TraceCapacity.known(), "a trace was measured earlier in this JVM");
        assertThat(TraceCapacity.parallel(5)).isEqualTo(1);
        assertThat(TraceCapacity.parallel(1)).isEqualTo(1);
    }

    /** T6-T: two failing tests of one tree traced side by side, each in its own copy and on its own port. */
    @Test
    void twoFailingTestsAreTracedAtOnce(@TempDir Path project) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("execution.trace.live"), "live: -Dexecution.trace.live=true");
        Files.writeString(project.resolve("pom.xml"), POM);
        Path main = Files.createDirectories(project.resolve("src/main/java/org/ink"));
        Path test = Files.createDirectories(project.resolve("src/test/java/org/ink"));
        Files.writeString(main.resolve("Rows.java"), SOURCES.get("src/main/java/org/ink/Rows.java"));
        Files.writeString(test.resolve("RowsTest.java"), """
                package org.ink;
                class RowsTest {
                  @org.junit.jupiter.api.Test void finds() { org.junit.jupiter.api.Assertions.assertEquals("Quill", Rows.of("org.ink.Quill")); }
                  @org.junit.jupiter.api.Test void findsNib() { org.junit.jupiter.api.Assertions.assertEquals("Nib", Rows.of("org.ink.Nib")); }
                }""");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> TraceCapacity.measured(() -> ExecutionTrace.trace(project, "org.ink.RowsTest", "finds", 240_000)));
            var b = pool.submit(() -> TraceCapacity.measured(() -> ExecutionTrace.trace(project, "org.ink.RowsTest", "findsNib", 240_000)));
            assertThat(ExecutionTrace.render(a.get().orElseThrow(), SOURCES, 10)).contains("Rows.of(name = \"org.ink.Quill\") -> null");
            assertThat(ExecutionTrace.render(b.get().orElseThrow(), SOURCES, 10)).contains("Rows.of(name = \"org.ink.Nib\") -> null");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * T6-T: several traces that do not fit one prompt are shown as the traces that do, each whole -- field48 run 1 left
     * four out whole nine times, so the model saw none.
     */
    @Test
    void tracesThatDoNotAllFit_areShownAsTheOnesThatDo_eachWhole() {
        String one = "The failing test NibTest.widens was run again under the debugger. It failed with: boom\n" + "    Nib.widen() -> 1\n".repeat(40);
        String two = one.replace("NibTest.widens", "InkTest.pours");
        String three = one.replace("NibTest.widens", "QuillTest.dries");
        String answer = String.join("\n\n", one, two, three);
        int each = com.walpro.autobuilder.protocol.PromptTokenBudget.estimateTokens(one);

        String fit = StuckLayer.tracesThatFit(answer, each * 2 + 200);

        assertThat(fit).contains("NibTest.widens", "InkTest.pours").doesNotContain("QuillTest.dries")
                .contains("1 more traced test(s) left out: no room in this prompt");
        assertThat(fit.split("Nib.widen\\(\\) -> 1", -1)).as("each shown trace is whole").hasSize(81);
        assertThat(StuckLayer.tracesThatFit(answer, 10)).as("not even one fits").isNull();
        assertThat(StuckLayer.tracesThatFit(one, 100_000)).as("a single trace is the caller's case").isNull();
    }
}
