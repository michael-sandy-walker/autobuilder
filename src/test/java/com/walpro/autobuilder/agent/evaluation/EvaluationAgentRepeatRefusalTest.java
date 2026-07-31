package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringService;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.heuristic.EvaluationHeuristic;
import com.walpro.autobuilder.analysis.heuristic.HeuristicNegotiator;
import com.walpro.autobuilder.analysis.heuristic.NegotiationResult;
import com.walpro.autobuilder.analysis.problem.ProblemContext;
import com.walpro.autobuilder.analysis.problem.ProblemDetector;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.CompileProbeService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.mutation.RejectedProposalLedger;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T2-EY: exercised as a round trip through the agent rather than by seeding the ledger directly.
 *
 * <p>The verdict is scoped to a fingerprint of the tree, which the agent derives internally, so a
 * test that seeded the ledger with a tree state of its own choosing would only prove that two
 * strings it invented compare equal. Rejecting through the agent and then re-proposing is the thing
 * the field actually does.
 */
@ExtendWith(MockitoExtension.class)
class EvaluationAgentRepeatRefusalTest {

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

    private RejectedProposalLedger ledger;
    private EvaluationAgent agent;

    private static final String FP = "fingerprint-of-the-repeated-proposal";
    private static final String REASON =
            "MergeRegressionHeuristic: takes an annotation off a type that already had it";

    /** The tree the proposal is judged against. */
    private static final Map<String, String> TREE =
            Map.of("src/main/java/Book.java", "class Book {}");
    /** The same tree after something wrote to it — a merge, or an injector skeleton. */
    private static final Map<String, String> TREE_MOVED =
            Map.of("src/main/java/Book.java", "class Book {}",
                   "src/main/java/BookRepository.java", "interface BookRepository {}");

    @BeforeEach
    void setUp() {
        ledger = new RejectedProposalLedger();
        lenient().when(compileProbeService.probe(any())).thenReturn(
                new CompileProbeService.ProbeResult(BuildOutcome.skipped("disabled in test"), null));

        agent = new EvaluationAgent(
                evaluationAdapter, scoringService, evaluationInterpreter, problemDetector,
                heuristicNegotiator, List.<EvaluationHeuristic>of(), graphFacade,
                evaluationActionRegistrar, goalPlannerService, mutationSnapshotService,
                compileProbeService, mergeRollbackService, fileService);
        agent.setRejectedProposalLedger(ledger);

        lenient().when(problemDetector.detectAndUpdate(any(), any(), any()))
                .thenReturn(ProblemContext.empty());
    }

    /**
     * The interpretation is what the agent reads to decide whether a mutation was rejected, so it
     * is constructed rather than mocked. A bare mock answers null for {@code mutationState()}, and
     * the ledger then records nothing at all — which made every one of these tests pass vacuously
     * against a gate that had never fired.
     */
    private void interpretationSays(MutationAcceptanceState state, GoalProgressState progress) {
        lenient().when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean()))
                .thenReturn(new EvaluationInterpreter.InterpretationResult(
                        0.5, 0.5, 0.0, true, state, false, progress));
    }

    /** Makes the evaluator answer REJECTED with {@link #REASON}, so the agent records a verdict. */
    private void evaluatorRejects() {
        EvaluationOutcome rejected = new EvaluationOutcome(0.1, MutationAcceptanceState.REJECTED,
                GoalProgressState.STAGNATING, REASON, null);
        when(evaluationAdapter.evaluate(any())).thenReturn(new ParsedEvaluation(rejected, List.of()));
        when(heuristicNegotiator.negotiate(any(), any(), any()))
                .thenReturn(new NegotiationResult(rejected, List.of(), List.of()));
        interpretationSays(MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING);
    }

    private CycleContextData contextProposing(String fingerprint, Map<String, String> tree, String subgoal) {
        return CycleContextData.builder()
                .cycleName("cycle-x")
                .projectDiff(new ProjectDiffResult(null, Map.of("src/main/java/Book.java", "changed"), Set.of()))
                .previousOutput(tree)
                .currentGoalId("goal-1")
                .currentSubGoalId(subgoal)
                .lastEvolutionFingerprint(fingerprint)
                .build();
    }

    private CycleContextData contextProposing(String fingerprint) {
        return contextProposing(fingerprint, TREE, "sub-1");
    }

    @Test
    void aRepeatAgainstTheSameTreeNeverReachesTheProbeOrTheEvaluator() {
        evaluatorRejects();
        agent.execute(contextProposing(FP));           // rejected, verdict recorded

        CycleContextData out = agent.execute(contextProposing(FP));   // the same bytes again

        verify(compileProbeService, times(1)).probe(any());     // the first pass only
        verify(evaluationAdapter, times(1)).evaluate(any());
        assertThat(out.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
    }

    @Test
    void theRefusalRelaysTheOriginalReasonRatherThanInventingOne() {
        evaluatorRejects();
        agent.execute(contextProposing(FP));

        CycleContextData out = agent.execute(contextProposing(FP));

        assertThat(out.rejectionFeedback()).isNotEmpty();
        assertThat(out.rejectionFeedback().get(0))
                .contains("takes an annotation off a type")
                .contains("already rejected in this run");
    }

    @Test
    void aTreeThatHasMovedSendsTheProposalThroughAgain() {
        // The condition the scoping exists for. A merge is not the only writer — the compile-fix
        // injector's files survive the rejection rollback by design — so this covers both.
        evaluatorRejects();
        agent.execute(contextProposing(FP, TREE, "sub-1"));

        agent.execute(contextProposing(FP, TREE_MOVED, "sub-1"));

        verify(compileProbeService, times(2)).probe(any());
    }

    @Test
    void aProposalTheRunHasNotSeenIsUntouched() {
        evaluatorRejects();
        agent.execute(contextProposing("some-other-proposal"));

        agent.execute(contextProposing(FP));

        verify(compileProbeService, times(2)).probe(any());
    }

    @Test
    void anAcceptedProposalLeavesNoVerdictToRepeatAgainst() {
        EvaluationOutcome accepted = new EvaluationOutcome(0.9, MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING, "ok", null);
        when(evaluationAdapter.evaluate(any())).thenReturn(new ParsedEvaluation(accepted, List.of()));
        when(heuristicNegotiator.negotiate(any(), any(), any()))
                .thenReturn(new NegotiationResult(accepted, List.of(), List.of()));
        interpretationSays(MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING);

        agent.execute(contextProposing(FP));
        agent.execute(contextProposing(FP));

        verify(compileProbeService, times(2)).probe(any());
    }

    @Test
    void theCandidateIsMarkedEvaluatedSoTheLoopDoesNotSpinOnIt() {
        // A short-circuit that leaves lastEvaluatedEvolutionFingerprint behind pins the
        // deliberation mode to EVALUATE on the same candidate every cycle — the starvation T5-J
        // was filed for, and exactly the shape a new short-circuit can reintroduce.
        evaluatorRejects();
        agent.execute(contextProposing(FP));

        CycleContextData out = agent.execute(contextProposing(FP));

        assertThat(out.lastEvaluatedEvolutionFingerprint()).isEqualTo(FP);
    }

    @Test
    void aFirstRepeatAsksForADifferentMutation() {
        evaluatorRejects();
        agent.execute(contextProposing(FP));

        CycleContextData out = agent.execute(contextProposing(FP));

        assertThat(out.hasOpenRequest(BasicSignals.DIVERGENCE_REQUEST)).isTrue();
        assertThat(out.hasOpenRequest(BasicSignals.REPETITION_DETECTED)).isTrue();
    }

    @Test
    void aRepeatThatSurvivedAChangeOfSubgoalAsksForADifferentWorkItem() {
        // Two subgoals have now proposed the same refused bytes, so replanning is not dislodging
        // it and the mutation is no longer the thing to change.
        evaluatorRejects();
        agent.execute(contextProposing(FP, TREE, "sub-0"));
        agent.execute(contextProposing(FP, TREE, "sub-1"));

        CycleContextData out = agent.execute(contextProposing(FP, TREE, "sub-1"));

        assertThat(out.hasOpenRequest(BasicSignals.REPLAN_REQUESTED)).isTrue();
    }

    @Test
    void theRefusalIsCountedSoTheRunCanReportWhatItSaved() {
        evaluatorRejects();
        agent.execute(contextProposing(FP));
        agent.execute(contextProposing(FP));

        assertThat(ledger.readout().refusals()).isEqualTo(1);
    }

    @Test
    void withoutTheLedgerTheAgentBehavesExactlyAsBefore() {
        EvaluationAgent noLedger = new EvaluationAgent(
                evaluationAdapter, scoringService, evaluationInterpreter, problemDetector,
                heuristicNegotiator, List.<EvaluationHeuristic>of(), graphFacade,
                evaluationActionRegistrar, goalPlannerService, mutationSnapshotService,
                compileProbeService, mergeRollbackService, fileService);
        evaluatorRejects();

        noLedger.execute(contextProposing(FP));
        noLedger.execute(contextProposing(FP));

        verify(compileProbeService, times(2)).probe(any());
    }
}
