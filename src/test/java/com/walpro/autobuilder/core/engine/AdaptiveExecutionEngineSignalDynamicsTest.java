package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.deliberation.DeliberationDecision;
import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.core.deliberation.DeliberationService;
import com.walpro.autobuilder.core.engine.ExecutionAgent;
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

    private final DeliberationExecutionGate deliberationExecutionGate = new DeliberationExecutionGate();

    @BeforeEach
    void initMocks() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void runCycle_withNoProgress_attenuatesRoleSignalAndStops() {
        // initial context with a role signal
        CycleContextData ctx = CycleContextData.builder().build()
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "test"));

        // execution agent that never changes the context
        ExecutionAgent idleAgent = new ExecutionAgent() {
            @Override
            public String name() {
                return "evolution";
            }

            @Override
            public String role() {
                return "evolution";
            }

            @Override
            public boolean canExecute(CycleContextData context) {
                return true;
            }

            @Override
            public CycleContextData execute(CycleContextData context) {
                // No changes => engine must detect no progress and attenuate signals.
                return context;
            }

            @Override
            public Set<SignalType> getSupportedSignals() {
                return Set.of(BasicSignals.ROLE_EVOLUTION);
            }
        };

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(idleAgent),
                deliberationService,
                deliberationExecutionGate
        );

        when(deliberationService.deliberate(any(CycleContextData.class)))
                .thenAnswer(inv -> new DeliberationDecision(
                        inv.getArgument(0),
                        DeliberationResult.noop("test")
                ));

        CycleContextData before = ctx;
        CycleContextData after = engine.runCycle(ctx);

        double beforeStrength = before.signals().get(BasicSignals.ROLE_EVOLUTION).strength();
        double afterStrength = after.signals().get(BasicSignals.ROLE_EVOLUTION).strength();

        // 1) strength must have decreased (FAIL_ATTENUATION factor inside AdaptiveExecutionEngine)
        assertThat(afterStrength)
                .as("ROLE_EVOLUTION signal should be attenuated when no progress occurs")
                .isLessThan(beforeStrength);

        // 2) no new signals introduced
        assertThat(after.signals().keySet())
                .containsExactlyInAnyOrder(BasicSignals.ROLE_EVOLUTION);
    }
}
