package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.agent.evaluation.EvaluationAgent;
import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter;
import com.walpro.autobuilder.agent.evaluation.EvaluationLLMAdapter;
import com.walpro.autobuilder.agent.evaluation.ParsedEvaluation;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringService;
import com.walpro.autobuilder.agent.evolution.EvolutionAgent;
import com.walpro.autobuilder.agent.evolution.EvolutionLLMAdapter;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.ProjectDiffService;
import com.walpro.autobuilder.analysis.heuristic.EvaluationHeuristic;
import com.walpro.autobuilder.analysis.heuristic.HeuristicNegotiator;
import com.walpro.autobuilder.analysis.heuristic.NegotiationResult;
import com.walpro.autobuilder.analysis.problem.ProblemDetector;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.deliberation.DeliberationDecision;
import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.core.deliberation.DeliberationService;
import com.walpro.autobuilder.core.service.FingerprintService;
import com.walpro.autobuilder.core.service.MutationApplier;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
import com.walpro.autobuilder.graph.evaluation.EvaluationActionRegistrar;
import com.walpro.autobuilder.graph.evaluation.EvaluationGraphFacade;
import com.walpro.autobuilder.graph.evolution.EvolutionGraphService;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.CompileProbeService;
import com.walpro.autobuilder.core.engine.meta.MetaOverrideLearningStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
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

    @Mock private EvaluationLLMAdapter evaluationAdapter;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ScoringService scoringService;

    @Mock private EvaluationInterpreter evaluationInterpreter;

    @Mock private DeliberationService deliberationService;
    private final DeliberationExecutionGate deliberationExecutionGate = new DeliberationExecutionGate();

    @Mock
    private ProblemDetector problemDetector;

    @Mock
    private HeuristicNegotiator heuristicNegotiator;
    @Mock
    private List<EvaluationHeuristic> heuristics;
    @Mock
    private EvaluationGraphFacade graphFacade;
    @Mock
    private GoalPlannerService goalPlannerService;
    @Mock
    private EvaluationActionRegistrar evaluationActionRegistrar;

    @Mock
    private ExecutionEligibilityExplainer executionEligibilityExplainer;

    @Mock
    private MutationSnapshotService mutationSnapshotService;

    @Mock
    private CompileProbeService compileProbeService;
    @Mock
    private com.walpro.autobuilder.core.merge.MergeRollbackService mergeRollbackService;
    @Mock
    private com.walpro.autobuilder.core.service.FileService fileService;

    @Test
    void runCycle_executesEvolutionAndevaluationAdapters_andUpdatesContext() throws Exception {
        // --- real execution agents (the whole point of the refactor) -----------------
        EvolutionAgent evoExecAgent = new EvolutionAgent(
                evolutionAdapter,
                mutationApplier,
                fingerprintService,
                projectDiffService,
                evolutionGraphService,
                graphFacade,
                goalPlannerService,
                new com.walpro.autobuilder.protocol.PromptTokenBudget()
        );

        // If you have an EvaluationExecutionAgent in your refactor, use it here.
        // Replace ctor args with the ones you actually have.
        lenient().when(compileProbeService.probe(any())).thenReturn(
                new CompileProbeService.ProbeResult(BuildOutcome.skipped("disabled in test"), null));
        var evalExecAgent = new EvaluationAgent(
                evaluationAdapter,
                scoringService,
                evaluationInterpreter,
                problemDetector,
                heuristicNegotiator,
                heuristics,
                graphFacade,
                evaluationActionRegistrar,
                goalPlannerService,
                mutationSnapshotService,
                compileProbeService,
                mergeRollbackService,
                fileService
        );

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(evoExecAgent, evalExecAgent),
                deliberationService,
                deliberationExecutionGate,
                executionEligibilityExplainer,
                new MetaOverrideLearningStore()
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
        when(evaluationAdapter.evaluate(any(CycleContextData.class))).thenReturn(new ParsedEvaluation(evalOutcome, java.util.List.of()));

        when(heuristicNegotiator.negotiate(any(CycleContextData.class), any(EvaluationOutcome.class), any()))
                .thenAnswer(inv -> new NegotiationResult(inv.getArgument(1, EvaluationOutcome.class), List.of(), List.of()));

        EvaluationInterpreter.InterpretationResult interpretation =
                mock(EvaluationInterpreter.InterpretationResult.class);
        when(interpretation.success()).thenReturn(true);
        when(interpretation.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(evaluationInterpreter.interpret(any(), nullable(Double.class), anyBoolean()))
                .thenReturn(interpretation);

        // --- deliberation ----------------------------------------------------------
        when(deliberationService.deliberate(any(CycleContextData.class)))
                .thenAnswer(inv -> new DeliberationDecision(
                        inv.getArgument(0),
                        DeliberationResult.noop("test")
                ));

        when(executionEligibilityExplainer.canExecute(any(), any())).thenReturn(true);

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

    @Test
    void runCycle_whenEvolutionBlockedByEligibility_doesNotExecuteEvolutionEvenIfRoleSignalPresent() throws Exception {
        EvolutionAgent evoExecAgent = spy(new EvolutionAgent(
                evolutionAdapter,
                mutationApplier,
                fingerprintService,
                projectDiffService,
                evolutionGraphService,
                graphFacade,
                goalPlannerService,
                new com.walpro.autobuilder.protocol.PromptTokenBudget()
        ));

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(evoExecAgent),
                deliberationService,
                deliberationExecutionGate,
                executionEligibilityExplainer,
                new MetaOverrideLearningStore()
        );

        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-1")
                .currentGoalId("goal-root")
                .currentSubGoalId("sub-1")
                .build()
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.0, "test"));

        when(deliberationService.deliberate(any(CycleContextData.class)))
                .thenAnswer(inv -> new DeliberationDecision(inv.getArgument(0), DeliberationResult.noop("test")));

        when(executionEligibilityExplainer.canExecute(eq("evolution"), any())).thenReturn(false);

        engine.runCycle(context);

        verify(evoExecAgent, never()).execute(any());
    }

    // T1-DI: after meta-step evolution+evaluation produces ACCEPTED, the post-meta context
    // (with mutationState=ACCEPTED and ROLE_MERGE signal) must be the entry context for the
    // fitness-ranked phase so MergeAgent can execute in the same cycle.

    @Test
    void runCycle_metaStepEvolutionAndEvaluationAccepted_mergeAgentExecutesInFitnessRankedPhase() throws Exception {
        EvolutionAgent evoAgent = new EvolutionAgent(
                evolutionAdapter,
                mutationApplier,
                fingerprintService,
                projectDiffService,
                evolutionGraphService,
                graphFacade,
                goalPlannerService,
                new com.walpro.autobuilder.protocol.PromptTokenBudget()
        );

        lenient().when(compileProbeService.probe(any())).thenReturn(
                new CompileProbeService.ProbeResult(BuildOutcome.skipped("disabled in test"), null));
        EvaluationAgent evalAgent = new EvaluationAgent(
                evaluationAdapter,
                scoringService,
                evaluationInterpreter,
                problemDetector,
                heuristicNegotiator,
                heuristics,
                graphFacade,
                evaluationActionRegistrar,
                goalPlannerService,
                mutationSnapshotService,
                compileProbeService,
                mergeRollbackService,
                fileService
        );

        ExecutionAgent mergeAgent = mock(ExecutionAgent.class);
        when(mergeAgent.role()).thenReturn("merge");
        when(mergeAgent.execute(any())).thenAnswer(inv ->
                inv.<CycleContextData>getArgument(0).withMerged(true));

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(evoAgent, evalAgent, mergeAgent),
                deliberationService,
                deliberationExecutionGate,
                executionEligibilityExplainer,
                new MetaOverrideLearningStore()
        );

        ProjectDiffResult diff = new ProjectDiffResult(null, Map.of("src/App.java", "changed"), Set.of());

        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-meta")
                .outputPath("build/out")
                .previousOutput(Map.of())
                .projectDiff(diff)
                .currentGoalId("goal-root")
                .currentSubGoalId("sub-impl")
                .build()
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 0.8, 0.05, "test"))
                .withSignal(Signal.of(BasicSignals.ROLE_EVALUATION, 0.8, 0.05, "test"))
                .withSignal(Signal.of(BasicSignals.ROLE_MERGE, 0.8, 0.05, "test"));

        EvolutionOutcome evolutionOutcome = new EvolutionOutcome(
                List.of(new com.walpro.autobuilder.agent.evolution.model.FileMutation("src/App.java", "class App {}")),
                List.of(),
                "stub-evolution",
                "#raw"
        );
        when(evolutionAdapter.evolve(any())).thenAnswer(inv ->
                inv.<CycleContextData>getArgument(0).withEvolutionOutcome(evolutionOutcome));
        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-meta");
        when(mutationApplier.resolveOutputPath()).thenReturn(Path.of("build/out"));
        doNothing().when(mutationApplier).applyRegularMutations(any());
        when(projectDiffService.computeDiff(any(), any())).thenReturn(diff);

        EvaluationOutcome evalOutcome = new EvaluationOutcome(
                0.85, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "ok", null);
        when(evaluationAdapter.evaluate(any())).thenReturn(new ParsedEvaluation(evalOutcome, List.of()));
        when(heuristicNegotiator.negotiate(any(), any(), any()))
                .thenAnswer(inv -> new NegotiationResult(inv.getArgument(1, EvaluationOutcome.class), List.of(), List.of()));
        EvaluationInterpreter.InterpretationResult interp = mock(EvaluationInterpreter.InterpretationResult.class);
        when(interp.success()).thenReturn(true);
        when(interp.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(interp.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(evaluationInterpreter.interpret(any(), any(), anyBoolean())).thenReturn(interp);

        // Deliberation returns meta-steps for evolution + evaluation; UNDIRECTED allows all roles.
        DeliberationResult metaDr = new DeliberationResult(
                com.walpro.autobuilder.core.deliberation.DeliberationMode.UNDIRECTED,
                0.8, "meta-test", List.of(), List.of(),
                Map.of(),
                List.of(),
                List.of(
                        new com.walpro.autobuilder.protocol.meta.MetaActionStep(1,
                                com.walpro.autobuilder.protocol.meta.MetaActionStep.Need.NEXT_STEP,
                                "evolution", "implement the feature", null, null),
                        new com.walpro.autobuilder.protocol.meta.MetaActionStep(2,
                                com.walpro.autobuilder.protocol.meta.MetaActionStep.Need.NEXT_STEP,
                                "evaluation", "score the mutation", null, null)
                )
        );
        when(deliberationService.deliberate(any()))
                .thenAnswer(inv -> new DeliberationDecision(inv.getArgument(0), metaDr));

        // evolution + evaluation always executable; merge eligible after ACCEPTED context propagates
        when(executionEligibilityExplainer.canExecute(eq("evolution"), any())).thenReturn(true);
        when(executionEligibilityExplainer.canExecute(eq("evaluation"), any())).thenReturn(true);
        when(executionEligibilityExplainer.canExecute(eq("merge"), argThat(
                ctx -> ctx.mutationState() == MutationAcceptanceState.ACCEPTED)))
                .thenReturn(true);

        CycleContextData result = engine.runCycle(context);

        verify(mergeAgent).execute(any());
        assertThat(result.merged()).isTrue();
    }

    // T1-DK: an agent blocked by canExecute=false in the fitness-ranked pass gets a second chance
    // once earlier agents have updated the context. Here evaluation is always ranked first (high
    // ROLE_EVALUATION signal), blocked at ranking time, then unblocked when evolution runs.
    @Test
    void runCycle_agentBlockedByCanExecute_runsInSecondPassAfterContextIsUpdated() throws Exception {
        ExecutionAgent evoAgent = mock(ExecutionAgent.class);
        when(evoAgent.role()).thenReturn("evolution");
        when(evoAgent.execute(any())).thenAnswer(inv ->
                inv.<CycleContextData>getArgument(0).withEvolutionRanThisCycle(true));

        ExecutionAgent evalAgent = mock(ExecutionAgent.class);
        when(evalAgent.role()).thenReturn("evaluation");
        when(evalAgent.execute(any())).thenAnswer(inv -> inv.getArgument(0));

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(evoAgent, evalAgent),
                deliberationService,
                deliberationExecutionGate,
                executionEligibilityExplainer,
                new MetaOverrideLearningStore()
        );

        // ROLE_EVALUATION >> ROLE_EVOLUTION so evaluation always ranks first and gets blocked first.
        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-dk")
                .currentGoalId("goal-root")
                .currentSubGoalId("sub-impl")
                .build()
                .withSignal(Signal.of(BasicSignals.ROLE_EVALUATION, 5.0, 0.05, "test"))
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 0.1, 0.05, "test"));

        when(deliberationService.deliberate(any()))
                .thenAnswer(inv -> new DeliberationDecision(inv.getArgument(0), DeliberationResult.noop("test")));

        when(executionEligibilityExplainer.canExecute(eq("evolution"), any())).thenReturn(true);
        when(executionEligibilityExplainer.canExecute(eq("evaluation"), any()))
                .thenAnswer(inv -> inv.<CycleContextData>getArgument(1).evolutionRanThisCycle());

        engine.runCycle(context);

        verify(evoAgent).execute(any());
        verify(evalAgent).execute(any());
    }
}
