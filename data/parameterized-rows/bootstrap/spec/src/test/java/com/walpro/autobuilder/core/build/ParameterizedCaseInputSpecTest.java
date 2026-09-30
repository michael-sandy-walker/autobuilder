package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The specification (from Autobuilder's own tests of the change: ParameterizedCaseInputTest, and the one case
 * TestRunEvidenceTest gained with it).
 *
 * T4-EA: a failing parameterized invocation is reported as {@code name(Types)[N]}; the row it ran with
 * is read from the test source, and only where the source writes the rows literally.
 */
class ParameterizedCaseInputSpecTest {

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

    private static String report(String cls, String... cases) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?>\n<testsuite name=\"" + cls + "\">\n");
        for (String c : cases) sb.append(c).append('\n');
        return sb.append("</testsuite>\n").toString();
    }

    /** T4-EA: a failing invocation of a parameterized test is shown with the row it ran with. */
    @Test
    void aFailingParameterizedInvocationIsShownWithItsRow() throws Exception {
        long start = System.currentTimeMillis();
        Path project = root.resolve("project");
        Path test = project.resolve("src/test/java/org/sample/QuillTest.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, "package org.sample;\nclass QuillTest {\n"
                + "  @ParameterizedTest @ValueSource(strings = {\"fine\", \"broad\"}) void nibs(String n) {}\n}\n");
        Path reports = project.resolve("target/surefire-reports");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("TEST-org.sample.QuillTest.xml"), report("org.sample.QuillTest",
                "<testcase classname=\"org.sample.QuillTest\" name=\"nibs(String)[1]\"/>",
                "<testcase classname=\"org.sample.QuillTest\" name=\"nibs(String)[2]\"><failure message=\"expected: 2\"/></testcase>"));

        TestRunReport r = SurefireReports.read(reports, start - 1_000, project).orElseThrow();

        assertThat(r.failing()).extracting(TestRunReport.Failure::input).containsExactly("broad");
        assertThat(TestRunPromptBlock.render(r, List.of(), 4_000))
                .contains("QuillTest.nibs(String)[2] -- ran with: broad -- failed: expected: 2");
        assertThat(SurefireReports.read(reports, start - 1_000).orElseThrow().failing().get(0).input())
                .as("no project root, no row").isNull();
    }
}
