package com.walpro.autobuilder.agent.evaluation;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringService;
import com.walpro.autobuilder.analysis.heuristic.HeuristicNegotiator;
import com.walpro.autobuilder.analysis.problem.ProblemDetector;
import com.walpro.autobuilder.analysis.heuristic.HeuristicOpinion;
import com.walpro.autobuilder.analysis.heuristic.NegotiationResult;
import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.build.BootGate;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.CompileProbeService;
import com.walpro.autobuilder.core.build.PackageGate;
import com.walpro.autobuilder.core.build.TestRunGate;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.merge.MergeRollbackService;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.graph.evaluation.EvaluationActionRegistrar;
import com.walpro.autobuilder.graph.evaluation.EvaluationGraphFacade;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.walpro.autobuilder.core.build.TestRunReport;
import com.walpro.autobuilder.goal.DeclaredTestSuite;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

/**
 * T6-T: a change the operator's suite shows better than the project as it is, is accepted whatever the evaluator
 * said -- unless a deterministic defect finding (the regression guard among them) rejected it. Invented
 * coordinates; the shape is ladder-rows run 2026-10-06_07-42-22 (22 of 29 passing, rejected against a tree whose
 * specification did not compile).
 */
@ExtendWith(MockitoExtension.class)
class EvaluationAgentTestProgressTest {

    @Mock private EvaluationLLMAdapter evaluationAdapter;
    @Mock private ScoringService scoringService;
    @Mock private EvaluationInterpreter evaluationInterpreter;
    @Mock private ProblemDetector problemDetector;
    @Mock private HeuristicNegotiator heuristicNegotiator;
    @Mock private EvaluationGraphFacade graphFacade;
    @Mock private EvaluationActionRegistrar evaluationActionRegistrar;
    @Mock private GoalPlannerService goalPlannerService;
    @Mock private MutationSnapshotService mutationSnapshotService;
    @Mock private CompileProbeService compileProbeService;
    @Mock private MergeRollbackService mergeRollbackService;
    @Mock private FileService fileService;
    @Mock private TestRunGate testRunGate;
    @Mock private DeclaredTestSuite declared;

    private EvaluationAgent agent;
    private final TestRunReport seven = new TestRunReport(29, 7, 0, 0, List.of(), Map.of());
    private final EvaluationOutcome rejected = new EvaluationOutcome(0.42, MutationAcceptanceState.REJECTED,
            GoalProgressState.STAGNATING, "fails 7 tests across the target classes", null);
    private final CycleContextData compiled = CycleContextData.builder().cycleName("c-23").build()
            .withLastProbeOutcome(new BuildOutcome(BuildResult.COMPILE_OK, "", 0L, "", "c-23"));

    @BeforeEach
    void setUp() {
        agent = new EvaluationAgent(
                evaluationAdapter, scoringService, evaluationInterpreter, problemDetector,
                heuristicNegotiator, List.of(), graphFacade, evaluationActionRegistrar,
                goalPlannerService, mutationSnapshotService, compileProbeService,
                mergeRollbackService, fileService);
        agent.setTestRunGate(testRunGate);
        agent.setDeclaredTestSuite(declared);
        lenient().when(testRunGate.isLive()).thenReturn(true);
        lenient().when(declared.isDeclared()).thenReturn(true);
        lenient().when(testRunGate.freshReport()).thenReturn(Optional.of(seven));
    }

    private static NegotiationResult plain(EvaluationOutcome o) {
        return new NegotiationResult(o, List.of(), List.of());
    }

    @Test
    void aCandidateWhoseTestsRunWhereTheTreesDidNotCompileIsAccepted() {
        when(testRunGate.compareWithTree(any())).thenReturn(new TestRunGate.Delta("BETTER", 7, null, 0, 0));

        EvaluationOutcome out = agent.applyTestProgressCheck(compiled, plain(rejected), rejected);

        assertThat(out.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(out.goalProgress()).isEqualTo(GoalProgressState.PROGRESSING);
        assertThat(out.feedbackMessage()).contains("better with this change",
                "7 test(s) fail with it; without it the tests do not compile", "fails 7 tests across the target classes");
    }

    @Test
    void sameOrWorseOrUnknownLeavesTheDecision() {
        for (String v : List.of("SAME", "WORSE", "UNKNOWN")) {
            when(testRunGate.compareWithTree(any())).thenReturn(new TestRunGate.Delta(v, 7, 7, 1, 1));
            assertThat(agent.applyTestProgressCheck(compiled, plain(rejected), rejected)).as(v).isSameAs(rejected);
        }
    }

    @Test
    void aDefectFindingKeepsItsRejection_andAnUndeclaredSuiteOrANonCompilingCandidateIsLeftAlone() {
        when(testRunGate.compareWithTree(any())).thenReturn(new TestRunGate.Delta("BETTER", 5, 6, 0, 1));
        NegotiationResult defect = new NegotiationResult(rejected, List.of(HeuristicOpinion.ofDefectWithScore(
                "TestRegressionHeuristic", 0.9, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "x", 0.3)),
                List.of());
        assertThat(agent.applyTestProgressCheck(compiled, defect, rejected)).isSameAs(rejected);

        when(declared.isDeclared()).thenReturn(false);
        assertThat(agent.applyTestProgressCheck(compiled, plain(rejected), rejected)).isSameAs(rejected);

        when(declared.isDeclared()).thenReturn(true);
        CycleContextData broken = compiled.withLastProbeOutcome(new BuildOutcome(BuildResult.COMPILE_ERROR, "", 0L, "", "c"));
        assertThat(agent.applyTestProgressCheck(broken, plain(rejected), rejected)).isSameAs(rejected);
    }

    /** T6-T: tests that still do not compile -- fewer test files that do not is progress (ladder-grind, field44). */
    @Test
    void aCandidateAfterWhichMoreTestFilesCompileIsAccepted_whenNoneRan() {
        when(testRunGate.freshReport()).thenReturn(Optional.empty());
        when(testRunGate.compareUncompiledWithTree()).thenReturn(new TestRunGate.Delta("BETTER", 1, 2, 0, 1, true,
                List.of("src/test/java/zeta/NibTest.java")));

        EvaluationOutcome out = agent.applyTestProgressCheck(compiled, plain(rejected), rejected);

        assertThat(out.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(out.feedbackMessage()).contains("1 test file(s) that did not compile do now; 1 still do not (NibTest.java)");

        when(testRunGate.compareUncompiledWithTree()).thenReturn(new TestRunGate.Delta("UNKNOWN", 0, null, 0, 0));
        assertThat(agent.applyTestProgressCheck(compiled, plain(rejected), rejected)).isSameAs(rejected);
    }
}
