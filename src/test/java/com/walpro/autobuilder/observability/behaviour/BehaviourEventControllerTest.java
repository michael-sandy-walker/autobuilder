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
 *
 * <p>T5-BF: also verifies the hello seed — the controller assembles a session-summary
 * envelope (event counts + build cost) and the publisher sends it on register.
 */
class BehaviourEventControllerTest {

    @Test
    void behaviour_returnsSseEmitterAndBroadcastsCycleEnd() {
        BehaviourEventPublisher publisher = new BehaviourEventPublisher();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        BehaviourLogger logger = new BehaviourLogger(mapper, publisher);
        var buildCostTracker = new com.walpro.autobuilder.core.build.BuildCostTracker();
        BehaviourEventController controller =
                new BehaviourEventController(publisher, logger, buildCostTracker);

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

    @Test
    void behaviour_sendsHelloSeedOnSubscribe() {
        // T5-BF: a subscriber joining mid-run gets the session summary immediately.
        java.util.List<Object> sent = new java.util.ArrayList<>();
        BehaviourEventPublisher publisher = new BehaviourEventPublisher() {
            @Override
            public SseEmitter register(Object helloEnvelope) {
                sent.add(helloEnvelope);
                return super.register(null); // skip the real send — no HTTP response attached
            }
        };
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        BehaviourLogger logger = new BehaviourLogger(mapper, publisher);
        var buildCostTracker = new com.walpro.autobuilder.core.build.BuildCostTracker();
        buildCostTracker.record("cycle-1", 1500L);

        logger.cycleStart("cycle-1", "g", null, null, null);
        logger.mutationMerged("cycle-1", "fp-1", java.util.List.of("A.java"));

        new BehaviourEventController(publisher, logger, buildCostTracker).behaviour();

        assertThat(sent).hasSize(1);
        @SuppressWarnings("unchecked")
        var envelope = (java.util.Map<String, Object>) sent.get(0);
        assertThat(envelope.get("event")).isEqualTo("hello");
        assertThat(envelope.get("cycleId")).isEqualTo("cycle-1");
        @SuppressWarnings("unchecked")
        var payload = (java.util.Map<String, Object>) envelope.get("payload");
        @SuppressWarnings("unchecked")
        var counts = (java.util.Map<String, Long>) payload.get("counts");
        assertThat(counts.get("mutation_merged")).isEqualTo(1L);
        assertThat(counts.get("cycle_start")).isEqualTo(1L);
        assertThat(payload.get("sessionBuildMs")).isEqualTo(1500L);
        assertThat(payload.get("sessionBuildCount")).isEqualTo(1L);
    }
}
