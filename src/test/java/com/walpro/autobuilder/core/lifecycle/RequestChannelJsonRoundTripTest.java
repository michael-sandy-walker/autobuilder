package com.walpro.autobuilder.core.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request ledger must survive a JSON round trip with its keys intact. Found while restoring a
 * carried cycle context (T4-DQ, 2026-09-14): every real {@code meta.json} on disk carried keys like
 * {@code SignalType[name=SIGNALTYPE[NAME=LOW_CONFIDENCE]]} — one wrapper per re-serialization — and a
 * ledger read from such a file answered {@code hasOpen(LOW_CONFIDENCE) == false}.
 */
class RequestChannelJsonRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("open requests are found by kind after a JSON round trip")
    void keysSurviveARoundTrip() throws Exception {
        RequestChannel channel = RequestChannel.empty()
                .issue(ChannelRequest.of(BasicSignals.LOW_CONFIDENCE, 3.0, "evaluation", 7))
                .issue(ChannelRequest.of(BasicSignals.DIVERGENCE_REQUEST, 0.7, "interpretation", 7));

        String json = mapper.writeValueAsString(channel);
        RequestChannel back = mapper.readValue(json, RequestChannel.class);

        assertThat(json).contains("\"LOW_CONFIDENCE\":").doesNotContain("SignalType[name=");
        assertThat(back.hasOpen(BasicSignals.LOW_CONFIDENCE)).isTrue();
        assertThat(back.hasOpen(BasicSignals.DIVERGENCE_REQUEST)).isTrue();
        assertThat(back.urgency(BasicSignals.LOW_CONFIDENCE)).isEqualTo(3.0);
        // And again: a second trip must not wrap anything either.
        RequestChannel twice = mapper.readValue(mapper.writeValueAsString(back), RequestChannel.class);
        assertThat(twice.hasOpen(BasicSignals.LOW_CONFIDENCE)).isTrue();
    }

    @Test
    @DisplayName("the legacy wrapped key forms read back to the bare kind, however deep")
    void legacyWrappedKeysAreUnwrapped() throws Exception {
        String legacy = "{\"open\":{\"SignalType[name=SIGNALTYPE[NAME=SIGNALTYPE[NAME=LOW_CONFIDENCE]]]\":"
                + "{\"kind\":{\"name\":\"LOW_CONFIDENCE\"},\"urgency\":3.0,\"source\":\"evaluation\","
                + "\"subGoalId\":null,\"age\":1,\"validForCycles\":7,\"meta\":{}}}}";

        RequestChannel back = mapper.readValue(legacy, RequestChannel.class);

        assertThat(back.hasOpen(BasicSignals.LOW_CONFIDENCE)).isTrue();
        assertThat(SignalTypeKey.unwrap("SignalType[name=X]")).isEqualTo("X");
        assertThat(SignalTypeKey.unwrap("X")).isEqualTo("X");
    }
}
