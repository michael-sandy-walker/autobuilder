package com.walpro.autobuilder.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.walpro.autobuilder.agent.evaluation.EvaluationAgent;
import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter;
import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringService;
import com.walpro.autobuilder.agent.evolution.EvolutionAgent;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.ProjectDiffService;
import com.walpro.autobuilder.core.coordinator.EvaluationCoordinator;
import com.walpro.autobuilder.core.coordinator.EvolutionCoordinator;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalBus;
import com.walpro.autobuilder.core.messaging.MessageBus;
import com.walpro.autobuilder.core.service.FingerprintService;
import com.walpro.autobuilder.core.service.MutationApplier;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdaptiveExecutionEngineEvolutionIntegrationTest {

    @Mock
    private EvolutionAgent evolutionAgent;

    @Mock
    private MutationApplier mutationApplier;

    @Mock
    private FingerprintService fingerprintService;

    @Mock
    private ProjectDiffService projectDiffService;

    @Mock
    private EvaluationAgent evaluationAgent;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ScoringService scoringService;

    @Mock
    private EvaluationInterpreter evaluationInterpreter;

    @Mock
    private SignalBus signalBus;

    @Mock
    private MessageBus messageBus;

    @Test
    void runCycle_executesEvolutionAndEvaluationCoordinators_andUpdatesContext() throws Exception {
        // --- Real coordinators, mocked internals ---
        EvolutionCoordinator evolutionCoordinator = new EvolutionCoordinator(
                evolutionAgent,
                mutationApplier,
                fingerprintService,
                projectDiffService,
                signalBus,
                messageBus
        );

        EvaluationCoordinator evaluationCoordinator = new EvaluationCoordinator(
                evaluationAgent,
                scoringService,
                evaluationInterpreter,
                signalBus,
                messageBus
        );

        // Real coordination strategy and engine
        SequentialCoordinationStrategy strategy = new SequentialCoordinationStrategy();
        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(evolutionCoordinator, evaluationCoordinator),
                strategy
        );

        // --- Initial context setup ---
        ProjectDiffResult initialDiff = new ProjectDiffResult(
                null,
                Map.of("src/main/java/App.java", "changed"),
                Set.of()
        );

        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-1")
                .outputPath("build/out")
                .previousOutput(Map.of())  // avoid null in scoringService
                .projectDiff(initialDiff)  // so evaluation can run even if it goes first
                .build();

        // Make both coordinators eligible in this cycle
        context = context
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 0.8, 0.05, "test"))
                .withSignal(Signal.of(BasicSignals.ROLE_EVALUATION, 0.8, 0.05, "test"));

        // --- Evolution stubbing ---
        EvolutionOutcome evolutionOutcome = new EvolutionOutcome(
                List.of(new FileMutation("src/main/java/App.java", "class App {}")),
                List.of(),
                "Parsed 1 changed file(s), 0 deleted file(s).",
                "#raw-evolution"
        );

        when(evolutionAgent.evolve(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData ctx = invocation.getArgument(0);
                    return ctx.withEvolutionOutcome(evolutionOutcome);
                });

        when(fingerprintService.computeFingerprint(any()))
                .thenReturn("fp-1");

        ProjectDiffResult diffResult = new ProjectDiffResult(
                null,
                Map.of("src/main/java/App.java", "changed"),
                Set.of()
        );

        when(mutationApplier.resolveOutputPath())
                .thenReturn(Path.of("build/out"));

        when(projectDiffService.computeDiff(any(), any()))
                .thenReturn(diffResult);

        // --- Evaluation stubbing ---

        // Evaluation outcome from the agent
        EvaluationOutcome evalOutcome = new EvaluationOutcome(
                0.9,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "Looks good.",
                null
        );

        when(evaluationAgent.evaluate(any(CycleContextData.class)))
                .thenReturn(evalOutcome);

        // Interpretation result
        InterpretationResult interpretation = org.mockito.Mockito.mock(InterpretationResult.class);
        when(interpretation.success()).thenReturn(true);
        when(interpretation.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(interpretation.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);

        when(evaluationInterpreter.interpret(
                any(EvaluationOutcome.class),
                anyDouble(),
                anyBoolean()
        )).thenReturn(interpretation);

        // ScoreHistoryService and scoring result are handled by deep stubs.
        // We only care that computeScores() is called and doesn't blow up.

        // --- Act ---
        CycleContextData result = engine.runCycle(context);

        // --- Assert: both coordinators have run ---

        // Evolution path
        verify(evolutionAgent, atLeastOnce()).evolve(any(CycleContextData.class));
        assertThat(result.evolutionOutcome())
                .as("Evolution outcome should be attached to the context")
                .isEqualTo(evolutionOutcome);

        assertThat(result.projectDiff())
                .as("Project diff should be computed and stored")
                .isEqualTo(diffResult);

        assertThat(result.signals())
                .as("Signals map should not be null")
                .isNotNull();
        assertThat(result.signals().keySet())
                .anyMatch(signalType -> signalType.equals(BasicSignals.EVOLUTION_OCCURRED));

        // Evaluation path
        verify(evaluationAgent, atLeastOnce()).evaluate(any(CycleContextData.class));
        verify(scoringService, atLeastOnce()).computeScores(
                anyString(),
                any(),
                anyMap(),
                anyMap(),
                anySet()
        );
        verify(evaluationInterpreter, atLeastOnce()).interpret(
                any(EvaluationOutcome.class),
                anyDouble(),
                anyBoolean()
        );

        assertThat(result.evaluationOutcome())
                .as("Evaluation outcome should be attached to the context")
                .isEqualTo(evalOutcome);
    }
}
