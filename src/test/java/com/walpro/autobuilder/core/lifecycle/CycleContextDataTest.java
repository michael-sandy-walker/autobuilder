package com.walpro.autobuilder.core.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CycleContextDataTest {

    @Test
    void withSignal_reinforcesExistingSignal_and_accumulatesSources() {
        CycleContextData ctx = CycleContextData.builder()
                .signals(Map.of(
                        BasicSignals.SYSTEM_PAUSED,
                        new Signal(BasicSignals.SYSTEM_PAUSED, 0.2, 0.10, 2, "engine", Map.of())
                ))
                .build();

        ctx = ctx.withSignal(new Signal(BasicSignals.SYSTEM_PAUSED, 0.3, 0.05, 1, "deliberation", Map.of()));

        Signal s = ctx.signals().get(BasicSignals.SYSTEM_PAUSED);
        assertNotNull(s);

        assertEquals(0.5, s.strength(), 1e-9);
        assertEquals(0.05, s.decayPerCycle(), 1e-9);

        Object sources = s.meta().get("sources");
        assertTrue(sources instanceof List<?>);

        @SuppressWarnings("unchecked")
        List<String> src = (List<String>) sources;

        assertTrue(src.contains("engine"));
        assertTrue(src.contains("deliberation"));
    }

    @Test
    void tickSignals_neverIncreasesSignalStrength() {
        CycleContextData ctx = CycleContextData.builder()
                .signals(Map.of(
                        BasicSignals.SYSTEM_PAUSED,
                        new Signal(BasicSignals.SYSTEM_PAUSED, 0.7, 0.1, 0, "test", Map.of())
                ))
                .build();

        double before = ctx.getSignal(BasicSignals.SYSTEM_PAUSED).strength();

        CycleContextData out = ctx.tickSignals(0.05);

        double after = out.getSignal(BasicSignals.SYSTEM_PAUSED).strength();

        assertThat(after).isLessThanOrEqualTo(before);
    }
}
