package com.walpro.autobuilder.controller;

import com.walpro.autobuilder.observability.behaviour.BehaviourEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * T5-H: SSE entry point for the behaviour trace stream. Each emitted line carries the
 * same {@code { timestamp, cycleId, event, payload }} envelope that the NDJSON
 * appender writes to {@code ${ROOT_PATH}/logs/behaviour.ndjson}.
 */
@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class BehaviourEventController {

    private final BehaviourEventPublisher publisher;

    @GetMapping(value = "/behaviour", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> behaviour() {
        if (publisher == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok(publisher.register());
    }
}
