package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.deliberation.DeliberationDecision;
import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.core.deliberation.DeliberationService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class AdaptiveExecutionEngineSignalDynamicsTest {

    @Mock
    DeliberationService deliberationService;

    @Mock
    ExecutionEligibilityExplainer executionEligibilityExplainer;

    private final DeliberationExecutionGate deliberationExecutionGate = new DeliberationExecutionGate();

    @BeforeEach
    void initMocks() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void runCycle_withNoProgress_attenuatesRoleSignal() {
        CycleContextData ctx = CycleContextData.builder().build()
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "test"));

        ExecutionAgent idleAgent = new ExecutionAgent() {
            @Override public String name() { return "evolution"; }
            @Override public String role() { return "evolution"; }

            @Override
            public CycleContextData execute(CycleContextData context) {
                return context; // no progress
            }

            @Override
            public Set<SignalType> getSupportedSignals() {
                return Set.of(BasicSignals.ROLE_EVOLUTION);
            }
        };

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(idleAgent),
                deliberationService,
                deliberationExecutionGate,
                executionEligibilityExplainer
        );
        engine.explorationJitter = 0.0; // avoid random order/noise affecting expectations

        when(deliberationService.deliberate(any(CycleContextData.class)))
                .thenAnswer(inv -> new DeliberationDecision(inv.getArgument(0), DeliberationResult.noop("test")));
        when(executionEligibilityExplainer.canExecute(any(), any())).thenReturn(true);

        CycleContextData after = engine.runCycle(ctx);

        double beforeStrength = ctx.signals().get(BasicSignals.ROLE_EVOLUTION).strength();
        double afterStrength = after.signals().get(BasicSignals.ROLE_EVOLUTION).strength();

        assertThat(afterStrength).isLessThan(beforeStrength);
        assertThat(after.signals().keySet()).containsExactlyInAnyOrder(BasicSignals.ROLE_EVOLUTION);
    }

    @Test
    void runCycle_withProgress_reinforcesRoleSignal_byPlusOne() {
        CycleContextData ctx = CycleContextData.builder().build()
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 0.4, 0.1, "test"));

        SignalType progressMarker = SignalType.of("TEST_PROGRESS_MARKER");

        ExecutionAgent progressAgent = new ExecutionAgent() {
            @Override public String name() { return "evolution"; }
            @Override public String role() { return "evolution"; }

            @Override
            public CycleContextData execute(CycleContextData context) {
                // Make "progress" visible to hasProgressed(): mutate signal set
                return context.withSignal(Signal.of(progressMarker, 1.0, 0.0, "test"));
            }

            @Override
            public Set<SignalType> getSupportedSignals() {
                return Set.of(BasicSignals.ROLE_EVOLUTION);
            }
        };

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(progressAgent),
                deliberationService,
                deliberationExecutionGate,
                executionEligibilityExplainer
        );
        engine.explorationJitter = 0.0;

        when(deliberationService.deliberate(any(CycleContextData.class)))
                .thenAnswer(inv -> new DeliberationDecision(inv.getArgument(0), DeliberationResult.noop("test")));
        when(executionEligibilityExplainer.canExecute(any(), any())).thenReturn(true);

        CycleContextData after = engine.runCycle(ctx);

        double beforeStrength = ctx.signals().get(BasicSignals.ROLE_EVOLUTION).strength();
        double afterStrength = after.signals().get(BasicSignals.ROLE_EVOLUTION).strength();

        // Signal.reinforce adds SUCCESS_REINFORCE (=1.0) to current strength
        assertThat(afterStrength).isEqualTo(beforeStrength + 1.0);
        assertThat(after.signals()).containsKey(progressMarker);
    }

    @Test
    void runCycle_whenAllSignalsWeak_exploresByNudgingWeakestUpToCap() {
        CycleContextData ctx = CycleContextData.builder().build()
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 0.01, 0.1, "test"))
                .withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 0.02, 0.1, "test"));

        ExecutionAgent idleAgent = new ExecutionAgent() {
            @Override public String name() { return "evolution"; }
            @Override public String role() { return "evolution"; }

            @Override
            public CycleContextData execute(CycleContextData context) {
                return context; // no progress => attenuation happens, then exploreIfNeeded runs
            }

            @Override
            public Set<SignalType> getSupportedSignals() {
                return Set.of(BasicSignals.ROLE_EVOLUTION);
            }
        };

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(idleAgent),
                deliberationService,
                deliberationExecutionGate,
                executionEligibilityExplainer
        );
        engine.explorationJitter = 0.0;

        when(deliberationService.deliberate(any(CycleContextData.class)))
                .thenAnswer(inv -> new DeliberationDecision(inv.getArgument(0), DeliberationResult.noop("test")));
        when(executionEligibilityExplainer.canExecute(any(), any())).thenReturn(true);

        CycleContextData after = engine.runCycle(ctx);

        double evoAfter = after.signals().get(BasicSignals.ROLE_EVOLUTION).strength();
        double planAfter = after.signals().get(BasicSignals.ROLE_PLANNER).strength();

        // exploreIfNeeded: weakest signal gets +0.04, capped at 0.12
        double evo = after.signals().get(BasicSignals.ROLE_EVOLUTION).strength();
        double plan = after.signals().get(BasicSignals.ROLE_PLANNER).strength();

        // Weakest initially was ROLE_EVOLUTION (0.01) -> should be >= ~0.04-ish after nudge (after attenuation it becomes 0.009, then +0.04 -> 0.049)
        assertThat(evo).isGreaterThan(0.03);
        // Planner should still exist and remain low-ish
        assertThat(plan).isGreaterThan(0.0);
        // Safety: nudge is capped at 0.12
        assertThat(evoAfter).isLessThanOrEqualTo(0.12);
        assertThat(planAfter).isLessThanOrEqualTo(0.12);
    }
}