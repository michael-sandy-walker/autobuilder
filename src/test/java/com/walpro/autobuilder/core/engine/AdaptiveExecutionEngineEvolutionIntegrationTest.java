package com.walpro.autobuilder.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.walpro.autobuilder.agent.arbitration.PlanArbiter;
import com.walpro.autobuilder.agent.evaluation.EvaluationAgent;
import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter;
import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringService;
import com.walpro.autobuilder.agent.evolution.EvolutionAgent;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.ProjectDiffService;
import com.walpro.autobuilder.core.coordinator.EvaluationCoordinator;
import com.walpro.autobuilder.core.coordinator.EvolutionCoordinator;
import com.walpro.autobuilder.core.execution.CoordinatorExecutionAgent;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalBus;
import com.walpro.autobuilder.core.messaging.MessageBus;
import com.walpro.autobuilder.core.service.FingerprintService;
import com.walpro.autobuilder.core.service.MutationApplier;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdaptiveExecutionEngineEvolutionIntegrationTest {

    @Mock private EvolutionAgent evolutionAgent;
    @Mock private MutationApplier mutationApplier;
    @Mock private FingerprintService fingerprintService;
    @Mock private ProjectDiffService projectDiffService;

    @Mock private EvaluationAgent evaluationAgent;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ScoringService scoringService;

    @Mock private EvaluationInterpreter evaluationInterpreter;
    @Mock private SignalBus signalBus;
    @Mock private MessageBus messageBus;
    @Mock private GoalPlannerService goalPlannerService;
    @Mock private PlanArbiter planArbiter;

    @Test
    void runCycle_executesEvolutionAndEvaluationCoordinators_andUpdatesContext() throws Exception {
        EvolutionCoordinator evoCoordinator = spy(new EvolutionCoordinator(
                evolutionAgent,
                mutationApplier,
                fingerprintService,
                projectDiffService,
                signalBus,
                messageBus,
                planArbiter
        ));

        EvaluationCoordinator evalCoordinator = spy(new EvaluationCoordinator(
                evaluationAgent,
                scoringService,
                evaluationInterpreter,
                signalBus,
                messageBus,
                goalPlannerService
        ));

        CoordinatorExecutionAgent evoExecAgent =
                new CoordinatorExecutionAgent("evolution", evoCoordinator, BasicSignals.ALL);
        CoordinatorExecutionAgent evalExecAgent =
                new CoordinatorExecutionAgent("evaluation", evalCoordinator, BasicSignals.ALL);

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(evoExecAgent, evalExecAgent),
                new SequentialCoordinationStrategy()
        );

        ProjectDiffResult initialDiff = new ProjectDiffResult(
                null,
                Map.of("src/main/java/App.java", "changed"),
                Set.of()
        );

        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-1")
                .outputPath("build/out")
                .previousOutput(Map.of())
                .projectDiff(initialDiff)
                .build()
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 0.8, 0.05, "test"))
                .withSignal(Signal.of(BasicSignals.ROLE_EVALUATION, 0.8, 0.05, "test"));

        EvolutionOutcome evolutionOutcome = new EvolutionOutcome(
                List.of(),
                List.of(),
                "stub-evolution",
                "#raw-evolution"
        );

        ProjectDiffResult diffResult = new ProjectDiffResult(
                null,
                Map.of("src/main/java/App.java", "changed"),
                Set.of()
        );

        // IMPORTANT: use doAnswer(...).when(spy)... for spies
        doAnswer(invocation -> {
            CycleContextData in = invocation.getArgument(0);
            CycleContextData out = in.withEvolutionOutcome(evolutionOutcome);
            out = out.withProjectDiff(diffResult);
            out = out.withSignal(Signal.of(BasicSignals.EVOLUTION_OCCURRED, 1.0, 0.0, "test"));
            return out;
        }).when(evoCoordinator).execute(any(CycleContextData.class));

        EvaluationOutcome evalOutcome = new EvaluationOutcome(
                0.9,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "Looks good.",
                null
        );

        doAnswer(invocation -> {
            CycleContextData in = invocation.getArgument(0);
            return in.withEvaluationOutcome(evalOutcome);
        }).when(evalCoordinator).execute(any(CycleContextData.class));

        CycleContextData result = engine.runCycle(context);

        verify(evoCoordinator, atLeastOnce()).execute(any(CycleContextData.class));
        verify(evalCoordinator, atLeastOnce()).execute(any(CycleContextData.class));

        assertThat(result).isNotNull();
        assertThat(result.evolutionOutcome()).isEqualTo(evolutionOutcome);
        assertThat(result.projectDiff()).isEqualTo(diffResult);
        assertThat(result.evaluationOutcome()).isEqualTo(evalOutcome);
        assertThat(result.signals().keySet()).contains(BasicSignals.EVOLUTION_OCCURRED);
    }
}
