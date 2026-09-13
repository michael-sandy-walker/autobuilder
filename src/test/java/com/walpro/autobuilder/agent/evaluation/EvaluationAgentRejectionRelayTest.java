package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringResult;
import com.walpro.autobuilder.agent.evaluation.score.ScoringService;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.heuristic.ConfidenceLevel;
import com.walpro.autobuilder.analysis.heuristic.HeuristicNegotiator;
import com.walpro.autobuilder.analysis.heuristic.HeuristicOpinion;
import com.walpro.autobuilder.analysis.heuristic.NegotiationResult;
import com.walpro.autobuilder.analysis.problem.ProblemContext;
import com.walpro.autobuilder.analysis.problem.ProblemDetector;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.CompileProbeService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.merge.MergeRollbackPoint;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * T1-GA: does a SEMANTIC rejection relay its authored reasons to the next evolution prompt?
 *
 * <p>Run {@code 2026-09-13_09-44-03} produced seven CRITICAL {@code StoreCoherenceHeuristic}
 * rejections carrying a precise diagnosis and zero {@code T4-BX: relaying} log lines. The ticket's
 * filed explanation — that the {@code buildFailureRejection} exclusion suppressed it — does not
 * survive reading the branches: a rejection that reaches the heuristics at all took the normal LLM
 * branch, so that flag is false. This fixture reproduces exactly that shape so the question is
 * settled by execution instead of by elimination.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvaluationAgentRejectionRelayTest {

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
    @Mock private MergeRollbackPoint rollbackPoint;

    private EvaluationAgent agent;

    /** The run's own rejection text, verbatim. */
    private static final String STORE_COHERENCE_REASON =
            "parallel store introduced — entity 'Book' declared in BookRepository:12, BookStore:14;"
                    + " writes to one are invisible to the other";

    @BeforeEach
    void setUp() {
        agent = new EvaluationAgent(
                evaluationAdapter, scoringService, evaluationInterpreter, problemDetector,
                heuristicNegotiator, List.of(), graphFacade, evaluationActionRegistrar,
                goalPlannerService, mutationSnapshotService, compileProbeService,
                mergeRollbackService, fileService);
    }

    private CycleContextData contextWithACandidate() {
        return CycleContextData.builder()
                .cycleName("cycle-t1ga")
                .build()
                .withProjectDiff(new ProjectDiffResult(null, Map.of("Book.java", "class Book {}"), Set.of()))
                .withPreviousOutput(Map.of())
                .withLastEvolutionFingerprint("fp-t1ga");
    }

    /** COMPILE_OK with a rollback point — {@code filesOnDisk()} is {@code rollbackPoint != null}. */
    private void probeCompilesCleanly() {
        when(compileProbeService.probe(any())).thenReturn(new CompileProbeService.ProbeResult(
                new BuildOutcome(BuildResult.COMPILE_OK, "", 0L, "", "cycle-t1ga"), rollbackPoint));
    }

    private void llmAccepts() {
        EvaluationOutcome llm = new EvaluationOutcome(0.82, MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING, "looks fine to me", null);
        when(evaluationAdapter.evaluate(any())).thenReturn(new ParsedEvaluation(llm, List.of()));
    }

    /** A deterministic heuristic overrules the LLM at CRITICAL — the run's exact shape. */
    private void heuristicOverrulesAtCritical() {
        HeuristicOpinion critical = HeuristicOpinion.ofWithScore(
                "StoreCoherenceHeuristic", 0.90, MutationAcceptanceState.REJECTED,
                GoalProgressState.STAGNATING, STORE_COHERENCE_REASON, 0.82);
        EvaluationOutcome overruled = new EvaluationOutcome(0.82, MutationAcceptanceState.REJECTED,
                GoalProgressState.STAGNATING,
                "CRITICAL: Heuristic 'StoreCoherenceHeuristic' overruled (0.90): " + STORE_COHERENCE_REASON, null);
        when(heuristicNegotiator.negotiate(any(), any(), any()))
                .thenReturn(new NegotiationResult(overruled, List.of(critical), List.of(critical)));
        when(evaluationInterpreter.interpret(any(), any(), anyBoolean())).thenReturn(
                new EvaluationInterpreter.InterpretationResult(
                        0.82, null, null, false, MutationAcceptanceState.REJECTED, false,
                        GoalProgressState.STAGNATING));
    }

    private void scoringAndProblemsAreUneventful() {
        when(scoringService.computeScores(anyString(), any(), any(), any(), any()))
                .thenReturn(new ScoringResult(0.82, 0.82, null, null, Map.of(), List.of(),
                        Map.of(), Map.of(), "goal-1"));
        when(problemDetector.detectAndUpdate(any(), any(), any())).thenReturn(ProblemContext.empty());
    }

    /**
     * The DoD case: a semantic CRITICAL rejection on a candidate whose compile probe PASSED. The
     * exclusion is for build failures, and this is not one, so the authored reason must travel.
     */
    @Test
    void semanticCriticalRejection_withAPassingProbe_relaysItsAuthoredReason() {
        probeCompilesCleanly();
        llmAccepts();
        heuristicOverrulesAtCritical();
        scoringAndProblemsAreUneventful();

        CycleContextData out = agent.execute(contextWithACandidate());

        assertThat(out.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(out.rejectionFeedback())
                .as("a semantic rejection's diagnosis must reach the next evolution prompt")
                .isNotNull()
                .isNotEmpty();
        assertThat(String.join("\n", out.rejectionFeedback()))
                .contains("parallel store introduced")
                .contains("BookRepository:12");
    }

    /**
     * The counterpart, so the exclusion is pinned rather than merely absent: a gate short-circuit
     * (compile error, files rolled back off disk) is a BUILD failure and relays nothing, because
     * the compile diagnostics reach the next prompt through their own block.
     */
    @Test
    void buildFailureRejection_relaysNothing_becauseTheDiagnosticsTravelSeparately() {
        when(compileProbeService.probe(any())).thenReturn(new CompileProbeService.ProbeResult(
                new BuildOutcome(BuildResult.COMPILE_ERROR, "cannot find symbol", 0L, "", "cycle-t1ga"), null));
        scoringAndProblemsAreUneventful();
        when(heuristicNegotiator.negotiate(any(), any(), any())).thenAnswer(inv -> {
            EvaluationOutcome original = inv.getArgument(1);
            return new NegotiationResult(original, List.of(), List.of());
        });
        when(evaluationInterpreter.interpret(any(), any(), anyBoolean())).thenReturn(
                new EvaluationInterpreter.InterpretationResult(
                        0.0, null, null, false, MutationAcceptanceState.REJECTED, false,
                        GoalProgressState.NOT_EVALUATED));

        CycleContextData out = agent.execute(contextWithACandidate());

        assertThat(out.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(out.rejectionFeedback()).isNullOrEmpty();
    }

    /**
     * T1-GA, the run's actual shape and the reason it ground for fifteen minutes. The probe FAILED
     * to compile, so the files were rolled back — and negotiation still ran, so
     * {@code StoreCoherenceHeuristic} separately found a parallel store and its text REPLACED the
     * outcome's "Compile probe failed" message. The rejection therefore looked semantic in the
     * record while the exclusion, which keys on the probe, suppressed the whole relay.
     *
     * <p>The compile diagnostics travel through their own block, so the loop fixed the compile
     * error every cycle. It never heard about the parallel store, and re-proposed it seven times.
     */
    @Test
    void buildFailure_stillRelaysAHeuristicFindingAboutSomethingElse() {
        when(compileProbeService.probe(any())).thenReturn(new CompileProbeService.ProbeResult(
                new BuildOutcome(BuildResult.COMPILE_ERROR, "cannot find symbol: NoSuchElementException",
                        0L, "", "cycle-t1ga"), null));
        scoringAndProblemsAreUneventful();
        heuristicOverrulesAtCritical();

        CycleContextData out = agent.execute(contextWithACandidate());

        assertThat(out.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(out.rejectionFeedback())
                .as("a defect the compile output cannot express must survive a build failure")
                .isNotNull()
                .isNotEmpty();
        String relayed = String.join("\n", out.rejectionFeedback());
        assertThat(relayed).contains("parallel store introduced").contains("BookRepository:12");
        assertThat(relayed)
                .as("the compile failure itself still travels through the diagnostics block, not this one")
                .doesNotContain("Compile probe failed");
    }

    /** Sanity: the critical opinion really is at CRITICAL, so the fixture reproduces the run. */
    @Test
    void theFixtureOpinionIsActuallyCritical() {
        assertThat(ConfidenceLevel.fromScore(0.90)).isEqualTo(ConfidenceLevel.CRITICAL);
    }
}
