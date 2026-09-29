package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-CQ: the project's own test run as evidence for a reader -- read from its reports, bounded by the prompt. */
class TestRunEvidenceTest {

    @TempDir
    Path tmp;

    private static String report(String cls, String... cases) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?>\n<testsuite name=\"" + cls + "\">\n");
        for (String c : cases) sb.append(c).append('\n');
        return sb.append("</testsuite>\n").toString();
    }

    @Test
    void onlyReportsWrittenByThisRunCount() throws Exception {
        long start = System.currentTimeMillis();
        Files.writeString(tmp.resolve("TEST-org.sample.QuillTest.xml"), report("org.sample.QuillTest",
                "<testcase classname=\"org.sample.QuillTest\" name=\"writes\"/>",
                "<testcase classname=\"org.sample.QuillTest\" name=\"erases\"><failure message=\"expected 2 but was 3\">trace</failure></testcase>",
                "<testcase classname=\"org.sample.QuillTest\" name=\"inks\"><error message=\"No qualifying bean&#10;more\"/></testcase>",
                "<testcase classname=\"org.sample.QuillTest\" name=\"later\"><skipped/></testcase>"));
        Path removedClass = tmp.resolve("TEST-org.sample.GoneTest.xml");
        Files.writeString(removedClass, report("org.sample.GoneTest",
                "<testcase classname=\"org.sample.GoneTest\" name=\"stale\"><failure message=\"old\"/></testcase>"));
        Files.setLastModifiedTime(removedClass, FileTime.fromMillis(start - 60_000));

        TestRunReport r = SurefireReports.read(tmp, start - 1_000).orElseThrow();

        assertThat(r.run()).isEqualTo(4);
        assertThat(r.failures()).isEqualTo(1);
        assertThat(r.errors()).isEqualTo(1);
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(r.passes()).isFalse();
        assertThat(r.byClass()).as("a removed class's leftover report is not this run's").doesNotContainKey("org.sample.GoneTest");
        assertThat(r.failing()).extracting(TestRunReport.Failure::message)
                .containsExactly("expected 2 but was 3", "No qualifying bean / more");
    }

    /**
     * The whole assertion message, not its first line: AssertJ's first line is "expected: X" or an
     * assertion's description, and the observation -- what the code returned -- comes after it
     * (receiver-questions run 2026-09-26_21-17: eleven failures relayed as "expected: MEMBERS" and
     * "[archived in ...]" alone).
     */
    @Test
    void anAssertionMessageIsRelayedWholeSoItsObservedValueIsSeen() throws Exception {
        long start = System.currentTimeMillis();
        Files.writeString(tmp.resolve("TEST-org.sample.InkTest.xml"), report("org.sample.InkTest",
                "<testcase classname=\"org.sample.InkTest\" name=\"kind\"><failure message=\"&#10;expected: MEMBERS&#10; but was: RECEIVER\">trace</failure></testcase>",
                "<testcase classname=\"org.sample.InkTest\" name=\"described\"><failure message=\"[archived in run 7] &#10;Expecting ArrayList:&#10;  [&quot;a()&quot;]&#10;to contain:&#10;  [&quot;b()&quot;]\"/></testcase>",
                "<testcase classname=\"org.sample.InkTest\" name=\"textOnly\"><error>boom&#10;\tat org.sample.Ink.run(Ink.java:3)</error></testcase>"));

        TestRunReport r = SurefireReports.read(tmp, start - 1_000).orElseThrow();

        assertThat(r.failing()).extracting(TestRunReport.Failure::message).containsExactly(
                "expected: MEMBERS / but was: RECEIVER",
                "[archived in run 7] / Expecting ArrayList: / [\"a()\"] / to contain: / [\"b()\"]",
                "boom");
        assertThat(TestRunPromptBlock.render(r, List.of(), 4_000)).contains("but was: RECEIVER");
    }

    @Test
    void noReportOfThisRunIsNoEvidence() {
        assertThat(SurefireReports.read(tmp, System.currentTimeMillis() + 60_000)).isEmpty();
        assertThat(SurefireReports.read(tmp.resolve("absent"), 0)).isEmpty();
    }

    @Test
    void aSuiteOfThousandsStaysWithinTheBudgetAndSaysWhatItLeftOut() {
        Map<String, List<TestRunReport.Case>> byClass = new LinkedHashMap<>();
        List<TestRunReport.Failure> failing = new ArrayList<>();
        for (int c = 0; c < 600; c++) {
            List<TestRunReport.Case> cases = new ArrayList<>();
            for (int m = 0; m < 10; m++) cases.add(new TestRunReport.Case("case" + m, TestRunReport.Outcome.PASSED));
            byClass.put("org.sample.gear.Gear" + c + "Test", cases);
        }
        for (int i = 0; i < 400; i++) {
            failing.add(new TestRunReport.Failure("org.sample.gear.Gear" + i + "Test", "case0",
                    TestRunReport.Outcome.FAILED, "expected cog " + i));
        }
        TestRunReport r = new TestRunReport(6000, 400, 0, 0, failing, byClass);

        String block = TestRunPromptBlock.render(r,
                List.of("src/test/java/org/sample/gear/Gear7Test.java", "src/main/java/org/sample/gear/Gear.java"), 4_000);

        assertThat(block).contains("6000 run, 400 failed, 0 errors, 0 skipped -- the suite does NOT pass.");
        assertThat(block.length()).isLessThan(4_000 + 200);
        assertThat(block).containsPattern("\\.\\.\\. and \\d+ more failing, not listed");
        assertThat(block).as("classes not shown in the prompt are covered by the totals only")
                .doesNotContain("Gear8Test: ");
    }

    @Test
    void aShownTestClassCarriesItsPerTestOutcomes() {
        TestRunReport r = new TestRunReport(3, 0, 0, 0, List.of(), Map.of(
                "org.sample.QuillTest", List.of(
                        new TestRunReport.Case("writes", TestRunReport.Outcome.PASSED),
                        new TestRunReport.Case("erases", TestRunReport.Outcome.PASSED)),
                "org.sample.InkTest", List.of(new TestRunReport.Case("flows", TestRunReport.Outcome.PASSED))));

        String block = TestRunPromptBlock.render(r, List.of("src/test/java/org/sample/QuillTest.java"), 4_000);

        assertThat(block).contains("the suite PASSES").contains("QuillTest: writes passed, erases passed")
                .doesNotContain("InkTest");
    }

    /** The other common JVM build writes the same per-class format elsewhere; the gate finds it there. */
    @Test
    void theGateReadsTheOtherBuildsReportLocationToo() throws Exception {
        Path out = tmp.resolve("output");
        Files.createDirectories(out.resolve("src/test/java"));
        Path reports = Files.createDirectories(out.resolve("build/test-results/test"));
        TestRunGate gate = new TestRunGate(true, "gradle test", org.mockito.Mockito.mock(BuildExecutor.class));
        org.springframework.test.util.ReflectionTestUtils.setField(gate, "fileBasePath", tmp.toString());
        org.springframework.test.util.ReflectionTestUtils.setField(gate, "lastRunStartedAt", System.currentTimeMillis() - 5_000);
        Files.writeString(reports.resolve("TEST-org.sample.QuillTest.xml"), report("org.sample.QuillTest",
                "<testcase classname=\"org.sample.QuillTest\" name=\"writes\"/>"));

        assertThat(gate.freshReport()).get().extracting(TestRunReport::run).isEqualTo(1);
    }

    @Test
    void aSourceChangedSinceTheRunMakesItsReportStale() throws Exception {
        Path out = tmp.resolve("output");
        Path src = Files.createDirectories(out.resolve("src/test/java"));
        Path reports = Files.createDirectories(out.resolve("target/surefire-reports"));
        TestRunGate gate = new TestRunGate(true, "mvn test", org.mockito.Mockito.mock(BuildExecutor.class));
        org.springframework.test.util.ReflectionTestUtils.setField(gate, "fileBasePath", tmp.toString());
        org.springframework.test.util.ReflectionTestUtils.setField(gate, "lastRunStartedAt", System.currentTimeMillis() - 5_000);
        Files.writeString(reports.resolve("TEST-org.sample.QuillTest.xml"), report("org.sample.QuillTest",
                "<testcase classname=\"org.sample.QuillTest\" name=\"writes\"/>"));
        Files.writeString(src.resolve("QuillTest.java"), "class QuillTest {}");

        assertThat(gate.freshReport()).as("the report describes code that has since changed").isEmpty();
    }

    /** T4-EA: a failing invocation of a parameterized test is shown with the row it ran with. */
    @Test
    void aFailingParameterizedInvocationIsShownWithItsRow() throws Exception {
        long start = System.currentTimeMillis();
        Path project = tmp.resolve("project");
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
                .contains("QuillTest.nibs(String)[2] -- " + ParameterizedCaseInput.LABEL + "broad -- failed: expected: 2");
        assertThat(SurefireReports.read(reports, start - 1_000).orElseThrow().failing().get(0).input())
                .as("no project root, no row").isNull();
    }
}
