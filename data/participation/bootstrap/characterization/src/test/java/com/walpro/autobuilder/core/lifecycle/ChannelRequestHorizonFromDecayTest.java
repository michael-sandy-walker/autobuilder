package com.walpro.autobuilder.core.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChannelRequestHorizonFromDecayTest {

    @Test
    void testHorizonFromDecayWithZeroDecayReturnsNull() {
        Integer result = ChannelRequest.horizonFromDecay(1.0, 0.0);
        assertNull(result);
    }

    @Test
    void testHorizonFromDecayWithKnownInputs() {
        Integer result = ChannelRequest.horizonFromDecay(1.0, 0.5);
        assertNotNull(result);
        assertEquals(2, result);
    }

    @Test
    void testHorizonFromDecayWithDifferentInputs() {
        Integer result = ChannelRequest.horizonFromDecay(2.0, 0.5);
        assertNotNull(result);
        assertEquals(4, result);
    }
}