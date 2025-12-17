package com.walpro.autobuilder.core.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySignalBusTest {

    @Test
    void publish_deliversOnlyToMatchingSignalTypeSubscribers() {
        InMemorySignalBus bus = new InMemorySignalBus();

        SignalType A = SignalType.of("A");
        SignalType B = SignalType.of("B");

        AtomicInteger hitsA = new AtomicInteger();
        AtomicInteger hitsB = new AtomicInteger();

        bus.subscribe(A, s -> hitsA.incrementAndGet());
        bus.subscribe(B, s -> hitsB.incrementAndGet());

        bus.publish(Signal.of(A, 1.0, 0.1, "test"));

        assertThat(hitsA.get()).isEqualTo(1);
        assertThat(hitsB.get()).isEqualTo(0);
    }
}
