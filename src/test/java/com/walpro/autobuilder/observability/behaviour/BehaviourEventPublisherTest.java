package com.walpro.autobuilder.observability.behaviour;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5-H: SSE-side coverage for {@link BehaviourEventPublisher}. Mirrors the suite for
 * {@code GoalStateEventPublisher} but for the higher-frequency behaviour stream.
 */
class BehaviourEventPublisherTest {

    @Test
    void register_addsEmitterToRegistry() {
        BehaviourEventPublisher pub = new BehaviourEventPublisher();
        SseEmitter emitter = pub.register();
        assertThat(emitter).isNotNull();
        assertThat(pub.subscriberCount()).isEqualTo(1);
        // SseEmitter.complete() defers onCompletion until Spring MVC drains the emitter,
        // so we don't assert decrement here — failure-pruning is covered by publish_pruneFailingSubscribers.
        emitter.complete();
    }

    @Test
    void publish_broadcastsToEverySubscriber() {
        BehaviourEventPublisher pub = new BehaviourEventPublisher();
        AtomicInteger goodReceives = new AtomicInteger();

        SseEmitter goodA = countingEmitter(goodReceives);
        SseEmitter goodB = countingEmitter(goodReceives);
        registerExternal(pub, goodA);
        registerExternal(pub, goodB);
        // also one registered through the normal path
        SseEmitter normal = pub.register();

        pub.publish("cycle_end", Map.of("event", "cycle_end", "cycleId", "c"));

        // The two counting emitters should have received exactly one event each.
        assertThat(goodReceives.get()).isEqualTo(2);

        normal.complete();
        goodA.complete();
        goodB.complete();
    }

    @Test
    void publish_withNoSubscribersIsNoop() {
        BehaviourEventPublisher pub = new BehaviourEventPublisher();
        pub.publish("cycle_start", Map.of("event", "cycle_start"));
        assertThat(pub.subscriberCount()).isEqualTo(0);
    }

    @Test
    void publish_pruneFailingSubscribers() {
        BehaviourEventPublisher pub = new BehaviourEventPublisher();
        SseEmitter dead = new SseEmitter() {
            @Override
            public void send(SseEventBuilder builder) {
                throw new RuntimeException("simulated failure");
            }
        };
        registerExternal(pub, dead);
        SseEmitter alive = pub.register();

        // Should not throw and should remove the dead subscriber.
        pub.publish("cycle_end", Map.of("event", "cycle_end"));
        assertThat(pub.subscriberCount()).isLessThanOrEqualTo(1);

        alive.complete();
    }

    // ---------------------------------------------------------------------------------

    private SseEmitter countingEmitter(AtomicInteger counter) {
        return new SseEmitter(Long.MAX_VALUE) {
            @Override
            public void send(SseEventBuilder builder) {
                counter.incrementAndGet();
            }
        };
    }

    /**
     * Registers an externally-constructed emitter without invoking {@link BehaviourEventPublisher#register()},
     * which would replace it with one created internally. Reflective bridge keeps tests independent
     * of any package-private hook.
     */
    private static void registerExternal(BehaviourEventPublisher pub, SseEmitter emitter) {
        try {
            var emittersField = BehaviourEventPublisher.class.getDeclaredField("emitters");
            emittersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<SseEmitter> emitters = (java.util.List<SseEmitter>) emittersField.get(pub);
            emitters.add(emitter);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
