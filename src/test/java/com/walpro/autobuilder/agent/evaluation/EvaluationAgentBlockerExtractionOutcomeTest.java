package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.analysis.heuristic.EvaluationHeuristic;
import com.walpro.autobuilder.analysis.heuristic.HeuristicNegotiator;
import com.walpro.autobuilder.analysis.problem.ProblemDetector;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.CompileProbeService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.merge.MergeRollbackService;
import com.walpro.autobuilder.core.mutation.DeferredMutationLedger;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.graph.evaluation.EvaluationActionRegistrar;
import com.walpro.autobuilder.graph.evaluation.EvaluationGraphFacade;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * T4-BF tests 7 / 8 / 9: per-rejection {@code blocker_extraction_outcome} event emission
 * through {@link EvaluationAgent#tryDeferRejectedMutation}. Each ticket-specified
 * {@code Outcome.Result} variant is stubbed by feeding a tailored stderr fixture; the
 * test then asserts exactly one event per call with matching payload fields.
 *
 * <p>The {@link DeferredMutationLedger} is mocked but its calls are not asserted —
 * deferral persistence is covered by {@code Cycle1779492075276ReplayTest}; this suite is
 * exclusively about the observability surface.
 */
@ExtendWith(MockitoExtension.class)
class EvaluationAgentBlockerExtractionOutcomeTest {

    @Mock private EvaluationLLMAdapter evaluationAdapter;
    @Mock private com.walpro.autobuilder.agent.evaluation.score.ScoringService scoringService;
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
    @Mock private BehaviourLogger behaviourLogger;
    @Mock private DeferredMutationLedger deferredMutationLedger;

    private EvaluationAgent agent;

    @BeforeEach
    void setUp() {
        agent = new EvaluationAgent(
                evaluationAdapter,
                scoringService,
                evaluationInterpreter,
                problemDetector,
                heuristicNegotiator,
                List.<EvaluationHeuristic>of(),
                graphFacade,
                evaluationActionRegistrar,
                goalPlannerService,
                mutationSnapshotService,
                compileProbeService,
                mergeRollbackService,
                fileService
        );
        agent.setBehaviourLogger(behaviourLogger);
        agent.setDeferredMutationLedger(deferredMutationLedger);
    }

    private CycleContextData ctxWithOneMutation() {
        return CycleContextData.builder()
                .cycleName("cycle-X")
                .currentSubGoalId("sg-X")
                .lastAttemptId("attempt-X")
                .evolutionOutcome(new EvolutionOutcome(
                        List.of(new FileMutation("src/A.java", "class A {}")),
                        List.of(), "s", "r"))
                .build();
    }

    private CompileProbeService.ProbeResult probeWithStderr(String stderr) {
        return new CompileProbeService.ProbeResult(
                new BuildOutcome(BuildResult.COMPILE_ERROR, stderr, 100L, "mvn compile", "cycle-X"),
                null);
    }

    // ---------------------------------------------------------------------------------
    // Test 7 — one event per result variant with matching payload fields
    // ---------------------------------------------------------------------------------

    @Test
    void deferredVariant_emitsOneEvent_withDeferrableCount() {
        String stderr = """
                [ERROR] /A.java:[10,1] cannot find symbol
                [ERROR]   symbol:   method setReturned(boolean)
                [ERROR]   location: class Loan
                """;

        agent.tryDeferRejectedMutation(ctxWithOneMutation(), probeWithStderr(stderr), 0.5);

        verify(behaviourLogger, times(1)).blockerExtractionOutcome(
                eq("cycle-X"), eq("attempt-X"), eq("sg-X"),
                eq("DEFERRED"),
                eq(1),                                // deferrableCount = blockers.size()
                eq(0),                                // authorErrorCount
                eq(0),                                // unknownCount
                anyList(),
                isNull()                              // firstUnknownLine
        );
    }

    @Test
    void refusedAuthorErrorVariant_emitsOneEvent_withAuthorErrorCount() {
        String stderr = """
                [ERROR] /A.java:[10,1] cannot find symbol
                [ERROR]   symbol:   method setReturned(boolean)
                [ERROR]   location: class Loan
                [ERROR] /B.java:[20,5] non-static method getBookById(Long) cannot be referenced from a static context
                """;

        agent.tryDeferRejectedMutation(ctxWithOneMutation(), probeWithStderr(stderr), 0.5);

        verify(behaviourLogger, times(1)).blockerExtractionOutcome(
                eq("cycle-X"), eq("attempt-X"), eq("sg-X"),
                eq("REFUSED_AUTHOR_ERROR"),
                eq(1),
                eq(1),
                eq(0),
                anyList(),
                isNull()
        );
        // No deferral persisted when the result is not DEFERRED.
        verifyNoInteractions(deferredMutationLedger);
    }

    @Test
    void refusedUnknownVariant_emitsOneEvent_withFirstUnknownLine() {
        String stderr = """
                [ERROR] /A.java:[10,1] cannot find symbol
                [ERROR]   symbol:   class LoanNotFoundException
                [ERROR] /B.java:[20,5] this is some entirely novel diagnostic shape the classifier doesn't know
                """;

        agent.tryDeferRejectedMutation(ctxWithOneMutation(), probeWithStderr(stderr), 0.5);

        ArgumentCaptor<String> firstUnknownCaptor = ArgumentCaptor.forClass(String.class);
        verify(behaviourLogger, times(1)).blockerExtractionOutcome(
                eq("cycle-X"), eq("attempt-X"), eq("sg-X"),
                eq("REFUSED_UNKNOWN"),
                eq(1),               // deferrableCount = 1 (the LoanNotFoundException blocker)
                eq(0),
                eq(1),               // unknownCount
                anyList(),
                firstUnknownCaptor.capture()
        );
        assertThat(firstUnknownCaptor.getValue())
                .contains("this is some entirely novel diagnostic shape the classifier doesn't know");
        verifyNoInteractions(deferredMutationLedger);
    }

    @Test
    void noDeferrableVariant_emitsOneEvent_withZeroCounts() {
        // Banner-only stderr (every line filtered) → NO_DEFERRABLE.
        String stderr = """
                [ERROR] -> [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
                [ERROR] For more information about the errors and possible solutions, please read the following articles:
                """;

        agent.tryDeferRejectedMutation(ctxWithOneMutation(), probeWithStderr(stderr), 0.5);

        verify(behaviourLogger, times(1)).blockerExtractionOutcome(
                eq("cycle-X"), eq("attempt-X"), eq("sg-X"),
                eq("NO_DEFERRABLE"),
                eq(0),
                eq(0),
                eq(0),
                anyList(),
                isNull()
        );
        verifyNoInteractions(deferredMutationLedger);
    }

    // ---------------------------------------------------------------------------------
    // Test 8 — errorShapes deduplicated + prefix-normalised in the event payload
    // ---------------------------------------------------------------------------------

    @Test
    void incompatibleTypes_collapseToSingleNormalisedShape() {
        String stderr = """
                [ERROR] /A.java:[1,1] incompatible types: int cannot be converted to String
                [ERROR] /B.java:[2,2] incompatible types: long cannot be converted to Integer
                [ERROR] /C.java:[3,3] incompatible types: double cannot be converted to Float
                """;

        agent.tryDeferRejectedMutation(ctxWithOneMutation(), probeWithStderr(stderr), 0.5);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> shapesCaptor = ArgumentCaptor.forClass(List.class);
        verify(behaviourLogger, times(1)).blockerExtractionOutcome(
                anyString(), anyString(), anyString(),
                eq("REFUSED_AUTHOR_ERROR"),
                anyInt(), anyInt(), anyInt(),
                shapesCaptor.capture(),
                isNull()
        );
        assertThat(shapesCaptor.getValue())
                .as("three concrete incompatible-types lines collapse to one normalised shape")
                .containsExactly("incompatible types: ... cannot be converted to ...");
    }

    // ---------------------------------------------------------------------------------
    // Test 9 — firstUnknownLine present only on REFUSED_UNKNOWN
    // ---------------------------------------------------------------------------------

    @Test
    void firstUnknownLine_absentOnDeferred() {
        String stderr = """
                [ERROR] /A.java:[10,1] cannot find symbol
                [ERROR]   symbol:   class LoanNotFoundException
                """;
        agent.tryDeferRejectedMutation(ctxWithOneMutation(), probeWithStderr(stderr), 0.5);
        verify(behaviourLogger).blockerExtractionOutcome(
                anyString(), anyString(), anyString(),
                eq("DEFERRED"),
                anyInt(), anyInt(), anyInt(),
                anyList(), isNull()
        );
    }

    @Test
    void firstUnknownLine_absentOnRefusedAuthorError() {
        String stderr = "[ERROR] /A.java:[1,1] incompatible types: int cannot be converted to String\n";
        agent.tryDeferRejectedMutation(ctxWithOneMutation(), probeWithStderr(stderr), 0.5);
        verify(behaviourLogger).blockerExtractionOutcome(
                anyString(), anyString(), anyString(),
                eq("REFUSED_AUTHOR_ERROR"),
                anyInt(), anyInt(), anyInt(),
                anyList(), isNull()
        );
    }

    @Test
    void firstUnknownLine_absentOnNoDeferrable() {
        String stderr = "[ERROR] -> [Help 1]\n";
        agent.tryDeferRejectedMutation(ctxWithOneMutation(), probeWithStderr(stderr), 0.5);
        verify(behaviourLogger).blockerExtractionOutcome(
                anyString(), anyString(), anyString(),
                eq("NO_DEFERRABLE"),
                anyInt(), anyInt(), anyInt(),
                anyList(), isNull()
        );
    }
}
