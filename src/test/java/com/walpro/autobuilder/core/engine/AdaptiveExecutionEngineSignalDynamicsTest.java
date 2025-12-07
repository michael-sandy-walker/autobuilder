package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.coordinator.AgentCoordinator;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveExecutionEngineSignalDynamicsTest {

    @Test
    void runCycle_withNoProgress_attentuatesRoleSignalAndStops() {
        // initial context with a role signal
        CycleContextData ctx = CycleContextData.builder().build();
        ctx = ctx.withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "test"));

        // coordinator that never changes the context
        AgentCoordinator idleCoordinator = new AgentCoordinator() {
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
                // No changes to signals or any other part of the context
                return context;
            }
        };

        SequentialCoordinationStrategy strategy = new SequentialCoordinationStrategy();
        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(idleCoordinator),
                strategy
        );

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
