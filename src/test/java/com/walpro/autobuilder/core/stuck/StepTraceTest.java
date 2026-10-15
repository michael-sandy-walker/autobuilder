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
 * T6-T: the method whose empty, false or null answer decided a failing test, walked line by line. Which methods are
 * chosen and how a walk reads are pinned here; the walk under the debugger is a live test, run with
 * {@code -Dexecution.trace.live=true} (it forks a Maven build under JDI, about a minute).
 */
class StepTraceTest {

    private static final String POM = """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>org.ink</groupId><artifactId>shelf</artifactId><version>1</version>
                <properties><maven.compiler.release>21</maven.compiler.release></properties>
                <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>
                <version>5.10.2</version><scope>test</scope></dependency></dependencies>
                <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
                <version>3.12.1</version></plugin><plugin><groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version></plugin></plugins></build></project>""";

    private static final Map<String, String> SOURCES = Map.of("src/main/java/org/ink/Shelf.java", String.join("\n",
            "package org.ink;",
            "import java.util.*;",
            "class Shelf {",
            "  static Optional<String> pick(java.nio.file.Path dir, List<String> names) {",
            "    int seen = 0;",
            "    List<String> kept = names.stream().filter(n -> n.endsWith(\".xml\")).toList();",
            "    for (String n : kept) seen++;",
            "    if (seen == 0) return Optional.empty();",
            "    return Optional.of(kept.get(0));",
            "  }",
            "}"));

    @Test
    void anAnswerOfNothingIsNullFalseOrEmpty() {
        assertThat(StepTrace.nothing("null")).isTrue();
        assertThat(StepTrace.nothing("false")).isTrue();
        assertThat(StepTrace.nothing("\"\"")).isTrue();
        assertThat(StepTrace.nothing("Optional(Optional.empty)")).isTrue();
        assertThat(StepTrace.nothing("ListN([])")).isTrue();
        assertThat(StepTrace.nothing("HashMap({})")).isTrue();
        assertThat(StepTrace.nothing("true")).isFalse();
        assertThat(StepTrace.nothing("\"Quill\"")).isFalse();
        assertThat(StepTrace.nothing("ListN([\"a\"])")).isFalse();
        assertThat(StepTrace.nothing("void")).isFalse();
        assertThat(StepTrace.nothing(null)).isFalse();
    }

    @Test
    void theDecidingMethodsAreTheNothingAnswersOfTheLastTopLevelCallInTheOrderTheyReturned() {
        List<ExecutionTrace.Call> calls = List.of(
                // an earlier top-level call: its empty answers did not decide the failure
                new ExecutionTrace.Call(1, "org.ink.Shelf", "early", List.of(), "null", false, 3, List.of()),
                new ExecutionTrace.Call(0, "org.ink.Gate", "setUp", List.of(), "void", false, 4, List.of()),
                // the last top-level call
                new ExecutionTrace.Call(2, "org.ink.Shelf", "pick", List.of(), "Optional(Optional.empty)", false, 8, List.of()),
                new ExecutionTrace.Call(2, "org.ink.Shelf", "<init>", List.of(), "null", false, 1, List.of()),
                new ExecutionTrace.Call(2, "org.ink.Shelf", "size", List.of(), "3", false, 9, List.of()),
                new ExecutionTrace.Call(2, "org.ink.Shelf", "pick", List.of(), "Optional(Optional.empty)", false, 8, List.of()),
                new ExecutionTrace.Call(1, "org.ink.Gate", "fresh", List.of(), "false", false, 12, List.of()),
                new ExecutionTrace.Call(0, "org.ink.Gate", "verify", List.of(), "\"blocked\"", false, 20, List.of()));

        assertThat(StepTrace.decidingMethods(calls)).extracting(t -> t[0] + "#" + t[1])
                .containsExactly("org.ink.Shelf#pick", "org.ink.Gate#fresh");
    }

    @Test
    void aWalkShowsEachLineItsSourceAndWhatTheLineChanged() {
        var walk = new StepTrace.Walk("org.ink.Shelf", "pick", List.of("dir = UnixPath(/r)", "names = ListN([\"InkTest.txt\"])"),
                "Optional(Optional.empty)", List.of(
                new StepTrace.Step(0, "pick", 5, List.of("seen = 0")),
                new StepTrace.Step(0, "pick", 6, List.of("kept = ListN([])")),
                new StepTrace.Step(0, "pick", 7, List.of()),
                new StepTrace.Step(0, "pick", 8, List.of())), false);

        String text = StepTrace.render(List.of(walk), SOURCES, 20);

        assertThat(text)
                .contains("Inside Shelf.pick(dir = UnixPath(/r), names = ListN([\"InkTest.txt\"])) -> Optional(Optional.empty), line by line")
                .contains("line 6: List<String> kept = names.stream().filter(n -> n.endsWith(\".xml\")).toList();   then kept = ListN([])")
                .contains("line 8: if (seen == 0) return Optional.empty();");
        assertThat(StepTrace.render(List.of(walk), SOURCES, 3)).contains("(more lines not shown)").doesNotContain("line 8");
    }

    @Test
    void aLineOpeningALambdaShowsTheBodyTheWalkSteppedOver() {
        Map<String, String> sources = Map.of("src/main/java/org/ink/Pick.java", String.join("\n",
                "package org.ink;",
                "class Pick {",
                "  static java.util.List<String> xml(java.util.List<String> names) {",
                "    java.util.List<String> kept = names.stream().filter(n -> {",
                "      return n.startsWith(\"TEST-\") && n.endsWith(\".xml\");",
                "    }).toList();",
                "    return kept;",
                "  }",
                "}"));
        var walk = new StepTrace.Walk("org.ink.Pick", "xml", List.of("names = ListN([\"InkTest.txt\"])"), "ListN([])", List.of(
                new StepTrace.Step(0, "xml", 4, List.of()),
                new StepTrace.Step(0, "xml", 6, List.of("kept = ListN([])")),
                new StepTrace.Step(0, "xml", 7, List.of())), false);

        assertThat(StepTrace.render(List.of(walk), sources, 20))
                .contains("line 4: java.util.List<String> kept = names.stream().filter(n -> { return n.startsWith(\"TEST-\") && n.endsWith(\".xml\");\n")
                .contains("line 6: }).toList();   then kept = ListN([])");
    }

    @Test
    void aLiveDecidingMethodIsWalkedWithEveryValueItsLinesChanged(@TempDir Path project) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("execution.trace.live"), "live: -Dexecution.trace.live=true");
        Files.writeString(project.resolve("pom.xml"), POM);
        Path main = Files.createDirectories(project.resolve("src/main/java/org/ink"));
        Path test = Files.createDirectories(project.resolve("src/test/java/org/ink"));
        Files.writeString(main.resolve("Shelf.java"), SOURCES.get("src/main/java/org/ink/Shelf.java"));
        Files.writeString(test.resolve("ShelfTest.java"), """
                package org.ink;
                class ShelfTest {
                  @org.junit.jupiter.api.Test void picks() {
                    org.junit.jupiter.api.Assertions.assertTrue(
                        Shelf.pick(java.nio.file.Path.of("/r"), java.util.List.of("InkTest.txt")).isPresent());
                  }
                }""");

        var calls = ExecutionTrace.trace(project, "org.ink.ShelfTest", "picks", 240_000).orElseThrow();
        var deciding = StepTrace.decidingMethods(calls);
        assertThat(deciding).extracting(t -> t[1]).containsExactly("pick");
        var walks = StepTrace.walk(project, "org.ink.ShelfTest", "picks", deciding, 240_000).orElseThrow();

        // the first argument is an object: every value after it was lost while the frame was read one value at a time
        assertThat(StepTrace.render(walks, SOURCES, 20))
                .contains("line 5: int seen = 0;   then seen = 0")
                .containsPattern("line 6: .*then kept = [\\w.]*\\(\\[\\]\\)")
                .contains("line 8: if (seen == 0) return Optional.empty();");
    }
}
