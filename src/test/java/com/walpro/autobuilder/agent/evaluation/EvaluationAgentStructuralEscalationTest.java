package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringService;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.heuristic.EvaluationHeuristic;
import com.walpro.autobuilder.analysis.heuristic.HeuristicNegotiator;
import com.walpro.autobuilder.analysis.heuristic.HeuristicOpinion;
import com.walpro.autobuilder.analysis.heuristic.NegotiationResult;
import com.walpro.autobuilder.analysis.problem.ProblemContext;
import com.walpro.autobuilder.analysis.problem.ProblemDetector;
import com.walpro.autobuilder.core.blocker.BlockerSubgoalInjector;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.CompileProbeService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
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

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T2-ES: when re-asking has demonstrably stopped working, change the question.
 *
 * <p>The shape under test is run {@code 2026-09-13_09-44-03}'s: one heuristic rejecting seven
 * consecutive mutations for one parallel store, while the only thing that changed between the
 * second attempt and the seventh was the integer in the warning text. Driven through
 * {@code execute()} rather than by calling the streak update directly, because the carry-forward
 * of the streak map across cycles is half of what makes a repeat countable.
 */
@ExtendWith(MockitoExtension.class)
class EvaluationAgentStructuralEscalationTest {

    @Mock private EvaluationLLMAdapter evaluationAdapter;
    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS) private ScoringService scoringService;
    @Mock private EvaluationInterpreter evaluationInterpreter;
    @Mock private ProblemDetector problemDetector;
    @Mock private HeuristicNegotiator heuristicNegotiator;
    @Mock private EvaluationGraphFacade graphFacade;
    @Mock private EvaluationActionRegistrar evaluationActionRegistrar;
    @Mock private GoalPlannerService goalPlannerService;
    @Mock private MutationSnapshotService mutationSnapshotService;
    @Mock private CompileProbeService compileProbeService;
    @Mock private com.walpro.autobuilder.core.merge.MergeRollbackService mergeRollbackService;
    @Mock private com.walpro.autobuilder.core.service.FileService fileService;
    @Mock private BlockerSubgoalInjector blockerSubgoalInjector;

    private EvaluationAgent agent;

    /** The two ways out, as StoreCoherenceHeuristic states them. */
    private static final List<String> PATHS = List.of(
            "BookRepository is the only type that declares a collection of 'Book'",
            "BookStore is the only type that declares a collection of 'Book'");

    @BeforeEach
    void setUp() {
        lenient().when(compileProbeService.probe(any())).thenReturn(
                new CompileProbeService.ProbeResult(BuildOutcome.skipped("disabled in test"), null));

        agent = new EvaluationAgent(
                evaluationAdapter, scoringService, evaluationInterpreter, problemDetector,
                heuristicNegotiator, List.<EvaluationHeuristic>of(), graphFacade,
                evaluationActionRegistrar, goalPlannerService, mutationSnapshotService,
                compileProbeService, mergeRollbackService, fileService);
        agent.setBlockerSubgoalInjector(blockerSubgoalInjector);

        lenient().when(problemDetector.detectAndUpdate(any(), any(), any()))
                .thenReturn(ProblemContext.empty());
        lenient().when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean()))
                .thenReturn(new EvaluationInterpreter.InterpretationResult(
                        0.5, 0.5, 0.0, true, MutationAcceptanceState.REJECTED, false,
                        GoalProgressState.STAGNATING));
    }

    /**
     * Drives one cycle whose negotiation rejects on a CRITICAL structural defect finding, and
     * returns the resulting context so the next cycle can inherit its streak map — which is what
     * {@code ContextLineageTracker} does in production while the subgoal is unchanged.
     */
    private CycleContextData rejectOnce(CycleContextData previous, String subject, String rationale) {
        HeuristicOpinion finding = HeuristicOpinion.ofDefectWithScore(
                "StoreCoherenceHeuristic", 0.9,
                MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING,
                rationale, 0.4,
                new HeuristicOpinion.StructuralDefect(subject, PATHS));

        EvaluationOutcome rejected = new EvaluationOutcome(0.4, MutationAcceptanceState.REJECTED,
                GoalProgressState.STAGNATING, rationale, null);
        when(evaluationAdapter.evaluate(any())).thenReturn(new ParsedEvaluation(rejected, List.of()));
        when(heuristicNegotiator.negotiate(any(), any(), any()))
                .thenReturn(new NegotiationResult(rejected, List.of(finding), List.of(finding)));

        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-x")
                .projectDiff(new ProjectDiffResult(null,
                        Map.of("src/main/java/BookStore.java", rationale), Set.of()))
                .previousOutput(Map.of("src/main/java/Book.java", "class Book {}"))
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .lastEvolutionFingerprint("fp-" + rationale)
                .build();
        if (previous != null) {
            context = context.withCriticalHeuristicStreaks(previous.criticalHeuristicStreaks());
        }
        return agent.execute(context);
    }

    /**
     * The threshold spends the first two cycles on the warning surface — no warning on the first
     * firing, the persistent-warning block from the second — so escalating on the third gives
     * re-asking exactly one chance to work.
     */
    @Test
    void escalatesOnTheThirdConsecutiveCycle_notBefore() {
        CycleContextData c1 = rejectOnce(null, "Book", "declared in BookRepository:12, BookStore:14");
        verify(blockerSubgoalInjector, never())
                .injectStructuralDefectRedirection(anyString(), anyString(), any(), anyInt());

        CycleContextData c2 = rejectOnce(c1, "Book", "declared in BookRepository:12, BookStore:14");
        verify(blockerSubgoalInjector, never())
                .injectStructuralDefectRedirection(anyString(), anyString(), any(), anyInt());

        rejectOnce(c2, "Book", "declared in BookRepository:12, BookStore:11");

        verify(blockerSubgoalInjector, times(1)).injectStructuralDefectRedirection(
                eq("StoreCoherenceHeuristic"), eq("Book"), eq(PATHS), eq(3));
    }

    /**
     * The field shape, and the reason a content fingerprint could not do this job: across those
     * three cycles the rationale is a different string every time, because the model rewrote the
     * file and the declaration moved. The streak counts the subject, so it still reaches three.
     */
    @Test
    void aRationaleThatMovesWithEveryRewrite_stillCountsAsOneDefect() {
        CycleContextData c1 = rejectOnce(null, "Book", "declared in BookRepository:12, BookStore:14");
        CycleContextData c2 = rejectOnce(c1, "Book", "declared in BookRepository:12, BookStore:11");
        CycleContextData c3 = rejectOnce(c2, "Book", "declared in BookRepository:12, BookStore:13");

        assertThat(c3.criticalHeuristicStreaks()).containsEntry("StoreCoherenceHeuristic|Book", 3);
        verify(blockerSubgoalInjector, times(1)).injectStructuralDefectRedirection(
                anyString(), eq("Book"), any(), eq(3));
    }

    /**
     * The other half of keying on the subject: a heuristic that finds something different each
     * cycle is not repeating itself, and escalating there would offer resolution paths for a
     * defect that is no longer the one in the way.
     */
    @Test
    void aChangedSubjectIsNotARepeat_andDoesNotEscalate() {
        CycleContextData c1 = rejectOnce(null, "Book", "declared in BookRepository:12, BookStore:14");
        CycleContextData c2 = rejectOnce(c1, "Member", "declared in MemberRepository:9, MemberStore:7");
        CycleContextData c3 = rejectOnce(c2, "Loan", "declared in LoanRepository:4, LoanStore:6");

        assertThat(c3.criticalHeuristicStreaks()).containsEntry("StoreCoherenceHeuristic|Loan", 1);
        verify(blockerSubgoalInjector, never())
                .injectStructuralDefectRedirection(anyString(), anyString(), any(), anyInt());
    }

    /**
     * Pre-T2-ES behaviour for findings about the mutation's statistics rather than its structure:
     * no subject, so the key is the heuristic's own name and nothing escalates, because there are
     * no alternatives to offer.
     */
    @Test
    void aFindingWithNoStructuralSubject_keysOnItsNameAndNeverEscalates() {
        HeuristicOpinion plain = HeuristicOpinion.ofDefectWithScore(
                "MergeRegressionHeuristic", 0.9,
                MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING,
                "score regressed", 0.4);
        assertThat(plain.identityKey()).isEqualTo("MergeRegressionHeuristic");
        assertThat(plain.defect()).isNull();

        EvaluationOutcome rejected = new EvaluationOutcome(0.4, MutationAcceptanceState.REJECTED,
                GoalProgressState.STAGNATING, "score regressed", null);
        when(evaluationAdapter.evaluate(any())).thenReturn(new ParsedEvaluation(rejected, List.of()));
        when(heuristicNegotiator.negotiate(any(), any(), any()))
                .thenReturn(new NegotiationResult(rejected, List.of(plain), List.of(plain)));

        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-x")
                .projectDiff(new ProjectDiffResult(null, Map.of("src/main/java/A.java", "x"), Set.of()))
                .previousOutput(Map.of())
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .lastEvolutionFingerprint("fp-1")
                .build();

        CycleContextData out = agent.execute(
                agent.execute(agent.execute(context).withCriticalHeuristicStreaks(
                        agent.execute(context).criticalHeuristicStreaks())));

        assertThat(out.criticalHeuristicStreaks()).containsKey("MergeRegressionHeuristic");
        verify(blockerSubgoalInjector, never())
                .injectStructuralDefectRedirection(anyString(), anyString(), any(), anyInt());
    }
}
