package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.TestRunGate;
import com.walpro.autobuilder.core.build.TestRunReport;
import com.walpro.autobuilder.core.build.ValueObservationProbe;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.merge.MergeRollbackPoint;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.SeedObligationsFile;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-CQ: a mutation whose own tests fail is rejected, with what the code returned, on a target
 * that declares its suite passing. Invented coordinates; the failure shape is structdiff run 3's.
 */
class StagedTestFailureHeuristicTest {

    private static final String SEED = "OBJECTIVE: every widget entry point is covered by a test.";
    private static final String STAGED = "src/test/java/shop/widget/WidgetParserTest.java";

    private final TestRunGate gate = mock(TestRunGate.class);
    private final GoalPlannerService planner = mock(GoalPlannerService.class);
    private final FileService files = mock(FileService.class);
    private final EvaluationOutcome outcome = mock(EvaluationOutcome.class);
    private final ValueObservationProbe probe = mock(ValueObservationProbe.class);
    private StagedTestFailureHeuristic heuristic;

    @BeforeEach
    void setUp() throws Exception {
        heuristic = new StagedTestFailureHeuristic(provider(gate),
                provider(new com.walpro.autobuilder.goal.DeclaredTestSuite(provider(planner))), provider(planner), provider(probe));
        var purpose = com.walpro.autobuilder.goal.purpose.PurposeService.fileOnly(files);
        purpose.recordSeed(SEED, "test");
        when(planner.requirements()).thenReturn(purpose);
        GoalEntity root = mock(GoalEntity.class);
        when(root.getDescription()).thenReturn(SEED);
        when(planner.getRootGoal()).thenReturn(root);
        when(planner.specification()).thenReturn(SEED);
        when(gate.isLive()).thenReturn(true);
        when(outcome.score()).thenReturn(0.8);
        declare(List.of("Every widget entry point is covered by a test.", "gate: tests The whole suite passes."));
    }

    @Test
    void theMutationsOwnFailingTestsRejectItAndCarryTheObservedValue() {
        failWith(new TestRunReport.Failure("shop.widget.WidgetParserTest", "parseNamesTheRoot",
                TestRunReport.Outcome.FAILED, "expected: <Gadget> but was: <shop/Gadget.src>"));

        HeuristicOpinion opinion = heuristic.evaluate(staged(STAGED), outcome);

        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.CRITICAL);
        assertThat(opinion.rationale())
                .contains("WidgetParserTest.parseNamesTheRoot: expected: <Gadget> but was: <shop/Gadget.src>");
    }

    /**
     * participation run 2026-09-25_11-45-07: a failure names the first failed assertion only, so one
     * test was rewritten nine times, a field per rejection. What the call returned, observed by running
     * the failing test up to it, names every field at once -- and rides the same relayed rationale.
     */
    @Test
    void whatTheFailingTestsCallsReturnedIsObservedAndRelayed() {
        TestRunReport.Failure failure = new TestRunReport.Failure("shop.widget.WidgetParserTest", "mergesTwo",
                TestRunReport.Outcome.FAILED, "expected: <10.0> but was: <6.0>");
        failWith(failure);
        when(probe.observeFailing(any(), anyList(), anyString())).thenReturn(List.of(new ValueObservationProbe.Observation(
                "Widget.merge(...)", "WidgetParserTest.mergesTwo", "\"Widget[name=a, weight=0.8, spare=6.0]\"")));

        HeuristicOpinion opinion = heuristic.evaluate(staged(STAGED), outcome);

        assertThat(opinion.rationale())
                .contains("expected: <10.0> but was: <6.0>")
                .contains("Widget.merge(...) returned \"Widget[name=a, weight=0.8, spare=6.0]\" (observed in WidgetParserTest.mergesTwo)");
        verify(probe).observeFailing(org.mockito.ArgumentMatchers.eq(List.of(failure)),
                org.mockito.ArgumentMatchers.argThat(l -> l.contains("Every widget entry point is covered by a test.")),
                anyString());
    }

    /** Red before this mutation, and the repair item's business -- unrelated work is not blocked. */
    @Test
    void aFailureInATestClassTheMutationDidNotWriteIsNotItsDefect() {
        failWith(new TestRunReport.Failure("shop.widget.OlderTest", "sums",
                TestRunReport.Outcome.FAILED, "expected: <3> but was: <4>"));

        assertThat(heuristic.evaluate(staged(STAGED), outcome).mutationState())
                .isEqualTo(MutationAcceptanceState.NOT_EVALUATED);
    }

    /** Test-first work on a target that never said a failing test is not progress. */
    @Test
    void withoutTheDeclarationNothingIsRun() throws Exception {
        declare(List.of("Every widget entry point is covered by a test."));

        heuristic.evaluate(staged(STAGED), outcome);

        verify(gate, never()).verifyTestsPass(anyString());
    }

    @Test
    void aMutationWithNoTestSourceIsNotRun() {
        heuristic.evaluate(staged("src/main/java/shop/widget/WidgetParser.java"), outcome);

        verify(gate, never()).verifyTestsPass(anyString());
    }

    @Test
    void aNestedTestClassBelongsToItsFile() {
        var own = StagedTestFailureHeuristic.ownFailures(List.of(new TestRunReport.Failure(
                "shop.widget.WidgetParserTest$Roots", "named", TestRunReport.Outcome.FAILED, "x")), List.of(STAGED));

        assertThat(own).hasSize(1);
    }

    private void failWith(TestRunReport.Failure failure) {
        when(gate.verifyTestsPass(anyString())).thenReturn(Optional.of("test suite failing"));
        when(gate.freshReport()).thenReturn(Optional.of(
                new TestRunReport(1, 1, 0, 0, List.of(failure), Map.of())));
    }

    private void declare(List<String> lines) throws Exception {
        when(files.exists(SeedObligationsFile.PATH)).thenReturn(true);
        when(files.readFile(SeedObligationsFile.PATH)).thenReturn(SeedObligationsFile.render(SEED, lines));
    }

    private static CycleContextData staged(String path) {
        return CycleContextData.builder()
                .cycleName("cycle-1")
                .projectDiff(new ProjectDiffResult(null, Map.of(path, "class X {}"), Set.of()))
                .lastProbeOutcome(new BuildOutcome(BuildResult.COMPILE_OK, "", 1L, "mvn test-compile", "cycle-1"))
                .lastRollbackPoint(new MergeRollbackPoint("cycle-1", Map.of(), Set.of(path)))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }
}
