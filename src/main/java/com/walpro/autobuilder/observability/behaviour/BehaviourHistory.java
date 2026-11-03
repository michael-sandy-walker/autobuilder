package com.walpro.autobuilder.observability.behaviour;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * T4-ED: the run's notable events further back than {@link BehaviourLogger#live()} keeps -- read from the run's own
 * behaviour log, so the home screen can scroll back to the run's start while each refresh stays small.
 */
@Slf4j
@Component
public class BehaviourHistory {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    @Autowired // two constructors: without this Spring cannot choose and the run context does not start
    public BehaviourHistory(@Value("${file.base-path:}") String basePath) {
        this.file = basePath == null || basePath.isBlank() ? null : Path.of(basePath, "logs", "behaviour.ndjson");
    }

    /** Visible for testing: reads {@code file} directly. */
    BehaviourHistory(Path file) {
        this.file = file;
    }

    /**
     * The {@code limit} notable events just before {@code before}, newest first, worded as {@link BehaviourLogger#row}
     * words them. The log holds pretty-printed JSON values one after another, read as a stream.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> before(Instant before, int limit) {
        if (file == null || !Files.isRegularFile(file) || limit <= 0) return List.of();
        ArrayDeque<Map<String, Object>> kept = new ArrayDeque<>();
        try (JsonParser parser = MAPPER.createParser(file.toFile());
             MappingIterator<JsonNode> values = MAPPER.readValues(parser, JsonNode.class)) {
            while (values.hasNextValue()) {
                JsonNode v = values.nextValue();
                Instant at;
                try {
                    at = OffsetDateTime.parse(v.path("timestamp").asText()).toInstant();
                } catch (RuntimeException e) {
                    continue;
                }
                if (!at.isBefore(before)) break;
                Map<String, Object> row = BehaviourLogger.row(at, v.path("event").asText(),
                        MAPPER.convertValue(v.path("payload"), Map.class));
                if (row == null) continue;
                kept.addLast(row);
                if (kept.size() > limit) kept.removeFirst();
            }
        } catch (IOException | RuntimeException e) {
            log.warn("T4-ED: the behaviour log could not be read back ({}); older events are not shown", e.getMessage());
        }
        List<Map<String, Object>> out = new ArrayList<>(kept);
        Collections.reverse(out);
        return out;
    }
}
