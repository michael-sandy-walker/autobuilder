package com.walpro.autobuilder.core.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChannelRequestRaiseTest {

    @Test
    void testRaiseReturnsNewInstanceWithHigherUrgency() {
        // Observed: raise preserves receiver's source (src1) and uses max urgency (3.0)
        ChannelRequest receiver = ChannelRequest.of(SignalType.of("TEST"), 1.0, "src1");
        ChannelRequest other = ChannelRequest.of(SignalType.of("TEST"), 3.0, "src2");
        ChannelRequest result = receiver.raise(other);

        assertNotSame(receiver, result);
        assertNotSame(other, result);
        assertEquals("TEST", result.kind().name());
        assertEquals(3.0, result.urgency(), 1e-9);
        assertEquals("src1", result.source());
        assertEquals(0, result.age());
        assertNull(result.validForCycles());
    }

    @Test
    void testRaiseWithLowerUrgencyKeepsReceiverUrgency() {
        // Observed: raise preserves receiver's source (src1) and uses max urgency (3.0)
        ChannelRequest receiver = ChannelRequest.of(SignalType.of("TEST"), 3.0, "src1");
        ChannelRequest other = ChannelRequest.of(SignalType.of("TEST"), 1.0, "src2");
        ChannelRequest result = receiver.raise(other);

        assertNotSame(receiver, result);
        assertNotSame(other, result);
        assertEquals("TEST", result.kind().name());
        assertEquals(3.0, result.urgency(), 1e-9);
        assertEquals("src1", result.source());
        assertEquals(0, result.age());
        assertNull(result.validForCycles());
    }
}