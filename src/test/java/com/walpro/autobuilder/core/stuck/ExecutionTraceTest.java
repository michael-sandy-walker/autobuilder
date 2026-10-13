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

    /**
     * T6-T, ladder-grind 2026-10-13_01-53-56: a test called one method twelve times; the forty lines held the first
     * four rounds, and neither fact the failure turned on was shown -- that from the second round on a call the
     * first round had made was no longer made, and what the object's count stood at after the last. Rounds that
     * made the same calls are shown once and counted, with what differed; a round that made other calls starts a
     * new group, so the difference is on the page.
     */
    @Test
    void aTestThatDoesOneThingManyTimes_isShownOnceAndCounted_withWhatDifferedAndWhatTheObjectHeldAtTheEnd() {
        List<ExecutionTrace.Call> calls = new java.util.ArrayList<>();
        calls.add(new ExecutionTrace.Call(1, "org.ink.Tally", "due", List.of("count = 1"), "false", false, 7, List.of()));
        calls.add(new ExecutionTrace.Call(0, "org.ink.Tally", "note", List.of("round = \"round-1\"", "what = \"blot\""), "void",
                false, 5, List.of(), List.of("seen={blot=1}", "thisRound=[blot]")));
        for (int r = 2; r <= 12; r++) {
            calls.add(new ExecutionTrace.Call(0, "org.ink.Tally", "note", List.of("round = \"round-" + r + "\"", "what = \"blot\""),
                    "void", false, 5, List.of(), List.of("seen={blot=1}", "thisRound=[blot]")));
        }
        // A round is often two calls of the test's own -- it builds what it hands over: the same holds then.
        List<ExecutionTrace.Call> built = new java.util.ArrayList<>();
        for (int r = 1; r <= 5; r++) {
            built.add(new ExecutionTrace.Call(0, "org.ink.Blot", "<init>", List.of("size = 2"), "void", false, 3, List.of(),
                    List.of("size=2")));
            built.add(new ExecutionTrace.Call(0, "org.ink.Tally", "note", List.of("round = \"round-" + r + "\""), "void", false, 5,
                    List.of(), List.of("seen={blot=1}")));
        }
        assertThat(ExecutionTrace.render(built, Map.of(), 40))
                .contains("... the same 2 calls 4 more times", "Tally.note, round: \"round-1\", \"round-2\"",
                        "after the last of them, Tally holds: seen={blot=1}")
                .as("what a constructor leaves is its arguments again").doesNotContain("Blot holds");

        String out = ExecutionTrace.render(calls, Map.of(), 40);

        assertThat(out).as("the first round, which asked whether a note was due").contains("Tally.due(count = 1) -> false")
                .as("the rounds after it made one call fewer: a group of their own, shown once")
                .contains("Tally.note(round = \"round-2\", what = \"blot\") -> void")
                .contains("... the same 1 call 10 more times")
                .as("what differed between them, in order -- and not what stayed the same")
                .contains("Tally.note, round: \"round-2\", \"round-3\", \"round-4\", ... , \"round-12\" (11 in all)")
                .doesNotContain("Tally.note, what:")
                .as("what the object held after the last round").contains("after the last of them, Tally holds: seen={blot=1}, thisRound=[blot]")
                .doesNotContain("round-7\", what").doesNotContain("more call(s)");
        assertThat(out.lines().count()).isLessThan(10);
    }

    @Test
    void aCallMadeOnceShowsWhatItsObjectHeldAfterIt_andCallsThatDifferAreNotFolded() {
        List<ExecutionTrace.Call> calls = List.of(
                new ExecutionTrace.Call(0, "org.ink.Tally", "note", List.of("what = \"blot\""), "void", false, 5, List.of(),
                        List.of("seen={blot=1}")),
                new ExecutionTrace.Call(0, "org.ink.Tally", "total", List.of(), "1", false, 9, List.of(), List.of("seen={blot=1}")));

        String out = ExecutionTrace.render(calls, Map.of(), 40);

        assertThat(out).contains("Tally.note(what = \"blot\") -> void", "Tally.total() -> 1",
                "after it, Tally holds: seen={blot=1}").doesNotContain("the same");
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
}
