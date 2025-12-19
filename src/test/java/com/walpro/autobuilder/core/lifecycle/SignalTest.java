package com.walpro.autobuilder.core.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SignalTest {

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
