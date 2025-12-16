package com.walpro.autobuilder.core.messaging;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

public record AgentMessage(
        String fromAgent,
        String toAgent,
        AgentMessageType type,
        String topic,
        String correlationId,
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
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be null or blank");
        }
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        if (payload == null) {
            payload = Collections.emptyMap();
        }
    }

    public static AgentMessage simpleText(
            String fromAgent,
            String toAgent,
            AgentMessageType type,
            String topic,
            String correlationId,
            String text
    ) {
        return new AgentMessage(
                fromAgent,
                toAgent,
                type,
                topic,
                correlationId,
                Map.of("text", text)
        );
    }
}
