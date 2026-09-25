package com.walpro.autobuilder.core.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The operator's specification of two new capabilities. These tests fail until the code provides them. */
class TotalsSpecTest {

    @Test
    void totalBoostIsTheSumOfTheActiveWindowsBoosts() {
        ParticipationWindows ws = ParticipationWindows.empty()
                .grant(ParticipationWindow.of("a", 1.0, 0.1, "spec"))
                .grant(ParticipationWindow.of("b", 0.25, 0.1, "spec"));
        assertEquals(1.25, ws.totalBoost(), 1e-9);
    }

    @Test
    void totalBoostOfNoWindowsIsZero_andARevokedWindowNoLongerCounts() {
        assertEquals(0.0, ParticipationWindows.empty().totalBoost(), 0.0);
        ParticipationWindows ws = ParticipationWindows.empty()
                .grant(ParticipationWindow.of("a", 1.0, 0.1, "spec"))
                .grant(ParticipationWindow.of("b", 2.0, 0.1, "spec"))
                .revoke("b");
        assertEquals(1.0, ws.totalBoost(), 1e-9);
    }

    @Test
    void withoutMetaReturnsACopyWithoutThatKey() {
        ChannelRequest r = ChannelRequest.of(SignalType.of("x"), 1.0, "s").withMeta("k", 1).withMeta("j", 2);
        ChannelRequest out = r.withoutMeta("k");
        assertEquals(Map.of("j", 2), out.meta());
        assertEquals(Map.of("k", 1, "j", 2), r.meta());
        assertEquals(r.kind(), out.kind());
        assertEquals(r.urgency(), out.urgency(), 0.0);
    }

    @Test
    void withoutMetaOfAnAbsentKeyReturnsTheSameRequest() {
        ChannelRequest r = ChannelRequest.of(SignalType.of("x"), 1.0, "s").withMeta("k", 1);
        assertSame(r, r.withoutMeta("absent"));
    }

    @Test
    void withoutMetaKeepsTheOtherFieldsUnchanged() {
        ChannelRequest r = ChannelRequest.of(SignalType.of("x"), 2.0, "s", 4).withMeta("k", 1).tick();
        ChannelRequest out = r.withoutMeta("k");
        assertEquals(List.of(r.source(), r.age(), r.validForCycles()), List.of(out.source(), out.age(), out.validForCycles()));
    }
}
