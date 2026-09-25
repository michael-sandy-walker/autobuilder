package com.walpro.autobuilder.core.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChannelRequestTest {

    @Test
    void of_createsRequestWithGivenFields() {
        SignalType kind = new SignalType("SIGNAL-A");
        ChannelRequest req = ChannelRequest.of(kind, 0.5, "src-a", 10);
        assertEquals(kind, req.kind());
        assertEquals(0.5, req.urgency(), 1e-9);
        assertEquals("src-a", req.source());
        assertEquals(0, req.age());
        assertEquals(10, req.validForCycles());
        assertTrue(req.meta().isEmpty());
    }

    @Test
    void raise_mergesTwoRequests() {
        SignalType kind = new SignalType("SIGNAL-A");
        ChannelRequest a = ChannelRequest.of(kind, 0.5, "src-a", 10);
        ChannelRequest b = ChannelRequest.of(kind, 0.8, "src-b", 10);
        ChannelRequest raised = a.raise(b);
        // Observed: raise sums urgency (0.5 + 0.8 = 1.3), keeps source of 'a',
        // validForCycles becomes max(10, 10) = 10, meta gets sources list
        assertEquals(kind, raised.kind());
        assertEquals(1.3, raised.urgency(), 1e-9);
        assertEquals("src-a", raised.source());
        assertEquals(0, raised.age());
        assertEquals(10, raised.validForCycles());
        assertEquals(java.util.List.of("src-a", "src-b"), raised.meta().get("sources"));
    }

    @Test
    void withMeta_addsKeyValueToMeta() {
        SignalType kind = new SignalType("SIGNAL-A");
        ChannelRequest req = ChannelRequest.of(kind, 0.5, "src-a", 10);
        ChannelRequest withMeta = req.withMeta("key1", "value1");
        assertEquals("value1", withMeta.meta().get("key1"));
        assertEquals(0.5, withMeta.urgency(), 1e-9);
        assertEquals("src-a", withMeta.source());
    }

    @Test
    void tick_agesRequestByOneCycle() {
        SignalType kind = new SignalType("TICK-SIGNAL");
        ChannelRequest req = ChannelRequest.of(kind, 0.9, "tick-src", 5);
        ChannelRequest ticked = req.tick();
        // Observed: tick increments age from 0 to 1, keeps validForCycles at 5
        assertEquals(1, ticked.age());
        assertEquals(5, ticked.validForCycles());
        assertEquals(0.9, ticked.urgency(), 1e-9);
        assertEquals("tick-src", ticked.source());
    }
}