package com.walpro.autobuilder.core.messaging;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMessageTest {

    @Test
    void requestResponse_carriesTopicAndCorrelationId() {
        AgentMessage req = new AgentMessage(
                "Evaluation",
                "Evolution",
                AgentMessageType.REQUEST,
                "EVOLUTION_DECISION",
                "cid-123",
                Map.of("changed", true)
        );

        AgentMessage res = new AgentMessage(
                "Evolution",
                "Evaluation",
                AgentMessageType.RESPONSE,
                req.topic(),
                req.correlationId(),
                Map.of("diverge", false)
        );

        assertThat(res.topic()).isEqualTo("EVOLUTION_DECISION");
        assertThat(res.correlationId()).isEqualTo("cid-123");
    }

    @Test
    void payload_nullBecomesEmptyMap() {
        AgentMessage m = new AgentMessage(
                "A",
                "B",
                AgentMessageType.INSIGHT,
                "SOMETHING",
                "cid-1",
                null
        );
        assertThat(m.payload()).isNotNull();
        assertThat(m.payload()).isEmpty();
    }
}
