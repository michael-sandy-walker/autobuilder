package com.walpro.autobuilder.agent.evolution;

import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.ProjectDiffService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.service.FingerprintService;
import com.walpro.autobuilder.core.service.MutationApplier;
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

    @Mock
    private ProjectDiffResult diffResult;

    private EvolutionAgent newAgent() {
        return new EvolutionAgent(
                evolutionAdapter,
                mutationApplier,
                fingerprintService,
                projectDiffService
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

    @Test
    void canExecute_requiresGoalOrSubGoalEvenWithRoleSignal() {
        EvolutionAgent agent = newAgent();

        CycleContextData noGoalState = CycleContextData.builder()
                .cycleName("cycle-1")
                .build()
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "test"));

        assertThat(agent.canExecute(noGoalState)).isFalse();

        CycleContextData withGoal = noGoalState.toBuilder()
                .currentGoalId("goal-1")
                .build();

        assertThat(agent.canExecute(withGoal)).isTrue();
    }


}
