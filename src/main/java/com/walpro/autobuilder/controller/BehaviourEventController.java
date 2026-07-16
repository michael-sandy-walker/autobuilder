package com.walpro.autobuilder.controller;

import com.walpro.autobuilder.core.build.BuildCostTracker;
import com.walpro.autobuilder.observability.behaviour.BehaviourEventPublisher;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * T5-H: SSE entry point for the behaviour trace stream. Each emitted line carries the
 * same {@code { timestamp, cycleId, event, payload }} envelope that the NDJSON
 * appender writes to {@code ${ROOT_PATH}/logs/behaviour.ndjson}.
 *
 * <p>T5-BF: subscription starts with a {@code hello} event carrying the session summary
 * (per-event-type counts, last cycle/mode, build cost totals) so a client joining mid-run
 * seeds its counters instead of starting at zero.
 */
@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class BehaviourEventController {

    private final BehaviourEventPublisher publisher;
    private final BehaviourLogger behaviourLogger;
    private final BuildCostTracker buildCostTracker;

    @GetMapping(value = "/behaviour", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> behaviour() {
        if (publisher == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok(publisher.register(helloEnvelope()));
    }

    /** Same envelope shape as every other behaviour event so the client parses uniformly. */
    private Map<String, Object> helloEnvelope() {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (behaviourLogger != null) {
            payload.putAll(behaviourLogger.sessionSummary());
        }
        if (buildCostTracker != null) {
            payload.put("sessionBuildMs", buildCostTracker.sessionBuildMs());
            payload.put("sessionBuildCount", buildCostTracker.sessionInvocations());
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("timestamp", OffsetDateTime.now().toString());
        envelope.put("cycleId", payload.get("lastCycleId"));
        envelope.put("event", "hello");
        envelope.put("payload", payload);
        return envelope;
    }
}
