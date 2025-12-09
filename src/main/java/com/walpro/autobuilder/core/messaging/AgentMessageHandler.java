package com.walpro.autobuilder.core.messaging;

@FunctionalInterface
public interface AgentMessageHandler {

    void onMessage(AgentMessage message);
}
