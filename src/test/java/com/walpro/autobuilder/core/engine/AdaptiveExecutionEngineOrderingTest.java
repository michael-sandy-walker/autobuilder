package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.deliberation.DeliberationDecision;
import com.walpro.autobuilder.core.deliberation.DeliberationMode;
import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.core.deliberation.DeliberationService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import org.junit.jupiter.api.Test;

import com.walpro.autobuilder.core.engine.meta.MetaOverrideLearningStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdaptiveExecutionEngineOrderingTest {

    @Test
    void runCycle_ordersAgentsBySignalFitness_evolutionPlanner() {
        List<String> executionOrder = new ArrayList<>();
        AdaptiveExecutionEngine engine = newEngine(executionOrder);
        engine.explorationJitter = 0.0; // deterministic ordering

        CycleContextData ctx = CycleContextData.builder().build()
                .withCurrentGoalId("1")
                .withCurrentSubGoalId("2")
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "test"))
                .withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 0.1, 0.1, "test"));

        engine.runCycle(ctx);

        assertThat(executionOrder).containsExactly("evolution", "planner");
    }

    @Test
    void runCycle_ordersAgentsBySignalFitness_plannerEvolution() {
        List<String> executionOrder = new ArrayList<>();
        AdaptiveExecutionEngine engine = newEngine(executionOrder);
        engine.explorationJitter = 0.0; // deterministic ordering

        CycleContextData ctx = CycleContextData.builder().build()
                .withCurrentGoalId("1")
                .withCurrentSubGoalId("2")
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 0.1, 0.1, "test"))
                .withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 1.0, 0.1, "test"));

        engine.runCycle(ctx);

        assertThat(executionOrder).containsExactly("planner", "evolution");
    }

    /**
     * Meta steps must execute BEFORE signal-driven ordering, and roles executed in meta
     * must NOT execute again in the signal-driven pass (executedRoles guard).
     *
     * This specifically validates:
     * - meta-step prefix ordering is respected
     * - the signal-driven ordering does not double-execute roles already executed in meta
     */
    @Test
    void runCycle_metaStepsExecuteFirst_andPreventDoubleExecutionInSignalPass() {
        List<String> executionOrder = new ArrayList<>();

        ExecutionAgent evolutionAgent = newExecutionAgent(
                "evolution",
                Set.of(BasicSignals.ROLE_EVOLUTION),
                executionOrder
        );

        ExecutionAgent plannerAgent = newExecutionAgent(
                "planner",
                Set.of(BasicSignals.ROLE_PLANNER),
                executionOrder
        );

        DeliberationService deliberationService = mock(DeliberationService.class);
        ExecutionEligibilityExplainer executionEligibilityExplainer = mock(ExecutionEligibilityExplainer.class);
        DeliberationExecutionGate gate = new DeliberationExecutionGate();

        when(executionEligibilityExplainer.canExecute(any(), any())).thenReturn(true);

        // Meta steps explicitly ask for PLANNER first, then EVOLUTION.
        // Signals will favor EVOLUTION, so this test proves meta overrides signal ordering.
        DeliberationResult drWithMetaSteps = new DeliberationResult(
                com.walpro.autobuilder.core.deliberation.DeliberationMode.NOOP,
                0.5,
                "test meta",
                List.of(),
                List.of(),
                Map.of(
                        "metaControl.steps",
                        List.of(
                                "1|NEXT_STEP|planner|Do planner first|Never",
                                "2|NEXT_STEP|evolution|Then evolution|Never"
                        )
                ),
                List.of()
        );

        when(deliberationService.deliberate(any(CycleContextData.class)))
                .thenAnswer(inv -> new DeliberationDecision(inv.getArgument(0), drWithMetaSteps));

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(evolutionAgent, plannerAgent),
                deliberationService,
                gate,
                executionEligibilityExplainer,
                new MetaOverrideLearningStore()
        );

        engine.explorationJitter = 0.0;

        CycleContextData ctx = CycleContextData.builder().build()
                .withCurrentGoalId("1")
                .withCurrentSubGoalId("2")
                // Stronger evolution signal would normally run evolution first.
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "test"))
                .withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 0.1, 0.1, "test"));

        engine.runCycle(ctx);

        // Expected: meta ordering wins, and no double-execution occurs afterwards.
        assertThat(executionOrder).containsExactly("planner", "evolution");
    }

    /**
     * If metaControl.steps are present but none are executable, engine falls back to signal-driven ordering.
     * In a single-role mode (EVOLVE/EVALUATE/MERGE), if the required ROLE_* signal is not strong enough,
     * engine injects it to avoid deadlock.
     */
    @Test
    void runCycle_whenMetaStepsAllSkipped_inSingleRoleMode_injectsRequiredRoleSignal() {
        List<String> executionOrder = new ArrayList<>();

        ExecutionAgent evolutionAgent = newExecutionAgent(
                "evolution",
                Set.of(BasicSignals.ROLE_EVOLUTION),
                executionOrder
        );
        ExecutionAgent plannerAgent = newExecutionAgent(
                "planner",
                Set.of(BasicSignals.ROLE_PLANNER),
                executionOrder
        );

        DeliberationService deliberationService = mock(DeliberationService.class);
        ExecutionEligibilityExplainer executionEligibilityExplainer = mock(ExecutionEligibilityExplainer.class);
        DeliberationExecutionGate gate = new DeliberationExecutionGate();

        // Important: prevent post-injection execution from mutating the injected signal (reinforce/attenuate).
        when(executionEligibilityExplainer.canExecute(any(), any()))
                .thenAnswer(inv -> {
                    String role = inv.getArgument(0);
                    return !"evolution".equalsIgnoreCase(role);
                });

        // Single-role mode: EVOLVE allows only "evolution" (size == 1) => eligible for deadlock injection.
        // Provide meta steps that will ALL be skipped (planner is blocked by EVOLVE).
        DeliberationResult dr = new DeliberationResult(
                DeliberationMode.EVOLVE,
                0.5,
                "force evolve mode + meta steps that are not executable",
                List.of(),
                List.of(),
                Map.of(
                        "metaControl.steps",
                        List.of(
                                "1|NEXT_STEP|planner|Try planner (will be blocked by EVOLVE)|Never"
                        )
                ),
                List.of()
        );

        when(deliberationService.deliberate(any(CycleContextData.class)))
                .thenAnswer(inv -> new DeliberationDecision(inv.getArgument(0), dr));

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(evolutionAgent, plannerAgent),
                deliberationService,
                gate,
                executionEligibilityExplainer,
                new MetaOverrideLearningStore()
        );
        engine.explorationJitter = 0.0;

        // Start WITHOUT a strong ROLE_EVOLUTION signal (or none at all) so injection is required.
        CycleContextData ctx = CycleContextData.builder().build()
                .withCurrentGoalId("1")
                .withCurrentSubGoalId("2");

        CycleContextData after = engine.runCycle(ctx);

        // Injection should have happened:
        Signal injected = after.signals().get(BasicSignals.ROLE_EVOLUTION);
        assertThat(injected).as("Expected injected ROLE_EVOLUTION signal").isNotNull();

        // DEADLOCK_INJECT_STRENGTH is 0.12 in AdaptiveExecutionEngine
        assertThat(injected.strength()).isEqualTo(0.12);

        // Bonus: ensure we didn't accidentally execute anything after injection (we blocked evolution execution)
        assertThat(executionOrder).isEmpty();
    }

    private static AdaptiveExecutionEngine newEngine(List<String> executionOrder) {
        ExecutionAgent evolutionAgent = newExecutionAgent(
                "evolution",
                Set.of(BasicSignals.ROLE_EVOLUTION),
                executionOrder
        );

        ExecutionAgent plannerAgent = newExecutionAgent(
                "planner",
                Set.of(BasicSignals.ROLE_PLANNER),
                executionOrder
        );

        DeliberationService deliberationService = mock(DeliberationService.class);
        ExecutionEligibilityExplainer executionEligibilityExplainer = mock(ExecutionEligibilityExplainer.class);
        DeliberationExecutionGate gate = new DeliberationExecutionGate();

        when(executionEligibilityExplainer.canExecute(any(), any())).thenReturn(true);

        when(deliberationService.deliberate(any(CycleContextData.class)))
                .thenAnswer(inv -> new DeliberationDecision(
                        inv.getArgument(0),
                        DeliberationResult.noop("test")
                ));

        return new AdaptiveExecutionEngine(
                List.of(evolutionAgent, plannerAgent),
                deliberationService,
                gate,
                executionEligibilityExplainer,
                new MetaOverrideLearningStore()
        );
    }

    private static ExecutionAgent newExecutionAgent(
            String name,
            Set<SignalType> supportedSignals,
            List<String> executionOrder
    ) {
        return new ExecutionAgent() {
            @Override public String name() { return name; }
            @Override public String role() { return name; }

            @Override
            public CycleContextData execute(CycleContextData ctx) {
                executionOrder.add(name);
                return ctx;
            }

            @Override
            public Set<SignalType> getSupportedSignals() {
                return supportedSignals;
            }
        };
    }

    @Test
    void runCycle_whenAgentMerges_cycleEndsAndLaterRolesDoNotExecute() {
        List<String> executionOrder = new ArrayList<>();

        ExecutionAgent evolutionAgent = new ExecutionAgent() {
            @Override public String name() { return "evolution"; }
            @Override public String role() { return "evolution"; }

            @Override
            public CycleContextData execute(CycleContextData ctx) {
                executionOrder.add("evolution");
                return ctx.withMerged(true); // shouldEndCycle => true
            }

            @Override
            public Set<SignalType> getSupportedSignals() {
                return Set.of(BasicSignals.ROLE_EVOLUTION);
            }
        };

        ExecutionAgent plannerAgent = new ExecutionAgent() {
            @Override public String name() { return "planner"; }
            @Override public String role() { return "planner"; }

            @Override
            public CycleContextData execute(CycleContextData ctx) {
                executionOrder.add("planner");
                return ctx;
            }

            @Override
            public Set<SignalType> getSupportedSignals() {
                return Set.of(BasicSignals.ROLE_PLANNER);
            }
        };

        DeliberationService deliberationService = mock(DeliberationService.class);
        ExecutionEligibilityExplainer executionEligibilityExplainer = mock(ExecutionEligibilityExplainer.class);
        DeliberationExecutionGate gate = new DeliberationExecutionGate();

        when(executionEligibilityExplainer.canExecute(any(), any())).thenReturn(true);

        when(deliberationService.deliberate(any(CycleContextData.class)))
                .thenAnswer(inv -> new DeliberationDecision(inv.getArgument(0), DeliberationResult.noop("test")));

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(evolutionAgent, plannerAgent),
                deliberationService,
                gate,
                executionEligibilityExplainer,
                new MetaOverrideLearningStore()
        );
        engine.explorationJitter = 0.0;

        CycleContextData ctx = CycleContextData.builder().build()
                .withCurrentGoalId("1")
                .withCurrentSubGoalId("2")
                // Ensure evolution runs first
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "test"))
                .withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 0.9, 0.1, "test"));

        engine.runCycle(ctx);

        assertThat(executionOrder).containsExactly("evolution");
    }

    @Test
    void runCycle_metaStopIfWithoutExplicitStopKeyword_doesNotStopEarly() {
        List<String> executionOrder = new ArrayList<>();

        ExecutionAgent plannerAgent = newExecutionAgent(
                "planner",
                Set.of(BasicSignals.ROLE_PLANNER),
                executionOrder
        );
        ExecutionAgent evolutionAgent = newExecutionAgent(
                "evolution",
                Set.of(BasicSignals.ROLE_EVOLUTION),
                executionOrder
        );

        DeliberationService deliberationService = mock(DeliberationService.class);
        ExecutionEligibilityExplainer executionEligibilityExplainer = mock(ExecutionEligibilityExplainer.class);
        DeliberationExecutionGate gate = new DeliberationExecutionGate();

        when(executionEligibilityExplainer.canExecute(any(), any())).thenReturn(true);

        // Step 1 runs planner, StopIf is "MutationAcceptanceState=ACCEPTED" (no explicit stop keyword)
        // Step 2 runs evolution: should still run because stopIf is ignored unless explicit stop keyword is present.
        DeliberationResult drWithMetaSteps = new DeliberationResult(
                com.walpro.autobuilder.core.deliberation.DeliberationMode.NOOP,
                0.5,
                "test meta",
                List.of(),
                List.of(),
                Map.of(
                        "metaControl.steps",
                        List.of(
                                "1|NEXT_STEP|planner|Do planner first|MutationAcceptanceState=ACCEPTED",
                                "2|NEXT_STEP|evolution|Then evolution|Never"
                        )
                ),
                List.of()
        );

        when(deliberationService.deliberate(any(CycleContextData.class)))
                .thenAnswer(inv -> new DeliberationDecision(inv.getArgument(0), drWithMetaSteps));

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(plannerAgent, evolutionAgent),
                deliberationService,
                gate,
                executionEligibilityExplainer,
                new MetaOverrideLearningStore()
        );
        engine.explorationJitter = 0.0;

        CycleContextData ctx = CycleContextData.builder().build()
                .withCurrentGoalId("1")
                .withCurrentSubGoalId("2")
                // irrelevant, meta will drive execution order
                .withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 0.1, 0.1, "test"))
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "test"))
                // make the condition "true" to prove it still doesn't stop early:
                .withMutationState(com.walpro.autobuilder.model.MutationAcceptanceState.ACCEPTED);

        engine.runCycle(ctx);

        assertThat(executionOrder).containsExactly("planner", "evolution");
    }

    @Test
    void runCycle_metaStopIfWithExplicitStopCycleIfKeyword_doesStopEarly() {
        List<String> executionOrder = new ArrayList<>();

        ExecutionAgent plannerAgent = newExecutionAgent(
                "planner",
                Set.of(BasicSignals.ROLE_PLANNER),
                executionOrder
        );
        ExecutionAgent evolutionAgent = newExecutionAgent(
                "evolution",
                Set.of(BasicSignals.ROLE_EVOLUTION),
                executionOrder
        );

        DeliberationService deliberationService = mock(DeliberationService.class);
        ExecutionEligibilityExplainer executionEligibilityExplainer = mock(ExecutionEligibilityExplainer.class);
        DeliberationExecutionGate gate = new DeliberationExecutionGate();

        when(executionEligibilityExplainer.canExecute(any(), any())).thenReturn(true);

        // Step 1 runs planner with stop_cycle_if condition that is true (MutationAcceptanceState=ACCEPTED).
        // Step 2 (evolution) should NOT run because stop_cycle_if halts the full cycle when the condition is met.
        DeliberationResult drWithMetaSteps = new DeliberationResult(
                com.walpro.autobuilder.core.deliberation.DeliberationMode.NOOP,
                0.5,
                "test meta",
                List.of(),
                List.of(),
                Map.of(
                        "metaControl.steps",
                        List.of(
                                "1|NEXT_STEP|planner|Do planner first|stop_cycle_if MutationAcceptanceState=ACCEPTED",
                                "2|NEXT_STEP|evolution|Then evolution|Never"
                        )
                ),
                List.of()
        );

        when(deliberationService.deliberate(any(CycleContextData.class)))
                .thenAnswer(inv -> new DeliberationDecision(inv.getArgument(0), drWithMetaSteps));

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(plannerAgent, evolutionAgent),
                deliberationService,
                gate,
                executionEligibilityExplainer,
                new MetaOverrideLearningStore()
        );
        engine.explorationJitter = 0.0;

        CycleContextData ctx = CycleContextData.builder().build()
                .withCurrentGoalId("1")
                .withCurrentSubGoalId("2")
                // irrelevant, meta will drive execution order
                .withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 0.1, 0.1, "test"))
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "test"))
                // make the condition "true" to prove it still doesn't stop early:
                .withMutationState(com.walpro.autobuilder.model.MutationAcceptanceState.ACCEPTED);

        engine.runCycle(ctx);

        assertThat(executionOrder).containsExactly("planner");
    }

    @Test
    void runCycle_metaStopIfWithStopIfKeyword_stopsMetaPhaseEarly() {
        List<String> executionOrder = new ArrayList<>();

        ExecutionAgent plannerAgent = newExecutionAgent(
                "planner",
                Set.of(BasicSignals.ROLE_PLANNER),
                executionOrder
        );
        ExecutionAgent evolutionAgent = newExecutionAgent(
                "evolution",
                Set.of(BasicSignals.ROLE_EVOLUTION),
                executionOrder
        );

        DeliberationService deliberationService = mock(DeliberationService.class);
        ExecutionEligibilityExplainer executionEligibilityExplainer = mock(ExecutionEligibilityExplainer.class);
        DeliberationExecutionGate gate = new DeliberationExecutionGate();

        when(executionEligibilityExplainer.canExecute(any(), any())).thenReturn(true);

        // Step 1 runs planner with stop_if condition that is true (MutationAcceptanceState=ACCEPTED).
        // stop_if halts further meta-step iteration but does NOT set stopCycle=true, so in a normal runCycle()
        // the signal-driven pass would proceed. executeMetaStepsOnly=true isolates the meta phase here.
        DeliberationResult drWithMetaSteps = new DeliberationResult(
                com.walpro.autobuilder.core.deliberation.DeliberationMode.NOOP,
                0.5,
                "test meta",
                List.of(),
                List.of(),
                Map.of(
                        "metaControl.steps",
                        List.of(
                                "1|NEXT_STEP|planner|Do planner first|stop_if MutationAcceptanceState=ACCEPTED",
                                "2|NEXT_STEP|evolution|Then evolution|Never"
                        )
                ),
                List.of()
        );

        when(deliberationService.deliberate(any(CycleContextData.class)))
                .thenAnswer(inv -> new DeliberationDecision(inv.getArgument(0), drWithMetaSteps));

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(plannerAgent, evolutionAgent),
                deliberationService,
                gate,
                executionEligibilityExplainer,
                new MetaOverrideLearningStore()
        );
        engine.explorationJitter = 0.0;

        CycleContextData ctx = CycleContextData.builder().build()
                .withCurrentGoalId("1")
                .withCurrentSubGoalId("2")
                // irrelevant, meta will drive execution order
                .withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 0.1, 0.1, "test"))
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "test"))
                // make the condition "true" to prove it still doesn't stop early:
                .withMutationState(com.walpro.autobuilder.model.MutationAcceptanceState.ACCEPTED);

        // executeMetaStepsOnly=true: proves that stop_if stops further meta-steps within the meta phase.
        // In a full runCycle(ctx) call, stop_if would not prevent the signal-driven pass from running.
        engine.runCycle(ctx, true);

        assertThat(executionOrder).containsExactly("planner");
    }

    /**
     * Regression test for the bug observed on 2026-04-07:
     *
     * {@code ctx.mutationState()} is only written by {@code CycleExecutor.applyEvaluationInterpretation()}
     * AFTER {@code runCycle()} returns — it is always null inside the meta-step loop.
     * The old {@code stopDecision()} code checked {@code ctx.mutationState() != null}, so the
     * {@code MutationAcceptanceState is REJECTED} stop_if condition NEVER fired.
     *
     * The fix: fall back to {@code ctx.evaluationOutcome().mutationState()} when {@code ctx.mutationState()} is null.
     *
     * This test puts the evaluation agent (meta step 1) in a position where it sets
     * {@code evaluationOutcome} with REJECTED but does NOT set {@code mutationState}.
     * It then asserts that the stop_if condition fires and the evolution agent (meta step 2) does NOT run.
     */
    @Test
    void metaStopIf_firesOnEvaluationOutcomeMutationState_whenContextMutationStateIsNull() {
        List<String> executionOrder = new ArrayList<>();

        // Evaluation agent: sets evaluationOutcome with REJECTED, but does NOT set ctx.mutationState()
        // (mirroring the real EvaluationAgent behaviour inside a cycle).
        ExecutionAgent evaluationAgent = new ExecutionAgent() {
            @Override public String name() { return "evaluation"; }
            @Override public String role() { return "evaluation"; }

            @Override
            public CycleContextData execute(CycleContextData ctx) {
                executionOrder.add("evaluation");
                com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome outcome =
                        new com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome(
                                0.5,
                                com.walpro.autobuilder.model.MutationAcceptanceState.REJECTED,
                                com.walpro.autobuilder.model.GoalProgressState.STAGNATING,
                                "rejected by heuristic",
                                null
                        );
                return ctx.withEvaluationOutcome(outcome); // mutationState stays null
            }

            @Override
            public Set<SignalType> getSupportedSignals() {
                return Set.of(BasicSignals.ROLE_EVALUATION);
            }
        };

        ExecutionAgent evolutionAgent = newExecutionAgent("evolution", Set.of(BasicSignals.ROLE_EVOLUTION), executionOrder);

        DeliberationService deliberationService = mock(DeliberationService.class);
        ExecutionEligibilityExplainer explainer = mock(ExecutionEligibilityExplainer.class);
        DeliberationExecutionGate gate = new DeliberationExecutionGate();

        when(explainer.canExecute(any(), any())).thenReturn(true);

        DeliberationResult dr = new DeliberationResult(
                DeliberationMode.NOOP, 0.5, "test",
                List.of(), List.of(),
                Map.of("metaControl.steps", List.of(
                        "1|NEXT_STEP|evaluation|Evaluate the mutation|stop_if MutationAcceptanceState is REJECTED",
                        "2|NEXT_STEP|evolution|Evolve|Never"
                )),
                List.of()
        );

        when(deliberationService.deliberate(any(CycleContextData.class)))
                .thenAnswer(inv -> new DeliberationDecision(inv.getArgument(0), dr));

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(evaluationAgent, evolutionAgent),
                deliberationService, gate, explainer,
                new MetaOverrideLearningStore()
        );
        engine.explorationJitter = 0.0;

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("g1").currentSubGoalId("s1")
                .build()
                // mutationState is intentionally null — mirrors state inside a real cycle
                .withSignal(Signal.of(BasicSignals.ROLE_EVALUATION, 1.0, 0.1, "test"))
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "test"));

        engine.runCycle(ctx, true); // executeMetaStepsOnly to isolate meta phase

        assertThat(executionOrder)
                .as("evaluation must run, then stop_if fires on evaluationOutcome.REJECTED — evolution must not run")
                .containsExactly("evaluation");
    }
}