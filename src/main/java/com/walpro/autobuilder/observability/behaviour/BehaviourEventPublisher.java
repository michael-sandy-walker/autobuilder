package com.walpro.autobuilder.observability.behaviour;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * T5-H: SSE fan-out for behaviour events. Parallel to
 * {@link com.walpro.autobuilder.controller.goal.GoalStateEventPublisher} but carries
 * higher-frequency, per-event-type payloads instead of one fat goal-tree snapshot per
 * cycle. Slow/dead clients are pruned best-effort: a failed write removes the emitter
 * and broadcasting moves on.
 */
@Slf4j
@Component
public class BehaviourEventPublisher {

    /** Never time out — the client controls lifetime by closing the connection. */
    private static final long EMITTER_TIMEOUT_MS = Long.MAX_VALUE;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** Registers a new SSE subscriber. Returned emitter is auto-removed on close/error/timeout. */
    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitters.add(emitter);
        emitter.onCompletion(() -> {
            emitters.remove(emitter);
            log.debug("Behaviour SSE subscriber completed ({} remaining)", emitters.size());
        });
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            log.debug("Behaviour SSE subscriber timed out ({} remaining)", emitters.size());
        });
        emitter.onError(t -> {
            emitters.remove(emitter);
            log.debug("Behaviour SSE subscriber errored: {} ({} remaining)", t.getMessage(), emitters.size());
        });
        log.info("Behaviour SSE subscriber registered ({} total)", emitters.size());
        return emitter;
    }

    /** Number of live subscribers — exposed for tests and status endpoints. */
    public int subscriberCount() {
        return emitters.size();
    }

    /**
     * Broadcasts the rendered envelope to every live subscriber. The {@code envelope} object
     * is the JSON-ready map (or POJO) the caller has already prepared so that the SSE bytes
     * exactly match the NDJSON line.
     */
    public void publish(String eventName, Object envelope) {
        if (emitters.isEmpty()) return;
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(envelope));
            } catch (Exception e) {
                log.debug("Behaviour SSE broadcast failed for one subscriber: {}", e.getMessage());
                dead.add(emitter);
            }
        }
        if (!dead.isEmpty()) {
            emitters.removeAll(dead);
            for (SseEmitter d : dead) {
                try { d.completeWithError(new RuntimeException("broadcast failed")); } catch (Exception ignored) {}
            }
        }
    }
}
