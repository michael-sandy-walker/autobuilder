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
    void noVerdict_timeoutAndDisabled_returnEmpty() {
        stubOutcome(BuildResult.TIMEOUT, "");
        assertThat(gate(true).verifyTestsPass("c")).isEmpty();

        assertThat(gate(false).verifyTestsPass("c")).isEmpty();
        verify(buildExecutor, never()).execute(any(), any(), eq(null));
    }
}
