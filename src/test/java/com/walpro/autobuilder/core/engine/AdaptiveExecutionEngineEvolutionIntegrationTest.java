package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.agent.evaluation.EvaluationAgent;
import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter;
import com.walpro.autobuilder.agent.evaluation.EvaluationLLMAdapter;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringService;
import com.walpro.autobuilder.agent.evolution.EvolutionAgent;
import com.walpro.autobuilder.agent.evolution.EvolutionLLMAdapter;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.ProjectDiffService;
import com.walpro.autobuilder.analysis.problem.ProblemDetector;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.deliberation.DeliberationDecision;
import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.core.deliberation.DeliberationService;
import com.walpro.autobuilder.core.service.FingerprintService;
import com.walpro.autobuilder.core.service.MutationApplier;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshotService;
import com.walpro.autobuilder.graph.evolution.EvolutionGraphService;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

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
 * Integration-ish test for AdaptiveExecutionEngine using agents.
 */
@ExtendWith(MockitoExtension.class)
class AdaptiveExecutionEngineEvolutionIntegrationTest {

    @Mock private EvolutionLLMAdapter evolutionAdapter;
    @Mock private MutationApplier mutationApplier;
    @Mock private FingerprintService fingerprintService;
    @Mock private ProjectDiffService projectDiffService;
    @Mock private EvolutionGraphService evolutionGraphService;
    @Mock private OrchestrationSnapshotService orchestrationSnapshotService;

    @Mock private EvaluationLLMAdapter evaluationAdapter;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ScoringService scoringService;

    @Mock private EvaluationInterpreter evaluationInterpreter;

    @Mock private DeliberationService deliberationService;
    private final DeliberationExecutionGate deliberationExecutionGate = new DeliberationExecutionGate();

    @Mock
    private ProblemDetector problemDetector;

    @Test
    void runCycle_executesEvolutionAndevaluationAdapters_andUpdatesContext() throws Exception {
        // --- real execution agents (the whole point of the refactor) -----------------
        EvolutionAgent evoExecAgent = new EvolutionAgent(
                evolutionAdapter,
                mutationApplier,
                fingerprintService,
                projectDiffService,
                evolutionGraphService,
                orchestrationSnapshotService
        );

        // If you have an EvaluationExecutionAgent in your refactor, use it here.
        // Replace ctor args with the ones you actually have.
        var evalExecAgent = new EvaluationAgent(
                evaluationAdapter,
                scoringService,
                evaluationInterpreter,
                problemDetector
        );

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(evoExecAgent, evalExecAgent),
                deliberationService,
                deliberationExecutionGate
        );

        // --- context ---------------------------------------------------------------
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
                .currentGoalId("goal-root")
                .currentSubGoalId("subgoal-impl")
                .build()
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 0.8, 0.05, "test"))
                .withSignal(Signal.of(BasicSignals.ROLE_EVALUATION, 0.8, 0.05, "test"));

        EvolutionOutcome evolutionOutcome = new EvolutionOutcome(
                List.of(new com.walpro.autobuilder.agent.evolution.model.FileMutation("src/main/java/App.java", "class App {}")),
                List.of(),
                "stub-evolution",
                "#raw-evolution"
        );

        when(evolutionAdapter.evolve(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.<CycleContextData>getArgument(0).withEvolutionOutcome(evolutionOutcome));

        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-1");

        Path outPath = Path.of("build/out");
        when(mutationApplier.resolveOutputPath()).thenReturn(outPath);
        doNothing().when(mutationApplier).applyRegularMutations(any(CycleContextData.class));

        ProjectDiffResult diffResult = new ProjectDiffResult(
                null,
                Map.of("src/main/java/App.java", "changed"),
                Set.of()
        );
        // adjust arg order if your computeDiff signature is (current, previous) etc.
        when(projectDiffService.computeDiff(any(), any())).thenReturn(diffResult);

        // --- evaluation stubs ------------------------------------------------------
        EvaluationOutcome evalOutcome = new EvaluationOutcome(
                0.9,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "Looks good.",
                null
        );
        when(evaluationAdapter.evaluate(any(CycleContextData.class))).thenReturn(evalOutcome);

        EvaluationInterpreter.InterpretationResult interpretation =
                mock(EvaluationInterpreter.InterpretationResult.class);
        when(interpretation.success()).thenReturn(true);
        when(interpretation.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(evaluationInterpreter.interpret(any(), anyDouble(), anyBoolean()))
                .thenReturn(interpretation);

        // --- deliberation ----------------------------------------------------------
        when(deliberationService.deliberate(any(CycleContextData.class)))
                .thenAnswer(inv -> new DeliberationDecision(
                        inv.getArgument(0),
                        DeliberationResult.noop("test")
                ));

        // --- act -------------------------------------------------------------------
        CycleContextData result = engine.runCycle(context);

        // --- assert: both subsystems were invoked ----------------------------------
        verify(evolutionAdapter, atLeastOnce()).evolve(any(CycleContextData.class));
        verify(evaluationAdapter, atLeastOnce()).evaluate(any(CycleContextData.class));

        assertThat(result).isNotNull();
        assertThat(result.evolutionOutcome()).isEqualTo(evolutionOutcome);
        assertThat(result.projectDiff()).isEqualTo(diffResult);
        assertThat(result.evaluationOutcome()).isEqualTo(evalOutcome);

        // If your EvolutionExecutionAgent sets this like the old coordinator did:
        assertThat(result.lastEvolutionFingerprint()).isEqualTo("fp-1");

        // If your agent sets EVOLUTION_OCCURRED on changes, keep this assertion.
        // Otherwise: remove it (or assert whatever your new contract is).
        assertThat(result.signals().keySet()).contains(BasicSignals.EVOLUTION_OCCURRED);
    }
}
