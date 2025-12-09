package com.walpro.autobuilder.core.messaging;

import java.util.Collections;
import java.util.Map;

public record AgentMessage(
        String fromAgent,
        String toAgent,
        AgentMessageType type,
        Map<String, Object> payload
) {

    public AgentMessage {
        if (fromAgent == null || fromAgent.isBlank()) {
            throw new IllegalArgumentException("fromAgent must not be null or blank");
        }
        if (toAgent == null || toAgent.isBlank()) {
            throw new IllegalArgumentException("toAgent must not be null or blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (payload == null) {
            payload = Collections.emptyMap();
        }
    }

    public static AgentMessage simpleText(
            String fromAgent,
            String toAgent,
            AgentMessageType type,
            String text
    ) {
        return new AgentMessage(
                fromAgent,
                toAgent,
                type,
                Map.of("text", text)
        );
    }
}
