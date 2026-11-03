package com.walpro.autobuilder.observability.behaviour;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-ED: older notable events, read back from the run's behaviour log the way the run writes it. */
class BehaviourHistoryTest {

    @TempDir
    Path dir;

    private static String envelope(String at, String event, Map<String, Object> payload) throws Exception {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("timestamp", at);
        e.put("cycleId", "c");
        e.put("event", event);
        e.put("payload", payload);
        return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(e) + "\n";
    }

    @Test
    void theNotableEventsBeforeAnInstantComeBackNewestFirst_wordedAsTheLiveListWordsThem() throws Exception {
        Path log = dir.resolve("behaviour.ndjson");
        Files.writeString(log, envelope("2026-11-03T20:00:00+01:00", "cycle_start", Map.of("subGoalDescription", "Implement Book"))
                + envelope("2026-11-03T20:00:05+01:00", "llm_call", Map.of("role", "evolution"))
                + envelope("2026-11-03T20:01:00+01:00", "mutation_merged", Map.of("files", List.of("src/a/Book.java")))
                + envelope("2026-11-03T20:02:00+01:00", "closure_refused", Map.of("reason", "GET /loans answers 500"))
                + envelope("2026-11-03T20:03:00+01:00", "cycle_start", Map.of("subGoalDescription", "Implement Loan")));
        BehaviourHistory history = new BehaviourHistory(log);

        List<Map<String, Object>> rows = history.before(Instant.parse("2026-11-03T19:02:30Z"), 50);

        assertThat(rows).extracting(r -> r.get("text"))
                .containsExactly("GET /loans answers 500", "1 file: Book.java", "Implement Book");
        assertThat(rows.get(0)).containsEntry("event", "closure_refused").containsEntry("at", "2026-11-03T19:02:00Z");
        assertThat(history.before(Instant.parse("2026-11-03T19:02:30Z"), 2)).extracting(r -> r.get("text"))
                .containsExactly("GET /loans answers 500", "1 file: Book.java");
        assertThat(history.before(Instant.parse("2026-11-03T18:00:00Z"), 50)).isEmpty();
    }

    @Test
    void noLogIsNoHistory() {
        assertThat(new BehaviourHistory(dir.resolve("absent.ndjson")).before(Instant.now(), 50)).isEmpty();
        assertThat(new BehaviourHistory("").before(Instant.now(), 50)).isEmpty();
    }
}
