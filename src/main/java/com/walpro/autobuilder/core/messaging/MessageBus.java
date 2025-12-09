package com.walpro.autobuilder.core.messaging;

public interface MessageBus {

    /**
     * Publish a message to the target agent.
     */
    void publish(AgentMessage message);

    /**
     * Subscribe a handler for all messages addressed to the given agentId.
     * agentId should usually be something like "Evaluation", "Planner", "Evolution", "Merge", "MetaAgent".
     */
    void subscribe(String agentId, AgentMessageHandler handler);

    /**
     * Unsubscribe a handler for a given agentId.
     */
    void unsubscribe(String agentId, AgentMessageHandler handler);
}
