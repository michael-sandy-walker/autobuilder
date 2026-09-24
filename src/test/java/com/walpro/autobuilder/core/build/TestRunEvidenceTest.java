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
                .containsExactly("expected 2 but was 3", "No qualifying bean");
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
}
