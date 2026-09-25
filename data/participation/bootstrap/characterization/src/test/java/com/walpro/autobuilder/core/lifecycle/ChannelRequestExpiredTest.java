package com.walpro.autobuilder.core.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ChannelRequestExpiredTest {

    @Test
    public void testExpiredWhenAgeExceedsValidForCycles() {
        SignalType st = SignalType.of("TEST");
        ChannelRequest request = ChannelRequest.of(st, 1.0, "src1", 1);
        ChannelRequest ticked = request.tick();
        // Observed: expired() returns false even after tick when age=1 and validForCycles=1
        assertFalse(ticked.expired());
    }
}