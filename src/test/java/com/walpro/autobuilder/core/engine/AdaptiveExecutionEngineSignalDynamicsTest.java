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

/**
 * T4-BZ-4: participation-window dynamics, numerically 1:1 with the stored-signal
 * reinforce/attenuate they replaced — progress re-grants the executed role's window
 * (+1.0 boost, capped), no progress shrinks it (x0.9). A stored ROLE_* twin from a recorded
 * state is absorbed into the window on the first write (migration bridge). The old
 * weak-signal exploration nudge was deleted with the fitness arithmetic (jitter's sibling).
 */
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
    void runCycle_withNoProgress_shrinksRoleWindow() {
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

        when(deliberationService.deliberate(any(CycleContextData.class)))
                .thenAnswer(inv -> new DeliberationDecision(inv.getArgument(0), DeliberationResult.noop("test")));
        when(executionEligibilityExplainer.canExecute(any(), any())).thenReturn(true);

        CycleContextData after = engine.runCycle(ctx);

        // The stored twin was absorbed into a window and shrunk by the old attenuation factor.
        assertThat(after.hasSignal(BasicSignals.ROLE_EVOLUTION)).isFalse();
        assertThat(after.windowBoost("evolution")).isEqualTo(1.0 * 0.9);
    }

    @Test
    void runCycle_withProgress_regrantsRoleWindow_byPlusOne() {
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

        when(deliberationService.deliberate(any(CycleContextData.class)))
                .thenAnswer(inv -> new DeliberationDecision(inv.getArgument(0), DeliberationResult.noop("test")));
        when(executionEligibilityExplainer.canExecute(any(), any())).thenReturn(true);

        CycleContextData after = engine.runCycle(ctx);

        // Re-grant adds the old SUCCESS_REINFORCE (=1.0) to the absorbed twin's boost.
        assertThat(after.hasSignal(BasicSignals.ROLE_EVOLUTION)).isFalse();
        assertThat(after.windowBoost("evolution")).isEqualTo(0.4 + 1.0);
        assertThat(after.signals()).containsKey(progressMarker);
    }
}
