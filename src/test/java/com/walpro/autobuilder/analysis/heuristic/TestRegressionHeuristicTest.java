package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.core.build.BuildExecutor;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.TestRunGate;
import com.walpro.autobuilder.core.build.TestRunReport;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.DeclaredTestSuite;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * T6-T: a change that makes more of the operator's tests fail than the project as it is, is rejected and names
 * what it broke; a partial fix, or a trade, still lands. Invented coordinates; the shape is ladder-rows run
 * 2026-10-06_00-25-31 (one failing, then seven merged).
 */
class TestRegressionHeuristicTest {

    private final TestRunGate gate = mock(TestRunGate.class);
    private final DeclaredTestSuite declared = mock(DeclaredTestSuite.class);
    private final TestRegressionHeuristic heuristic = new TestRegressionHeuristic(provider(gate), provider(declared));
    private final EvaluationOutcome accepted = new EvaluationOutcome(0.58, MutationAcceptanceState.ACCEPTED,
            GoalProgressState.STAGNATING, "looks fine", null);
    private final CycleContextData compiled = CycleContextData.builder()
            .lastProbeOutcome(new BuildOutcome(BuildResult.COMPILE_OK, "", 100L, "compile", "c")).build();

    @BeforeEach
    void setUp() {
        when(gate.isLive()).thenReturn(true);
        when(declared.isDeclared()).thenReturn(true);
    }

    private static TestRunReport failing(String... tests) {
        List<TestRunReport.Failure> f = Arrays.stream(tests).map(t -> new TestRunReport.Failure(
                "zeta." + t.substring(0, t.indexOf('.')), t.substring(t.indexOf('.') + 1),
                TestRunReport.Outcome.FAILED, "x")).toList();
        return new TestRunReport(9, f.size(), 0, 0, f, Map.of());
    }

    @Test
    void aChangeThatMakesMoreTestsFailIsRejected_namingWhatItBroke() {
        when(gate.treeFailing()).thenReturn(Optional.of(Set.of("NibTest.widens")));
        when(gate.freshReport()).thenReturn(Optional.of(failing("NibTest.widens", "InkwellTest.pours", "InkwellTest.drains")));

        HeuristicOpinion o = heuristic.evaluate(compiled, accepted);

        assertThat(o.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(o.defectFinding()).isTrue();
        assertThat(o.rationale()).contains("3 test(s) fail with it, 1 without it", "InkwellTest.drains", "InkwellTest.pours")
                .doesNotContain("breaks NibTest");
        verify(gate).candidateRan(argThat(r -> r != null && r.failing().size() == 3));
    }

    @Test
    void aPartialFixOrATradeStillLands() {
        when(gate.treeFailing()).thenReturn(Optional.of(Set.of("NibTest.widens", "InkwellTest.pours")));
        when(gate.freshReport()).thenReturn(Optional.of(failing("NibTest.widens")));
        assertThat(heuristic.evaluate(compiled, accepted).mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);

        when(gate.freshReport()).thenReturn(Optional.of(failing("NibTest.widens", "QuillTest.dries")));
        assertThat(heuristic.evaluate(compiled, accepted).mutationState()).as("one fixed, one broken").isEqualTo(MutationAcceptanceState.NOT_EVALUATED);
    }

    @Test
    void noBaseline_noDeclaration_orNoCompileMeansNoOpinion_andNoStaleCandidate() {
        when(gate.treeFailing()).thenReturn(Optional.empty());
        when(gate.freshReport()).thenReturn(Optional.of(failing("NibTest.widens")));
        assertThat(heuristic.evaluate(compiled, accepted).mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);

        when(declared.isDeclared()).thenReturn(false);
        assertThat(heuristic.evaluate(compiled, accepted).mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);

        clearInvocations(gate);
        when(declared.isDeclared()).thenReturn(true);
        CycleContextData broken = CycleContextData.builder()
                .lastProbeOutcome(new BuildOutcome(BuildResult.COMPILE_ERROR, "", 100L, "compile", "c")).build();
        assertThat(heuristic.evaluate(broken, accepted).mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);
        verify(gate).candidateRan(null);
        verify(gate, never()).candidateRan(argThat(r -> r != null));
    }

    /** The gate's own bookkeeping: a merge promotes the candidate's run; a merge with none leaves the tree unknown. */
    @Test
    void theGateKeepsTheTreesFailingTests() {
        TestRunGate real = new TestRunGate(true, "mvn test", mock(BuildExecutor.class));
        real.treeRan(failing("NibTest.widens"));
        assertThat(real.treeFailing()).contains(Set.of("NibTest.widens"));

        real.candidateRan(failing("NibTest.widens", "InkwellTest.pours"));
        real.candidateMerged();
        assertThat(real.treeFailing()).contains(Set.of("NibTest.widens", "InkwellTest.pours"));

        real.candidateRan(null);
        real.candidateMerged();
        assertThat(real.treeFailing()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }
}
