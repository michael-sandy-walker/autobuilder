package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.core.build.TestRunGate;
import com.walpro.autobuilder.core.build.TestRunReport;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.DeclaredTestSuite;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The evaluator is shown the project's own test run where the operator declared the suite an
 * authority. stuck-questions run 2026-09-26_14-24: a green suite, a criterion "the spec test passes in
 * full", and a model answering "Cannot verify test execution status from static analysis" for every
 * cycle of the run.
 */
class EvaluationTestEvidenceTest {

    private static final TestRunReport GREEN = new TestRunReport(29, 0, 0, 0, List.of(), Map.of());

    private final TestRunGate gate = mock(TestRunGate.class);
    private final DeclaredTestSuite declared = mock(DeclaredTestSuite.class);
    private final EvaluationTestEvidence evidence = new EvaluationTestEvidence(provider(gate), provider(declared));
    private final CycleContextData ctx = CycleContextData.builder().cycleName("cycle-7").build();

    @Test
    void aDeclaredSuiteWithAReportOfThisTreeIsShownAsAnObservation() {
        when(gate.isLive()).thenReturn(true);
        when(declared.isDeclared()).thenReturn(true);
        when(gate.freshReport()).thenReturn(Optional.of(GREEN));

        String block = evidence.block(ctx).orElseThrow();

        assertThat(block).startsWith(EvaluationTestEvidence.RULE).contains("PROJECT TEST RUN");
        verify(gate, never()).verifyTestsPass(anyString());
    }

    @Test
    void withoutAReportOfThisTreeTheSuiteRunsOnce() {
        when(gate.isLive()).thenReturn(true);
        when(declared.isDeclared()).thenReturn(true);
        when(gate.freshReport()).thenReturn(Optional.empty(), Optional.of(GREEN));

        assertThat(evidence.block(ctx)).isPresent();
        verify(gate, times(1)).verifyTestsPass("cycle-7-eval-tests");
    }

    @Test
    void anUndeclaredSuiteIsNeitherRunNorShown() {
        when(gate.isLive()).thenReturn(true);
        when(declared.isDeclared()).thenReturn(false);

        assertThat(evidence.block(ctx)).isEmpty();
        verify(gate, never()).verifyTestsPass(anyString());
        verify(gate, never()).freshReport();
    }

    @Test
    void noReportAtAllMeansNoBlock() {
        when(gate.isLive()).thenReturn(true);
        when(declared.isDeclared()).thenReturn(true);
        when(gate.freshReport()).thenReturn(Optional.empty());

        assertThat(evidence.block(ctx)).isEmpty();
    }

    /** T6-T: a run that left no report because its tests did not compile is shown as its blocker. */
    @Test
    void aRunWhoseTestsDidNotCompileIsShownAsItsBlocker() {
        when(gate.isLive()).thenReturn(true);
        when(declared.isDeclared()).thenReturn(true);
        when(gate.freshReport()).thenReturn(Optional.empty());
        when(gate.verifyTestsPass("cycle-7-eval-tests")).thenReturn(Optional.of(
                "the tests do not compile, so none ran -- src/test/java/zeta/NibTest.java:9: cannot find symbol"));

        String block = evidence.block(ctx).orElseThrow();

        assertThat(block).startsWith(EvaluationTestEvidence.RULE).contains("PROJECT TEST RUN",
                "the tests do not compile, so none ran -- src/test/java/zeta/NibTest.java:9: cannot find symbol");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }
}
