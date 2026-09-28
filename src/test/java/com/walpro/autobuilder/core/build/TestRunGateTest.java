package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

        assertThat(blocker).contains("dries[2] (ran with: green, Map<String,Nib>, a long note")
                .as("a long row does not crowd out what the code returned (field run 2026-09-28_15-38-46)")
                .contains("): java.lang.AssertionError: Expecting Optional to contain a value but it was empty.");
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

    @Test
    void noResult_timeoutAndDisabled_returnEmpty() {
        stubOutcome(BuildResult.TIMEOUT, "");
        assertThat(gate(true).verifyTestsPass("c")).isEmpty();

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
}
