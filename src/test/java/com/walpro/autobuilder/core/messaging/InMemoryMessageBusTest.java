package com.walpro.autobuilder.core.messaging;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryMessageBusTest {

    @Test
    void publish_deliversOnlyToHandlersOfTargetAgent() {
        InMemoryMessageBus bus = new InMemoryMessageBus();

        AtomicInteger hitsA = new AtomicInteger();
        AtomicInteger hitsB = new AtomicInteger();

        bus.subscribe("A", msg -> hitsA.incrementAndGet());
        bus.subscribe("B", msg -> hitsB.incrementAndGet());

        bus.publish(new AgentMessage("X", "B", AgentMessageType.INSIGHT, "TOPIC", "cid", Map.of()));

        assertThat(hitsA.get()).isEqualTo(0);
        assertThat(hitsB.get()).isEqualTo(1);
    }
}
