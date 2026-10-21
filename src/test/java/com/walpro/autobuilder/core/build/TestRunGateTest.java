package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-AN-2: the test-run gate executes the project's own tests once at the completion edge.
 * Fixture shapes mirror the 2026-06-12 18:00 fulfilled artifact, whose 4 test files compiled
 * clean and failed 7+ tests at runtime — invisible to every compile-level mechanism.
 */
class TestRunGateTest {

    private final BuildExecutor buildExecutor = mock(BuildExecutor.class);

    private TestRunGate gate(boolean enabled) {
        TestRunGate g = new TestRunGate(enabled, "mvn test", buildExecutor);
        g.setFileBasePath("/tmp/project");
        return g;
    }

    private void stubOutcome(BuildResult result, String tail) {
        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(eq("/tmp/project"), any(), eq("mvn test")))
                .thenReturn(new BuildOutcome(result, tail, 2000L, "mvn test", "c"));
    }

    @Test
    void failingSuite_returnsBlockerNamingFailedTests_andEmitsAttemptEvent() {
        BehaviourLogger behaviourLogger = mock(BehaviourLogger.class);
        stubOutcome(BuildResult.COMPILE_ERROR, """
                Tests run: 5, Failures: 5, Errors: 0, Skipped: 0, Time elapsed: 0.005 sec <<< FAILURE!
                com.example.library.model.MemberServiceTest.testCreateMemberWithUniqueEmail()  Time elapsed: 0.003 sec  <<< FAILURE!
                com.example.library.model.MemberServiceTest.testGetMemberById()  Time elapsed: 0 sec  <<< FAILURE!
                Tests run: 7, Failures: 7, Errors: 0
                """);
        TestRunGate g = gate(true);
        g.setBehaviourLogger(behaviourLogger);

        Optional<String> blocker = g.verifyTestsPass("cycle-3");

        assertThat(blocker).isPresent();
        assertThat(blocker.get())
                .contains("test suite failing")
                .contains("Tests run: 7, Failures: 7")
                .contains("MemberServiceTest.testCreateMemberWithUniqueEmail");
        verify(behaviourLogger).buildFixAttempt(eq("cycle-3"), eq("test-gate"), eq(0), eq(null),
                eq(null), eq(null), eq(List.of()), eq("COMPILE_ERROR"), eq(2000L));
    }

    @Test
    void failingSuite_severityTaggedLines_stillNameTheFailedTests() {
        // Run 6 (2026-07-03): the runner prefixes failure lines with a severity tag; the gate
        // injected a bare tally ("Errors: 4") with no test names and the model churned blind.
        stubOutcome(BuildResult.COMPILE_ERROR, """
                [ERROR] com.example.library.model.MemberControllerTest.testCreateMemberWithValidData -- Time elapsed: 0.016 s <<< ERROR!
                [ERROR] Tests run: 4, Failures: 0, Errors: 4, Skipped: 0
                """);

        Optional<String> blocker = gate(true).verifyTestsPass("c");

        assertThat(blocker).isPresent();
        assertThat(blocker.get()).contains("MemberControllerTest.testCreateMemberWithValidData");
    }

    @Test
    void failingSuite_deepestReportedCause_appendedToBlocker(@org.junit.jupiter.api.io.TempDir java.nio.file.Path base)
            throws java.io.IOException {
        // Run 6's repairable fact ('No qualifying bean of type BookRepository') lived only in the
        // per-test reports — the blocker must carry each failure chain's deepest cause, deduped.
        java.nio.file.Path reports = base.resolve("output/target/surefire-reports");
        java.nio.file.Files.createDirectories(reports);
        String trace = """
                -------------------------------------------------------------------------------
                Test set: com.example.library.model.BooksControllerTest
                -------------------------------------------------------------------------------
                java.lang.IllegalStateException: Failed to load ApplicationContext
                Caused by: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'bookController'
                Caused by: org.springframework.beans.factory.NoSuchBeanDefinitionException: No qualifying bean of type 'com.example.library.model.BookRepository' available
                """;
        java.nio.file.Files.writeString(reports.resolve("com.example.library.model.BooksControllerTest.txt"), trace);
        java.nio.file.Files.writeString(reports.resolve("com.example.library.model.MemberControllerTest.txt"), trace);

        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(eq(base.toString()), any(), eq("mvn test")))
                .thenReturn(new BuildOutcome(BuildResult.COMPILE_ERROR,
                        "[ERROR] Tests run: 4, Failures: 0, Errors: 4, Skipped: 0", 2000L, "mvn test", "c"));
        TestRunGate g = new TestRunGate(true, "mvn test", buildExecutor);
        g.setFileBasePath(base.toString());

        Optional<String> blocker = g.verifyTestsPass("c");

        assertThat(blocker).isPresent();
        assertThat(blocker.get())
                .contains("root cause(s):")
                .contains("No qualifying bean of type 'com.example.library.model.BookRepository'");
        // Deduped across the two report files: the cause appears once.
        assertThat(blocker.get().split("No qualifying bean", -1)).hasSize(2);
        // The deepest cause, not the intermediate wrapper, is what gets surfaced.
        assertThat(blocker.get()).doesNotContain("bookController");
    }

    @Test
    void failingSuite_assertionMessage_carriesTheObservedValue(@org.junit.jupiter.api.io.TempDir java.nio.file.Path base)
            throws java.io.IOException {
        // T4-CQ structdiff run 3: an assertion failure has no 'Caused by:', so the observed value
        // ('but was: <...>') never reached the repair item and six tests were repaired blind.
        java.nio.file.Path reports = base.resolve("output/target/surefire-reports");
        java.nio.file.Files.createDirectories(reports);
        java.nio.file.Files.writeString(reports.resolve("com.example.widget.WidgetParserTest.txt"), """
                -------------------------------------------------------------------------------
                Test set: com.example.widget.WidgetParserTest
                -------------------------------------------------------------------------------
                Tests run: 2, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 0.1 s <<< FAILURE! -- in com.example.widget.WidgetParserTest
                com.example.widget.WidgetParserTest.parseNamesTheRoot -- Time elapsed: 0.02 s <<< FAILURE!
                org.opentest4j.AssertionFailedError: expected: <Gadget> but was: <shop/Gadget.src>
                \tat org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
                com.example.widget.WidgetParserTest.parseCountsParts -- Time elapsed: 0.01 s <<< FAILURE!
                org.opentest4j.AssertionFailedError: expected: <3> but was: <4>
                \tat org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
                """);
        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(eq(base.toString()), any(), eq("mvn test")))
                .thenReturn(new BuildOutcome(BuildResult.COMPILE_ERROR,
                        "[ERROR] Tests run: 2, Failures: 2, Errors: 0, Skipped: 0", 2000L, "mvn test", "c"));
        TestRunGate g = new TestRunGate(true, "mvn test", buildExecutor);
        g.setFileBasePath(base.toString());

        Optional<String> blocker = g.verifyTestsPass("c");

        assertThat(blocker).isPresent();
        assertThat(blocker.get())
                .contains("parseNamesTheRoot: org.opentest4j.AssertionFailedError: expected: <Gadget> but was: <shop/Gadget.src>")
                .contains("parseCountsParts: org.opentest4j.AssertionFailedError: expected: <3> but was: <4>")
                .doesNotContain("Tests run: 2, Failures: 2, Errors: 0, Skipped: 0, Time elapsed")
                .doesNotContain("AssertionFailureBuilder");
    }

    @Test
    void failingSuite_aParameterizedInvocation_carriesTheRowItRanWith(@org.junit.jupiter.api.io.TempDir java.nio.file.Path base)
            throws java.io.IOException {
        // T4-EA receiver-questions A/B: "[10] failed: Expecting Optional to contain a value" reached
        // every prompt, and the row [10] ran with -- the fact the fix turned on -- reached none.
        java.nio.file.Path test = base.resolve("output/src/test/java/org/sample/quill/InkTest.java");
        java.nio.file.Files.createDirectories(test.getParent());
        java.nio.file.Files.writeString(test, """
                package org.sample.quill;
                class InkTest {
                    @ParameterizedTest @CsvSource({"blue, Nib", "green, Map<String,Nib>, %s"})
                    void dries(String colour, String type, String note) {}
                }
                """.formatted("a long note ".repeat(30)));
        java.nio.file.Path reports = base.resolve("output/target/surefire-reports");
        java.nio.file.Files.createDirectories(reports);
        java.nio.file.Files.writeString(reports.resolve("org.sample.quill.InkTest.txt"), """
                Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.1 s <<< FAILURE! -- in org.sample.quill.InkTest
                org.sample.quill.InkTest.dries(String, String, String)[2] -- Time elapsed: 0.02 s <<< FAILURE!
                java.lang.AssertionError: Expecting Optional to contain a value but it was empty.
                """);
        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(eq(base.toString()), any(), eq("mvn test")))
                .thenReturn(new BuildOutcome(BuildResult.COMPILE_ERROR,
                        "[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0", 2000L, "mvn test", "c"));
        TestRunGate g = new TestRunGate(true, "mvn test", buildExecutor);
        g.setFileBasePath(base.toString());

        String blocker = g.verifyTestsPass("c").orElseThrow();

        assertThat(blocker).contains("dries[2] (" + ParameterizedCaseInput.LABEL + "green, Map<String,Nib>, a long note")
                .as("a long row does not crowd out what the code returned (field run 2026-09-28_15-38-46)")
                .contains("): java.lang.AssertionError: Expecting Optional to contain a value but it was empty.");
    }

    @Test
    void theSameFailingSetReadsTheSameSignatureAcrossRuns() {
        TestRunReport a = new TestRunReport(5, 2, 0, 0, List.of(
                new TestRunReport.Failure("org.ink.QuillTest", "dries(String, Map)[10]", TestRunReport.Outcome.FAILED, "expected 3"),
                new TestRunReport.Failure("org.ink.InkTest", "refusesEmptyWell", TestRunReport.Outcome.FAILED, "was 5")), Map.of());
        TestRunReport b = new TestRunReport(5, 2, 0, 0, List.of(
                new TestRunReport.Failure("org.ink.InkTest", "refusesEmptyWell", TestRunReport.Outcome.FAILED, "was 7"),
                new TestRunReport.Failure("org.ink.QuillTest", "dries(String, Map)[10]", TestRunReport.Outcome.FAILED, "expected 4")), Map.of());

        assertThat(TestRunGate.failingSignature(a)).isEqualTo(TestRunGate.failingSignature(b))
                .contains("the same tests keep failing: InkTest.refusesEmptyWell, QuillTest.dries[10]");
        assertThat(TestRunGate.failingSignature(new TestRunReport(3, 0, 0, 0, List.of(), Map.of()))).isEmpty();
    }

    @Test
    void aFailingRunIsReportedToTheStuckLayer(@org.junit.jupiter.api.io.TempDir java.nio.file.Path base) throws Exception {
        java.nio.file.Path reports = base.resolve("output/target/surefire-reports");
        java.nio.file.Files.createDirectories(reports);
        java.nio.file.Files.createDirectories(base.resolve("output/src/main/java"));
        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(eq(base.toString()), any(), eq("mvn test"))).thenAnswer(inv -> {
            Thread.sleep(50); // file times come from a coarse clock; a real test run takes seconds
            java.nio.file.Files.writeString(reports.resolve("TEST-org.ink.QuillTest.xml"),
                    "<testsuite><testcase classname=\"org.ink.QuillTest\" name=\"dries\"><failure message=\"expected 3\"/></testcase></testsuite>");
            return new BuildOutcome(BuildResult.COMPILE_ERROR,
                    "org.ink.QuillTest.dries -- Time elapsed: 0.1 s <<< FAILURE!\n[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0",
                    2000L, "mvn test", "c");
        });
        TestRunGate g = new TestRunGate(true, "mvn test", buildExecutor);
        g.setFileBasePath(base.toString());
        com.walpro.autobuilder.core.stuck.StuckLayer layer = mock(com.walpro.autobuilder.core.stuck.StuckLayer.class);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<com.walpro.autobuilder.core.stuck.StuckLayer> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(layer);
        org.springframework.test.util.ReflectionTestUtils.setField(g, "stuckLayer", provider);

        g.verifyTestsPass("c");
        verify(layer, never()).report(any(com.walpro.autobuilder.core.stuck.StuckObservation.Source.class), anyString(), any());

        // The same set, long enough over the run: reported.
        com.walpro.autobuilder.core.stuck.Recurrence longRun = new com.walpro.autobuilder.core.stuck.Recurrence(0);
        longRun.observe("the same tests keep failing: QuillTest.dries", 1);
        org.springframework.test.util.ReflectionTestUtils.setField(g, "failingSets", longRun);
        g.verifyTestsPass("c");
        verify(layer).report(eq(com.walpro.autobuilder.core.stuck.StuckObservation.Source.TEST_FAILURES),
                eq("the same tests keep failing: QuillTest.dries"), any());
    }

    @Test
    void passingSuite_returnsEmpty() {
        stubOutcome(BuildResult.COMPILE_OK, "Tests run: 7, Failures: 0, Errors: 0, Skipped: 0");
        assertThat(gate(true).verifyTestsPass("c")).isEmpty();
    }

    @Test
    void noTestsInProject_abstains() {
        // "No tests" is not a failure — smoke-test authoring is separate work.
        stubOutcome(BuildResult.COMPILE_ERROR,
                "[ERROR] No tests to run with the given includes");
        assertThat(gate(true).verifyTestsPass("c")).isEmpty();
    }

    /** T6-T: a timeout is a blocker (the suite did not finish), no longer "no result"; a disabled gate still abstains. */
    @Test
    void aTimeoutIsABlocker_aDisabledGateAbstains() {
        stubOutcome(BuildResult.TIMEOUT, "");
        assertThat(gate(true).verifyTestsPass("c")).hasValueSatisfying(b -> assertThat(b).startsWith("the suite did not finish"));

        assertThat(gate(false).verifyTestsPass("c")).isEmpty();
        verify(buildExecutor, never()).execute(any(), any(), eq(null));
    }

    // --- T4-AN-2b: unenforced-test-requirement guard ---------------------------------------- //

    @Test
    void specRequiresTests_detectsGenericTestWording_notFrameworkNames() {
        assertThat(TestRunGate.specRequiresTests("provide JUnit 5 tests for every endpoint")).isTrue();
        assertThat(TestRunGate.specRequiresTests("write a unit test for the service logic")).isTrue();
        assertThat(TestRunGate.specRequiresTests("Expose a REST API with three resources")).isFalse();
        assertThat(TestRunGate.specRequiresTests(null)).isFalse();
    }

    @Test
    void testsRequired_butNoTestSources_blocksWithoutEvenRunningTheSuite(@org.junit.jupiter.api.io.TempDir java.nio.file.Path base) throws Exception {
        // Real main code, zero test sources — the 2026-06-22 vacuous-pass shape.
        java.nio.file.Files.createDirectories(base.resolve("output/src/main/java/app"));
        java.nio.file.Files.writeString(base.resolve("output/src/main/java/app/App.java"), "package app; class App {}");
        when(buildExecutor.isEnabled()).thenReturn(true);

        TestRunGate g = new TestRunGate(true, "mvn test", buildExecutor);
        g.setFileBasePath(base.toString());

        Optional<String> blocker = g.verifyTestsPass("c", true);

        assertThat(blocker).isPresent();
        assertThat(blocker.get()).contains("requires automated tests").contains("none were authored");
        // The suite is never even invoked — the absence is decided on disk.
        verify(buildExecutor, never()).execute(any(), any(), any());
    }

    @Test
    void testsRequired_withTestSourcesPresent_delegatesToSuite(@org.junit.jupiter.api.io.TempDir java.nio.file.Path base) throws Exception {
        java.nio.file.Files.createDirectories(base.resolve("output/src/test/java/app"));
        java.nio.file.Files.writeString(base.resolve("output/src/test/java/app/AppTest.java"), "package app; class AppTest {}");
        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(eq(base.toString()), any(), eq("mvn test")))
                .thenReturn(new BuildOutcome(BuildResult.COMPILE_OK, "Tests run: 4, Failures: 0, Errors: 0", 2000L, "mvn test", "c"));

        TestRunGate g = new TestRunGate(true, "mvn test", buildExecutor);
        g.setFileBasePath(base.toString());

        // Tests exist and pass → no blocker (the guard does not fire when sources are present).
        assertThat(g.verifyTestsPass("c", true)).isEmpty();
        verify(buildExecutor).execute(eq(base.toString()), any(), eq("mvn test"));
    }

    @Test
    void testsNotRequired_noTestSources_unchangedAbstain(@org.junit.jupiter.api.io.TempDir java.nio.file.Path base) {
        // Backward compatibility: when tests are optional, no-tests still abstains (vacuous OK).
        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(eq(base.toString()), any(), eq("mvn test")))
                .thenReturn(new BuildOutcome(BuildResult.COMPILE_OK, "", 2000L, "mvn test", "c"));

        TestRunGate g = new TestRunGate(true, "mvn test", buildExecutor);
        g.setFileBasePath(base.toString());

        assertThat(g.verifyTestsPass("c", false)).isEmpty();
    }
    /**
     * T6-T: tests that do not compile leave no tally and no report -- the compiler's errors are the whole
     * fact, file relative to the project, line, message and the lines under it. Ladder-rows run
     * 2026-10-05_16-22-15 said only "test suite failing" here for an hour.
     */
    @Test
    void testsThatDoNotCompile_areReportedWithTheCompilersErrors_andKeptAsTheLastBlocker() {
        stubOutcome(BuildResult.COMPILE_ERROR, """
                [ERROR] COMPILATION ERROR :
                [ERROR] /tmp/project/output/src/test/java/zeta/InkwellTest.java:[22,16] constructor Inkwell in record zeta.Inkwell cannot be applied to given types;
                  required: java.lang.String,int,java.lang.Integer
                  found:    java.lang.String,int
                  reason: actual and formal argument lists differ in length
                [ERROR] /tmp/project/output/src/test/java/zeta/NibTest.java:[9,5] cannot find symbol
                [ERROR]   symbol:   method width(int)
                [ERROR]   location: class zeta.Nib
                [ERROR] Failed to execute goal org.example:compiler:testCompile (default-testCompile) on project quill: Compilation failure
                """);
        TestRunGate g = gate(true);

        String blocker = g.verifyTestsPass("cycle-4").orElseThrow();

        assertThat(blocker).startsWith("the tests do not compile, so none ran")
                .contains("src/test/java/zeta/InkwellTest.java:22: constructor Inkwell in record zeta.Inkwell cannot be applied"
                        + " to given types; required: java.lang.String,int,java.lang.Integer; found: java.lang.String,int;"
                        + " reason: actual and formal argument lists differ in length")
                .contains("src/test/java/zeta/NibTest.java:9: cannot find symbol; symbol: method width(int); location: class zeta.Nib")
                .doesNotContain("/tmp/project/output/").doesNotContain("test suite failing");
        assertThat(g.lastBlocker()).contains(blocker);
    }

    /** A run with a tally ran its tests: a failing suite stays a failing suite, not a compile failure. */
    @Test
    void aTallyMeansTheTestsRan_soNoCompileFailureIsReported() {
        assertThat(TestRunGate.testsDoNotCompile("Tests run: 3, Failures: 1, Errors: 0\n[ERROR] /p/A.java:[1,1] x", "/p")).isNull();
        assertThat(TestRunGate.testsDoNotCompile("[ERROR] There are test failures.", "/p")).isNull();
    }
    /** The compiler names files absolutely; a relative base path still yields project-relative files. */
    @Test
    void aRelativeBasePath_stillGivesProjectRelativeFiles() {
        String abs = java.nio.file.Path.of("rel/quill/output").toAbsolutePath().normalize().toString();
        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(eq("rel/quill"), any(), eq("mvn test"))).thenReturn(new BuildOutcome(BuildResult.COMPILE_ERROR,
                "[ERROR] " + abs + "/src/test/java/zeta/NibTest.java:[9,5] cannot find symbol\n", 2000L, "mvn test", "c"));
        TestRunGate g = new TestRunGate(true, "mvn test", buildExecutor);
        g.setFileBasePath("rel/quill");

        assertThat(g.verifyTestsPass("cycle-5").orElseThrow())
                .contains("-- src/test/java/zeta/NibTest.java:9: cannot find symbol").doesNotContain(abs);
    }
    /** T6-T: the tree's test state -- unknown, not compiling, ran -- and a candidate compared with it. */
    @Test
    void theTreesTestStateAndACandidateComparedWithIt() {
        stubOutcome(BuildResult.COMPILE_ERROR, "[ERROR] /tmp/project/output/src/test/java/zeta/NibTest.java:[9,5] cannot find symbol\n");
        TestRunGate g = gate(true);
        assertThat(g.treeTests()).isEqualTo(TestRunGate.TreeTests.UNKNOWN);
        assertThat(g.compareWithTree(report("NibTest.widens")).verdict()).isEqualTo("UNKNOWN");

        g.runOnTree("c1", false);
        assertThat(g.treeTests()).isEqualTo(TestRunGate.TreeTests.NOT_COMPILING);
        assertThat(g.compareWithTree(report("NibTest.widens", "InkwellTest.pours")).better()).isTrue();

        g.treeRan(report("NibTest.widens", "InkwellTest.pours"));
        assertThat(g.treeTests()).isEqualTo(TestRunGate.TreeTests.RAN);
        assertThat(g.compareWithTree(report("NibTest.widens")).verdict()).isEqualTo("BETTER");
        assertThat(g.compareWithTree(report("NibTest.widens", "QuillTest.dries")).verdict()).as("a trade").isEqualTo("SAME");
        assertThat(g.compareWithTree(report("QuillTest.dries")).verdict()).as("fewer, but one newly broken").isEqualTo("SAME");
        assertThat(g.compareWithTree(report("QuillTest.dries")).fewerFailing()).as("but fewer fail all the same").isTrue();
        assertThat(g.compareWithTree(report("NibTest.widens", "QuillTest.dries")).fewerFailing()).as("a trade").isFalse();
        assertThat(g.compareWithTree(report("NibTest.widens", "InkwellTest.pours", "QuillTest.dries")).verdict()).isEqualTo("WORSE");
    }

    /**
     * T6-T: tests that still do not compile, compared by the test files that do not. Ladder-grind field44: candidates
     * after which a whole specification class compiled were rejected for the rest, and no step was ever kept.
     */
    @Test
    void testsThatStillDoNotCompile_areComparedByTheFilesThatDoNot_andAMergeHandsItsFilesToTheTree() {
        String nib = "/tmp/project/output/src/test/java/zeta/NibTest.java";
        String ink = "/tmp/project/output/src/test/java/zeta/InkwellTest.java";
        String quill = "/tmp/project/output/src/test/java/zeta/QuillTest.java";
        String main = "/tmp/project/output/src/main/java/zeta/Nib.java";
        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(eq("/tmp/project"), any(), eq("mvn test"))).thenReturn(
                uncompiled(nib, ink), uncompiled(nib), uncompiled(nib, quill), uncompiled(nib, ink), uncompiled(nib, main),
                uncompiled(nib), uncompiled(nib));
        TestRunGate g = gate(true);
        assertThat(g.compareUncompiledWithTree().verdict()).as("tree unknown").isEqualTo("UNKNOWN");

        g.runOnTree("c1", false);
        g.verifyTestsPass("c2");
        TestRunGate.Delta better = g.compareUncompiledWithTree();
        assertThat(better.verdict()).isEqualTo("BETTER");
        assertThat(better.byCompilation()).isTrue();
        assertThat(better.describe()).isEqualTo("1 test file(s) that did not compile do now; 1 still do not (NibTest.java)");

        g.verifyTestsPass("c3");
        assertThat(g.compareUncompiledWithTree().verdict()).as("one compiles, another stops").isEqualTo("WORSE");
        g.verifyTestsPass("c4");
        assertThat(g.compareUncompiledWithTree().verdict()).isEqualTo("SAME");
        g.verifyTestsPass("c5");
        assertThat(g.compareUncompiledWithTree().verdict()).as("its main code does not compile").isEqualTo("UNKNOWN");

        // the better candidate merges: its files are the tree's, and the same files again are no progress
        g.verifyTestsPass("c6");
        g.candidateRan(null);
        g.candidateDidNotCompile();
        g.candidateMerged();
        assertThat(g.treeTests()).isEqualTo(TestRunGate.TreeTests.NOT_COMPILING);
        g.verifyTestsPass("c7");
        assertThat(g.compareUncompiledWithTree().verdict()).isEqualTo("SAME");
    }

    private static BuildOutcome uncompiled(String... files) {
        StringBuilder tail = new StringBuilder();
        for (String f : files) tail.append("[ERROR] ").append(f).append(":[9,5] cannot find symbol\n");
        return new BuildOutcome(BuildResult.COMPILE_ERROR, tail.toString(), 2000L, "mvn test", "c", List.of(files));
    }

    private static TestRunReport report(String... tests) {
        java.util.List<TestRunReport.Failure> f = java.util.Arrays.stream(tests).map(t -> new TestRunReport.Failure(
                "zeta." + t.substring(0, t.indexOf('.')), t.substring(t.indexOf('.') + 1), TestRunReport.Outcome.FAILED, "x")).toList();
        return new TestRunReport(9, f.size(), 0, 0, f, java.util.Map.of());
    }
    /**
     * T6-T: a run cut at the timeout is a failure that names the classes that never finished, and the classes that
     * did finish are never taken for the suite. Ladder-rows run 2026-10-06_11-50-45: "3 run, 0 failed" from the one
     * finished class confirmed a suite that hung.
     */
    @Test
    void aRunCutAtTheTimeoutIsAFailure_namingWhatNeverFinished_andItsPartialReportIsNotTheSuites(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path base) throws Exception {
        java.nio.file.Path tests = base.resolve("output/src/test/java/zeta");
        java.nio.file.Files.createDirectories(tests);
        java.nio.file.Files.writeString(tests.resolve("InkwellTest.java"), "class InkwellTest {}");
        java.nio.file.Files.writeString(tests.resolve("NibTest.java"), "class NibTest {}");
        java.nio.file.Path reports = base.resolve("output/target/surefire-reports");
        java.nio.file.Files.createDirectories(reports);
        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(eq(base.toString()), any(), eq("mvn test"))).thenAnswer(inv -> {
            java.nio.file.Path report = reports.resolve("zeta.InkwellTest.txt");
            java.nio.file.Files.writeString(report,
                    "Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.1 s -- in zeta.InkwellTest\n");
            java.nio.file.Files.setLastModifiedTime(report,
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 1_000));
            return new BuildOutcome(BuildResult.TIMEOUT, "", 120_000L, "mvn test", "c");
        });
        TestRunGate g = new TestRunGate(true, "mvn test", buildExecutor);
        g.setFileBasePath(base.toString());

        String blocker = g.verifyTestsPass("cycle-6").orElseThrow();

        assertThat(blocker).startsWith("the suite did not finish within 120 s")
                .contains("hangs or loops forever", "never finished: NibTest", "finished: InkwellTest");
        assertThat(g.lastRunTimedOut()).isTrue();
        assertThat(g.freshReport()).as("the class that finished is not the suite").isEmpty();
        assertThat(g.lastBlocker()).contains(blocker);
    }

    /**
     * T4-EC: a multi-module build writes its reports per module. ledger-import-modules run 2026-10-21_15-48-14 scored
     * 40/40 and never closed: the gate read only the root's target/surefire-reports and so never had a report of the tree.
     */
    @Test
    void aMultiModuleBuildsReportsAreReadFromEveryModule(@org.junit.jupiter.api.io.TempDir java.nio.file.Path base)
            throws Exception {
        java.nio.file.Path out = base.resolve("output");
        java.nio.file.Files.createDirectories(out);
        java.nio.file.Files.writeString(out.resolve("pom.xml"),
                "<project><packaging>pom</packaging><modules><module>wick</module><module>lamp</module></modules></project>");
        for (String m : new String[] {"wick", "lamp"}) {
            java.nio.file.Files.createDirectories(out.resolve(m + "/src/test/java/zeta"));
            java.nio.file.Files.writeString(out.resolve(m + "/src/test/java/zeta/" + m + "Test.java"), "class T {}");
            java.nio.file.Files.writeString(out.resolve(m + "/pom.xml"), "<project/>");
            java.nio.file.Files.setLastModifiedTime(out.resolve(m + "/src/test/java/zeta/" + m + "Test.java"),
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        }
        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(eq(base.toString()), any(), eq("mvn test"))).thenAnswer(inv -> {
            for (String m : new String[] {"wick", "lamp"}) {
                java.nio.file.Path reports = out.resolve(m + "/target/surefire-reports");
                java.nio.file.Files.createDirectories(reports);
                java.nio.file.Path xml = reports.resolve("TEST-zeta." + m + "Test.xml");
                java.nio.file.Files.writeString(xml, "<testsuite name=\"zeta." + m + "Test\" tests=\"2\"><testcase classname=\"zeta."
                        + m + "Test\" name=\"a\"/><testcase classname=\"zeta." + m + "Test\" name=\"b\"/></testsuite>");
                java.nio.file.Files.setLastModifiedTime(xml,
                        java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 1_000));
            }
            return new BuildOutcome(BuildResult.COMPILE_OK, "", 1000L, "mvn test", "c");
        });
        TestRunGate g = new TestRunGate(true, "mvn test", buildExecutor);
        g.setFileBasePath(base.toString());

        g.verifyTestsPass("c");

        TestRunReport report = g.freshReport().orElseThrow();
        assertThat(report.run()).isEqualTo(4);
        assertThat(report.passes()).isTrue();
        assertThat(report.byClass()).containsKeys("zeta.wickTest", "zeta.lampTest");
    }
}
