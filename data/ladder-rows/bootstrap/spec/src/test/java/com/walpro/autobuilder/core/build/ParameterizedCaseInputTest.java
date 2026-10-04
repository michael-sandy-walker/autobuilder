package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-EA: a failing parameterized invocation is reported as {@code name(Types)[N]}; the row it ran with
 * is read from the test source, and only where the source writes the rows literally.
 */
class ParameterizedCaseInputTest {

    @TempDir
    Path root;

    private void source(String body) throws Exception {
        Path file = root.resolve("src/test/java/org/sample/quill/InkTest.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package org.sample.quill;\n" + body);
    }

    private String row(String method) {
        return ParameterizedCaseInput.of(root, "org.sample.quill.InkTest", method).orElse(null);
    }

    @Test
    void theNthInlineRowIsTheInputOfInvocationN() throws Exception {
        source("""
                class InkTest {
                    @ParameterizedTest(name = "{0}")
                    @CsvSource(delimiter = '|', value = {
                            "blue  | Map<String,Nib> | 3",
                            "green | Nib             | 4"})
                    void dries(String colour, String type, int seconds) {}
                }
                """);

        assertThat(row("dries(String, String, int)[2]")).isEqualTo("green | Nib             | 4");
        assertThat(row("dries(String, String, int)[1]")).isEqualTo("blue  | Map<String,Nib> | 3");
    }

    @Test
    void aSingleRowAndLiteralValuesAreRowsToo() throws Exception {
        source("""
                class InkTest {
                    @ParameterizedTest @CsvSource("only, row") void one(String a, String b) {}
                    @ParameterizedTest @ValueSource(ints = {7, 11}) void count(int n) {}
                }
                """);

        assertThat(row("one(String, String)[1]")).isEqualTo("only, row");
        assertThat(row("count(int)[2]")).isEqualTo("11");
    }

    @Test
    void aTextBlockHasOneRowPerLineAndCommentsAreNotRows() throws Exception {
        source("""
                class InkTest {
                    @ParameterizedTest
                    @CsvSource(textBlock = \"""
                        # colour, seconds
                        red,  2

                        black, 9
                        \""")
                    void dries(String colour, int seconds) {}
                }
                """);

        assertThat(row("dries(String, int)[2]")).isEqualTo("black, 9");
    }

    @Test
    void rowsTheSourceDoesNotWriteLiterallyAreNotGuessed() throws Exception {
        source("""
                class InkTest {
                    @ParameterizedTest @MethodSource("nibs") void fromMethod(String n) {}
                    @ParameterizedTest @NullSource @ValueSource(strings = {"a"}) void twoSources(String n) {}
                    @ParameterizedTest @CsvSource(useHeadersInDisplayName = true, value = {"h", "a"}) void header(String n) {}
                    @ParameterizedTest @ValueSource(strings = {"a"}) void short_(String n) {}
                    @Test void plain() {}
                }
                """);

        assertThat(row("fromMethod(String)[1]")).isNull();
        assertThat(row("twoSources(String)[1]")).as("a second source interleaves invocations").isNull();
        assertThat(row("header(String)[1]")).as("a header row shifts the index").isNull();
        assertThat(row("short_(String)[2]")).as("an index past the rows").isNull();
        assertThat(row("plain")).as("not an invocation").isNull();
        assertThat(row("absent(String)[1]")).isNull();
        assertThat(ParameterizedCaseInput.of(root, "org.sample.quill.GoneTest", "x(String)[1]")).isEmpty();
    }

    @Test
    void overloadsAreSeparatedByTheirParameterCount() throws Exception {
        source("""
                class InkTest {
                    @ParameterizedTest @ValueSource(strings = {"one"}) void mix(String a) {}
                    @ParameterizedTest @CsvSource({"x, y"}) void mix(String a, String b) {}
                }
                """);

        assertThat(row("mix(String)[1]")).isEqualTo("one");
        assertThat(row("mix(String, String)[1]")).isEqualTo("x, y");
    }

    @Test
    void aNestedTestClassIsReadFromItsOuterFile() throws Exception {
        source("""
                class InkTest {
                    @Nested class Wet {
                        @ParameterizedTest @ValueSource(strings = {"damp", "soaked"}) void spreads(String s) {}
                    }
                }
                """);

        assertThat(ParameterizedCaseInput.of(root, "org.sample.quill.InkTest$Wet", "spreads(String)[2]"))
                .contains("soaked");
    }
}
