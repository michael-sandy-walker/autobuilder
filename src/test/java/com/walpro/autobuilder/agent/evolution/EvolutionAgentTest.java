package com.walpro.autobuilder.agent.evolution;

import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.graph.evaluation.EvaluationGraphFacade;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.ProjectDiffService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.service.FingerprintService;
import com.walpro.autobuilder.core.service.MutationApplier;
import com.walpro.autobuilder.graph.evolution.EvolutionGraphService;
import com.walpro.autobuilder.model.GoalProgressState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * High-level behavior tests for EvolutionAgent (pipeline + signals),
 * complementary to EvolutionAgentDiffPathTest and EvolutionAgentFingerprintTest.
 */
@ExtendWith(MockitoExtension.class)
class EvolutionAgentTest {

    @Mock
    private EvolutionLLMAdapter evolutionAdapter;

    @Mock
    private MutationApplier mutationApplier;

    @Mock
    private FingerprintService fingerprintService;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ProjectDiffService projectDiffService;
    @Mock private EvolutionGraphService evolutionGraphService;
    @Mock private EvaluationGraphFacade evaluationGraphFacade;

    @Mock
    private ProjectDiffResult diffResult;

    private EvolutionAgent newAgent() {
        return new EvolutionAgent(
                evolutionAdapter,
                mutationApplier,
                fingerprintService,
                projectDiffService,
                evolutionGraphService,
                evaluationGraphFacade
        );
    }

    private CycleContextData baseContext() {
        return CycleContextData.builder()
                .cycleName("cycle-1")
                .previousAcceptedCycleName(null)
                .outputPath("build/out")
                .previousOutput(Map.of())
                .projectDiff(new ProjectDiffResult(null, Map.of(), Set.of()))
                .build();
    }

    /**
     * Basic evolve() pipeline:
     *  - calls evolutionAgent.evolve(...)
     *  - writes evolutionOutcome and lastEvolutionOutcome
     *  - sets EVOLUTION_OCCURRED when there are changes
     *  - computes and stores projectDiff
     */
    @Test
    void evolve_withChanges_setsEvolutionOccurredAndStoresOutcomeAndDiff() throws Exception {
        EvolutionAgent agent = newAgent();

        CycleContextData ctx = baseContext();

        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(new FileMutation("src/Main.java", "class Main {}")),
                List.of(),
                "changed 1 file",
                "#raw"
        );

        when(evolutionAdapter.evolve(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData c = invocation.getArgument(0);
                    return c.withEvolutionOutcome(outcome);
                });

        when(fingerprintService.computeEvolutionFingerprint(any()))
                .thenReturn("fp-1");

        Path outPath = Path.of("build/out");
        when(mutationApplier.resolveOutputPath()).thenReturn(outPath);
        doNothing().when(mutationApplier).applyRegularMutations(any(CycleContextData.class));

        when(projectDiffService.computeDiff(null, outPath)).thenReturn(diffResult);

        CycleContextData result = agent.execute(ctx);

        // evolution agent was called
        verify(evolutionAdapter).evolve(any(CycleContextData.class));

        // outcome is attached
        assertThat(result.evolutionOutcome()).isEqualTo(outcome);
        assertThat(result.lastEvolutionOutcome()).isEqualTo(outcome);

        // EVOLUTION_OCCURRED signal present because there are changes
        assertThat(result.signals().keySet())
                .anyMatch(t -> t.equals(BasicSignals.EVOLUTION_OCCURRED));

        // diff computed and stored
        verify(projectDiffService).computeDiff(null, outPath);
        assertThat(result.projectDiff()).isEqualTo(diffResult);
    }

    @Test
    void evolve_withoutChanges_doesNotSetEvolutionOccurredSignal() throws Exception {
        EvolutionAgent agent = newAgent();

        CycleContextData ctx = baseContext();

        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(),       // no added/changed files
                List.of(),       // no deletions
                "no-op",
                "#raw"
        );

        when(evolutionAdapter.evolve(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData c = invocation.getArgument(0);
                    return c.withEvolutionOutcome(outcome);
                });

        when(fingerprintService.computeEvolutionFingerprint(any()))
                .thenReturn("fp-1");

        Path outPath = Path.of("build/out");
        when(mutationApplier.resolveOutputPath()).thenReturn(outPath);
        doNothing().when(mutationApplier).applyRegularMutations(any(CycleContextData.class));
        when(projectDiffService.computeDiff(null, outPath)).thenReturn(diffResult);

        CycleContextData result = agent.execute(ctx);

        assertThat(result.signals().keySet())
                .noneMatch(t -> t.equals(BasicSignals.EVOLUTION_OCCURRED));
    }

    @Test
    void execute_afterStagnationInsight_usesDivergentEvolutionOnce() throws Exception {
        EvolutionAgent agent = newAgent();

        when(evolutionAdapter.evolve(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData ctx = invocation.getArgument(0);
                    return ctx.withEvolutionOutcome(new EvolutionOutcome(
                            List.of(new FileMutation("src/Main.java", "class Main {}")),
                            List.of(),
                            "normal",
                            "#normal"
                    ));
                });

        when(evolutionAdapter.evolveDivergent(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData ctx = invocation.getArgument(0);
                    return ctx.withEvolutionOutcome(new EvolutionOutcome(
                            List.of(new FileMutation("src/Main.java", "class Main { /* divergent */ }")),
                            List.of(),
                            "divergent",
                            "#divergent"
                    ));
                });

        when(fingerprintService.computeEvolutionFingerprint(any()))
                .thenReturn("fp-1");
        when(mutationApplier.resolveOutputPath())
                .thenReturn(Path.of("build/out"));
        when(projectDiffService.computeDiff(any(), any()))
                .thenReturn(new ProjectDiffResult(null, Map.of(), Set.of()));

        CycleContextData context = baseContext()
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "test"));

        // simulate stagnation
        context = context.withSignal(Signal.of(BasicSignals.STAGNATION, 1.0, 0.1, "stagnating"));

        // first execute() → divergent
        CycleContextData first = agent.execute(context);
        assertThat(first.evolutionOutcome().rawResponse()).isEqualTo("#divergent");
        verify(evolutionAdapter, times(1)).evolveDivergent(any(CycleContextData.class));
        verify(evolutionAdapter, never()).evolve(any(CycleContextData.class));

        // second execute() → normal again (flag consumed)
        CycleContextData second = agent.execute(first);
        assertThat(second.evolutionOutcome().rawResponse()).isEqualTo("#normal");
        verify(evolutionAdapter, times(1)).evolveDivergent(any(CycleContextData.class));
        verify(evolutionAdapter, atLeastOnce()).evolve(any(CycleContextData.class));
    }

    @Test
    void execute_usesDivergentWhenEvaluationInterpretationIsRegressing() throws Exception {
        EvolutionAgent agent = newAgent();

        when(evolutionAdapter.evolveDivergent(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData ctx = invocation.getArgument(0);
                    return ctx.withEvolutionOutcome(new EvolutionOutcome(
                            List.of(new FileMutation("src/Main.java", "class Main { /* divergent */ }")),
                            List.of(),
                            "divergent",
                            "#divergent"
                    ));
                });

        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-1");
        when(mutationApplier.resolveOutputPath()).thenReturn(Path.of("build/out"));
        when(projectDiffService.computeDiff(any(), any()))
                .thenReturn(new ProjectDiffResult(null, Map.of(), Set.of()));

        // mock interpretation -> REGRESSING
        EvaluationInterpreter.InterpretationResult interpretation = mock(EvaluationInterpreter.InterpretationResult.class);
        when(interpretation.goalProgress()).thenReturn(GoalProgressState.REGRESSING);

        CycleContextData context = baseContext()
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "test"))
                .withEvaluationInterpretation(interpretation);

        CycleContextData result = agent.execute(context);

        assertThat(result.evolutionOutcome().rawResponse()).isEqualTo("#divergent");
        verify(evolutionAdapter, times(1)).evolveDivergent(any(CycleContextData.class));
        verify(evolutionAdapter, never()).evolve(any(CycleContextData.class));
    }

    @Test
    void execute_usesNormalEvolutionWhenInterpretationIsNotRegressing() throws Exception {
        EvolutionAgent agent = newAgent();

        when(evolutionAdapter.evolve(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData ctx = invocation.getArgument(0);
                    return ctx.withEvolutionOutcome(new EvolutionOutcome(
                            List.of(new FileMutation("src/Main.java", "class Main {}")),
                            List.of(),
                            "normal",
                            "#normal"
                    ));
                });

        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-1");
        when(mutationApplier.resolveOutputPath()).thenReturn(Path.of("build/out"));
        when(projectDiffService.computeDiff(any(), any()))
                .thenReturn(new ProjectDiffResult(null, Map.of(), Set.of()));

        EvaluationInterpreter.InterpretationResult interpretation = mock(EvaluationInterpreter.InterpretationResult.class);
        when(interpretation.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);

        CycleContextData context = baseContext()
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "test"))
                .withEvaluationInterpretation(interpretation);

        CycleContextData result = agent.execute(context);

        assertThat(result.evolutionOutcome().rawResponse()).isEqualTo("#normal");
        verify(evolutionAdapter, times(1)).evolve(any(CycleContextData.class));
        verify(evolutionAdapter, never()).evolveDivergent(any());
    }

    // ── T2-CC: #NoDiff server-side surface-scan guard ────────────────────────

    @Test
    void noDiff_withNoGraphEvaluation_requestsVerifyInPlace() throws Exception {
        // LLM says #NoDiff and the graph has NO prior evaluation for this subgoal.
        // This is the cross-subgoal deferred-merge case: the implementation was delivered by
        // another subgoal's merge.  We must not force divergence (the LLM is correct); instead
        // supply a synthetic empty diff so the evaluation agent can verify in place.
        EvolutionAgent agent = newAgent();

        when(evolutionAdapter.evolve(any())).thenAnswer(inv -> {
            CycleContextData c = inv.getArgument(0);
            return c.withEvolutionOutcome(
                    new EvolutionOutcome(List.of(), List.of(), "#NoDiff — implementation appears complete", "#NoDiff", false, true));
        });
        when(evaluationGraphFacade.findLatestEvaluationStatusForSubgoal("sub-1")).thenReturn(null);

        CycleContextData ctx = baseContext().withCurrentSubGoalId("sub-1");

        CycleContextData result = agent.execute(ctx);

        assertThat(result.hasSignal(BasicSignals.EVOLUTION_NOOP))
                .as("cross-subgoal #NoDiff must NOT emit EVOLUTION_NOOP before first verification")
                .isFalse();
        assertThat(result.hasSignal(BasicSignals.REPETITION_DETECTED))
                .as("REPETITION_DETECTED keeps evaluation eligible for first-time fingerprint")
                .isTrue();
        assertThat(result.hasSignal(BasicSignals.DIVERGENCE_REQUEST))
                .as("no DIVERGENCE_REQUEST — LLM is correct, let evaluation verify")
                .isFalse();
        assertThat(result.evolutionVerifyInPlace())
                .as("verifyInPlace flag must be set so evaluation runs on synthetic empty diff")
                .isTrue();
        assertThat(result.projectDiff())
                .as("synthetic empty diff must be populated so evaluation eligibility passes")
                .isNotNull();
    }

    @Test
    void noDiff_withRejectedGraphEvaluation_rejectsUnverifiedNoDiffAndForcesDivergence() throws Exception {
        // LLM says #NoDiff but the graph shows last evaluation was REJECTED → unverified.
        EvolutionAgent agent = newAgent();

        when(evolutionAdapter.evolve(any())).thenAnswer(inv -> {
            CycleContextData c = inv.getArgument(0);
            return c.withEvolutionOutcome(
                    new EvolutionOutcome(List.of(), List.of(), "#NoDiff — implementation appears complete", "#NoDiff", false, true));
        });
        when(evaluationGraphFacade.findLatestEvaluationStatusForSubgoal("sub-1")).thenReturn("REJECTED");

        CycleContextData ctx = baseContext().withCurrentSubGoalId("sub-1");

        CycleContextData result = agent.execute(ctx);

        assertThat(result.hasSignal(BasicSignals.EVOLUTION_NOOP)).isFalse();
        assertThat(result.hasSignal(BasicSignals.REPETITION_DETECTED)).isTrue();
        assertThat(result.hasSignal(BasicSignals.DIVERGENCE_REQUEST)).isTrue();
    }

    @Test
    void noDiff_withAcceptedGraphEvaluation_emitsEvolutionNoop() throws Exception {
        // LLM says #NoDiff and the graph confirms last evaluation was ACCEPTED → trust it.
        EvolutionAgent agent = newAgent();

        when(evolutionAdapter.evolve(any())).thenAnswer(inv -> {
            CycleContextData c = inv.getArgument(0);
            return c.withEvolutionOutcome(
                    new EvolutionOutcome(List.of(), List.of(), "#NoDiff — implementation appears complete", "#NoDiff", false, true));
        });
        when(evaluationGraphFacade.findLatestEvaluationStatusForSubgoal("sub-1")).thenReturn("ACCEPTED");

        CycleContextData ctx = baseContext().withCurrentSubGoalId("sub-1");

        CycleContextData result = agent.execute(ctx);

        assertThat(result.hasSignal(BasicSignals.EVOLUTION_NOOP))
                .as("#NoDiff confirmed by graph must emit EVOLUTION_NOOP")
                .isTrue();
        assertThat(result.hasSignal(BasicSignals.REPETITION_DETECTED)).isFalse();
        assertThat(result.hasSignal(BasicSignals.DIVERGENCE_REQUEST)).isFalse();
    }

    // ── T2-AO: DIVERGENCE_REQUEST lifecycle ──────────────────────────────────

    @Test
    void divergenceRequest_consumed_when_fingerprintChanges() throws Exception {
        EvolutionAgent agent = newAgent();

        // LLM produces a new fingerprint → divergence happened → signal should be consumed
        when(evolutionAdapter.evolveDivergent(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData c = invocation.getArgument(0);
                    return c.withEvolutionOutcome(new EvolutionOutcome(
                            List.of(new FileMutation("src/A.java", "new content")), List.of(), "diverged", "#raw"));
                });
        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-new");
        when(mutationApplier.resolveOutputPath()).thenReturn(Path.of("build/out"));
        doNothing().when(mutationApplier).applyRegularMutations(any());
        when(projectDiffService.computeDiff(any(), any()))
                .thenReturn(new ProjectDiffResult(null, Map.of(), Set.of()));

        CycleContextData ctx = baseContext()
                .withLastEvolutionFingerprint("fp-old")  // different from fp-new → actually diverged
                .withSignal(Signal.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, 0.10, "test"));

        CycleContextData result = agent.execute(ctx);

        assertThat(result.hasSignal(BasicSignals.DIVERGENCE_REQUEST))
                .as("DIVERGENCE_REQUEST should be consumed after actual divergence")
                .isFalse();
    }

    @Test
    void divergenceRequest_retained_when_fingerprintUnchanged() throws Exception {
        EvolutionAgent agent = newAgent();

        // LLM produces the same fingerprint → no real divergence → signal should be kept alive
        when(evolutionAdapter.evolveDivergent(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData c = invocation.getArgument(0);
                    return c.withEvolutionOutcome(new EvolutionOutcome(
                            List.of(new FileMutation("src/A.java", "same content")), List.of(), "no change", "#raw"));
                });
        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-same");
        when(mutationApplier.resolveOutputPath()).thenReturn(Path.of("build/out"));
        doNothing().when(mutationApplier).applyRegularMutations(any());
        when(projectDiffService.computeDiff(any(), any()))
                .thenReturn(new ProjectDiffResult(null, Map.of(), Set.of()));

        CycleContextData ctx = baseContext()
                .withLastEvolutionFingerprint("fp-same")  // same as produced → no divergence
                .withSignal(Signal.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, 0.10, "test"));

        CycleContextData result = agent.execute(ctx);

        assertThat(result.hasSignal(BasicSignals.DIVERGENCE_REQUEST))
                .as("DIVERGENCE_REQUEST should be kept when LLM did not actually diverge")
                .isTrue();
    }

}
