package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.agent.evaluation.score.ScoringService;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.heuristic.EvaluationHeuristic;
import com.walpro.autobuilder.analysis.heuristic.HeuristicNegotiator;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * T2-EY: the refusal must happen before anything expensive runs, and must not happen when a merge
 * has made the earlier verdict revisable. Both are asserted against the collaborators rather than
 * against the returned state alone, because "did not call the evaluator" is the actual claim.
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

    private static final String FP = "fingerprint-of-the-repeated-proposal";

    @BeforeEach
    void setUp() {
        ledger = new RejectedProposalLedger();
        lenient().when(compileProbeService.probe(any())).thenReturn(
                new CompileProbeService.ProbeResult(BuildOutcome.skipped("disabled in test"), null));

        // The tests asserting that a proposal goes through normally run the full path, so the
        // collaborators past the gate have to answer. Lenient because the refusal tests never
        // reach them — which is the thing those tests exist to prove.
        var accepted = new com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome(
                0.9, MutationAcceptanceState.ACCEPTED,
                com.walpro.autobuilder.model.GoalProgressState.PROGRESSING, "ok", null);
        lenient().when(evaluationAdapter.evaluate(any()))
                .thenReturn(new ParsedEvaluation(accepted, List.of()));
        lenient().when(problemDetector.detectAndUpdate(any(), any(), any()))
                .thenReturn(com.walpro.autobuilder.analysis.problem.ProblemContext.empty());
        lenient().when(heuristicNegotiator.negotiate(any(), any(), any()))
                .thenReturn(new com.walpro.autobuilder.analysis.heuristic.NegotiationResult(
                        accepted, List.of(), List.of()));
        var interpretation = org.mockito.Mockito.mock(EvaluationInterpreter.InterpretationResult.class);
        lenient().when(interpretation.success()).thenReturn(true);
        lenient().when(evaluationInterpreter.interpret(any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(interpretation);
    }

    private EvaluationAgent newAgent() {
        EvaluationAgent agent = new EvaluationAgent(
                evaluationAdapter, scoringService, evaluationInterpreter, problemDetector,
                heuristicNegotiator, List.<EvaluationHeuristic>of(), graphFacade,
                evaluationActionRegistrar, goalPlannerService, mutationSnapshotService,
                compileProbeService, mergeRollbackService, fileService);
        agent.setRejectedProposalLedger(ledger);
        return agent;
    }

    private CycleContextData contextProposing(String fingerprint) {
        return CycleContextData.builder()
                .cycleName("cycle-2")
                .projectDiff(new ProjectDiffResult(null, Map.of("src/Main.java", "changed"), Set.of()))
                .previousOutput(Map.of())
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .lastEvolutionFingerprint(fingerprint)
                .build();
    }

    @Test
    void aRepeatOfAStandingRejectionNeverReachesTheProbeOrTheEvaluator() {
        ledger.recordRejection(FP, "MergeRegressionHeuristic: takes an annotation off a type", "sub-1");

        CycleContextData out = newAgent().execute(contextProposing(FP));

        verify(compileProbeService, never()).probe(any());
        verify(evaluationAdapter, never()).evaluate(any());
        assertThat(out.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
    }

    @Test
    void theRefusalRelaysTheOriginalReasonRatherThanInventingOne() {
        ledger.recordRejection(FP, "MergeRegressionHeuristic: takes an annotation off a type", "sub-1");

        CycleContextData out = newAgent().execute(contextProposing(FP));

        assertThat(out.rejectionFeedback()).isNotEmpty();
        assertThat(out.rejectionFeedback().get(0))
                .contains("takes an annotation off a type")
                .contains("already rejected in this run");
    }

    @Test
    void aMergeSinceTheRejectionSendsTheProposalThroughNormally() {
        // The one case in 39 that the safety condition protects. Without it this proposal would be
        // refused although the tree it is judged against has changed.
        ledger.recordRejection(FP, "Compile probe failed", "sub-1");
        ledger.recordMerge();

        newAgent().execute(contextProposing(FP));

        verify(compileProbeService).probe(any());
    }

    @Test
    void aProposalTheRunHasNotSeenIsUntouched() {
        ledger.recordRejection("some-other-proposal", "Compile probe failed", "sub-1");

        newAgent().execute(contextProposing(FP));

        verify(compileProbeService).probe(any());
    }

    @Test
    void theCandidateIsMarkedEvaluatedSoTheLoopDoesNotSpinOnIt() {
        // A short-circuit that leaves lastEvaluatedEvolutionFingerprint behind pins the
        // deliberation mode to EVALUATE on the same candidate every cycle — the starvation T5-J
        // was filed for, and exactly the shape a new short-circuit can reintroduce.
        ledger.recordRejection(FP, "reason", "sub-1");

        CycleContextData out = newAgent().execute(contextProposing(FP));

        assertThat(out.lastEvaluatedEvolutionFingerprint()).isEqualTo(FP);
    }

    @Test
    void aFirstRepeatAsksForADifferentMutation() {
        ledger.recordRejection(FP, "reason", "sub-1");

        CycleContextData out = newAgent().execute(contextProposing(FP));

        assertThat(out.hasOpenRequest(BasicSignals.DIVERGENCE_REQUEST)).isTrue();
        assertThat(out.hasOpenRequest(BasicSignals.REPETITION_DETECTED)).isTrue();
    }

    @Test
    void aRepeatThatSurvivedAChangeOfSubgoalAsksForADifferentWorkItem() {
        // Two subgoals have now proposed the same refused bytes, so replanning is not dislodging
        // it and the mutation is no longer the thing to change.
        ledger.recordRejection(FP, "reason", "sub-0");
        ledger.recordRejection(FP, "reason", "sub-1");

        CycleContextData out = newAgent().execute(contextProposing(FP));

        assertThat(out.hasOpenRequest(BasicSignals.REPLAN_REQUESTED)).isTrue();
    }

    @Test
    void theRefusalIsCountedSoTheRunCanReportWhatItSaved() {
        ledger.recordRejection(FP, "reason", "sub-1");

        newAgent().execute(contextProposing(FP));

        assertThat(ledger.readout().refusals()).isEqualTo(1);
    }

    @Test
    void withoutTheLedgerTheAgentBehavesExactlyAsBefore() {
        // The ledger is optional so existing tests construct the agent freely; absent it, nothing
        // in this path may change.
        EvaluationAgent agent = new EvaluationAgent(
                evaluationAdapter, scoringService, evaluationInterpreter, problemDetector,
                heuristicNegotiator, List.<EvaluationHeuristic>of(), graphFacade,
                evaluationActionRegistrar, goalPlannerService, mutationSnapshotService,
                compileProbeService, mergeRollbackService, fileService);

        agent.execute(contextProposing(FP));

        verify(compileProbeService).probe(any());
    }
}
