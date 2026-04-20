package com.walpro.autobuilder.core.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SignalTest {

    // ── T1-AG: Signal strength cap ─────────────────────────────────────────────

    @Test
    void of_strengthCappedAtMaxStrength() {
        Signal s = Signal.of(BasicSignals.ROLE_EVOLUTION, 999.0, 0.10, "test");
        assertThat(s.strength()).isEqualTo(Signal.MAX_STRENGTH);
    }

    @Test
    void reinforce_strengthCappedAtMaxStrength() {
        Signal a = Signal.of(BasicSignals.ROLE_EVOLUTION, 9.0, 0.10, "a");
        Signal b = Signal.of(BasicSignals.ROLE_EVOLUTION, 9.0, 0.10, "b");
        Signal result = a.reinforce(b);
        assertThat(result.strength()).isEqualTo(Signal.MAX_STRENGTH);
    }

    @Test
    void reinforce_normalSum_notCapped() {
        // T2-CG: MAX_STRENGTH lowered to 3.0; use values whose sum stays below the cap
        Signal a = Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.10, "a");
        Signal b = Signal.of(BasicSignals.ROLE_EVOLUTION, 1.5, 0.10, "b");
        Signal result = a.reinforce(b);
        assertThat(result.strength()).isEqualTo(2.5, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void reinforce_mergesStrength_decay_age_and_sources() {
        Signal a = new Signal(BasicSignals.SYSTEM_PAUSED, 0.2, 0.10, 3, "engine",
                Map.of("sources", List.of("engine"), "foo", "bar"));

        Signal b = new Signal(BasicSignals.SYSTEM_PAUSED, 0.3, 0.05, 1, "deliberation",
                Map.of("sources", List.of("deliberation"), "baz", 42));

        Signal r = a.reinforce(b);

        assertEquals(BasicSignals.SYSTEM_PAUSED, r.type());
        assertEquals(0.5, r.strength(), 1e-9);
        assertEquals(0.05, r.decayPerCycle(), 1e-9);
        assertEquals(1, r.age()); // min(3,1)
        assertEquals("engine", r.source()); // primary source kept

        Object sources = r.meta().get("sources");
        assertTrue(sources instanceof List<?>);

        @SuppressWarnings("unchecked")
        List<String> src = (List<String>) sources;

        assertTrue(src.contains("engine"));
        assertTrue(src.contains("deliberation"));
        assertEquals("bar", r.meta().get("foo"));
        assertEquals(42, r.meta().get("baz"));
    }
}
