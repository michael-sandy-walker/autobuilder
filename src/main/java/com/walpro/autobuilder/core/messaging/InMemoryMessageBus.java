package com.walpro.autobuilder.core.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Component
public class InMemoryMessageBus implements MessageBus {

    private final ConcurrentMap<String, CopyOnWriteArrayList<AgentMessageHandler>> handlersByAgent =
            new ConcurrentHashMap<>();

    @Override
    public void publish(AgentMessage message) {
        Objects.requireNonNull(message, "message must not be null");

        List<AgentMessageHandler> handlers = handlersByAgent
                .getOrDefault(message.toAgent(), new CopyOnWriteArrayList<>());

        if (handlers.isEmpty()) {
            log.debug("No handlers registered for agent '{}', dropping message: {}", message.toAgent(), message);
            return;
        }

        for (AgentMessageHandler handler : handlers) {
            try {
                handler.onMessage(message);
            } catch (Exception e) {
                log.warn("Handler for agent '{}' threw exception while processing message {}",
                        message.toAgent(), message, e);
            }
        }
    }

    @Override
    public void subscribe(String agentId, AgentMessageHandler handler) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(handler, "handler must not be null");

        handlersByAgent
                .computeIfAbsent(agentId, id -> new CopyOnWriteArrayList<>())
                .add(handler);

        log.debug("Registered message handler for agent '{}'", agentId);
    }

    @Override
    public void unsubscribe(String agentId, AgentMessageHandler handler) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(handler, "handler must not be null");

        List<AgentMessageHandler> handlers = handlersByAgent.get(agentId);
        if (handlers != null) {
            handlers.remove(handler);
            if (handlers.isEmpty()) {
                handlersByAgent.remove(agentId);
            }
            log.debug("Unregistered message handler for agent '{}'", agentId);
        }
    }
}
