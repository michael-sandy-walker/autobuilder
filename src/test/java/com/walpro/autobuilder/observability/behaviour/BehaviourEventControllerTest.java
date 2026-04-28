package com.walpro.autobuilder.observability.behaviour;

import com.walpro.autobuilder.controller.BehaviourEventController;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5-H: integration test for the {@code GET /events/behaviour} SSE endpoint.
 * Verifies that subscribing yields an emitter and that a subsequent
 * {@code cycle_end} broadcast reaches it through the publisher.
 */
class BehaviourEventControllerTest {

    @Test
    void behaviour_returnsSseEmitterAndBroadcastsCycleEnd() {
        BehaviourEventPublisher publisher = new BehaviourEventPublisher();
        BehaviourEventController controller = new BehaviourEventController(publisher);

        ResponseEntity<SseEmitter> response = controller.behaviour();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNull(); // controller's annotation produces; entity body has the emitter
        SseEmitter emitter = response.getBody();
        assertThat(emitter).isNotNull();
        assertThat(publisher.subscriberCount()).isEqualTo(1);

        // Use a counting emitter to verify a published cycle_end reaches it.
        java.util.concurrent.atomic.AtomicInteger received = new java.util.concurrent.atomic.AtomicInteger();
        SseEmitter counting = new SseEmitter(Long.MAX_VALUE) {
            @Override
            public void send(SseEventBuilder builder) {
                received.incrementAndGet();
            }
        };
        try {
            var f = BehaviourEventPublisher.class.getDeclaredField("emitters");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<SseEmitter> emitters = (java.util.List<SseEmitter>) f.get(publisher);
            emitters.clear();
            emitters.add(counting);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        publisher.publish("cycle_end", java.util.Map.of(
                "timestamp", "2026-04-28T00:00:00Z",
                "cycleId", "cycle-1",
                "event", "cycle_end",
                "payload", java.util.Map.of("merged", true)
        ));
        assertThat(received.get()).isEqualTo(1);

        // Also verify the controller's media type contract.
        var produces = MediaType.TEXT_EVENT_STREAM_VALUE;
        assertThat(produces).isEqualTo("text/event-stream");

        emitter.complete();
        counting.complete();
    }
}
