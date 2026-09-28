package com.walpro.autobuilder.core.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T4-CI phase 3: the live run's health, read against the population of healthy runs, for the loop to
 * consult -- today by the stuck layer's dossier (T4-DY), which hands the deviations to the model as
 * facts about the run so far.
 *
 * <p>Fed every behaviour event by {@link com.walpro.autobuilder.observability.behaviour.BehaviourLogger},
 * the one place every event passes, so the profile is the stream the envelope was frozen from. The
 * envelope is the operator's curated file ({@code scripts/run_health_baseline.json}, labels are a
 * judgement act); without it the loop reads no deviations, never invented ones.
 */
@Slf4j
@Component
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "the live run's shape so far; a restored run re-accumulates it from its own events")
public class RunHealth {

    private final RunHealthProfile profile = new RunHealthProfile();
    private final RunHealthEnvelope envelope;

    public RunHealth(@Value("${autobuilder.health.baseline-path:scripts/run_health_baseline.json}") String baselinePath) {
        this.envelope = new RunHealthEnvelope(load(baselinePath));
        log.info("T4-CI: run-health envelope from {} -- {} healthy run(s){}", baselinePath, envelope.size(),
                envelope.size() < RunHealthEnvelope.MIN_HEALTHY ? " (too few: no deviations will be read)" : "");
    }

    public synchronized void observe(String event, String cycleId, Map<String, ?> payload) {
        profile.observe(event, cycleId, payload);
    }

    public synchronized Map<String, Double> features() {
        return profile.features();
    }

    /** The features on their unhealthy side so far; empty without an envelope or before a shape exists. */
    public synchronized List<RunHealthEnvelope.Deviation> deviations() {
        return envelope.deviations(profile.features());
    }

    /** The healthy vectors of a frozen baseline file; empty when it is absent or unreadable. */
    static List<Map<String, Double>> load(String path) {
        List<Map<String, Double>> out = new ArrayList<>();
        if (path == null || path.isBlank()) return out;
        Path p = Path.of(path);
        if (!Files.isRegularFile(p)) return out;
        try {
            JsonNode runs = new ObjectMapper().readTree(p.toFile()).path("runs");
            runs.fields().forEachRemaining(e -> {
                JsonNode entry = e.getValue();
                if (!"healthy".equals(entry.path("label").asText())) return;
                Map<String, Double> f = new LinkedHashMap<>();
                entry.path("features").fields().forEachRemaining(x -> f.put(x.getKey(), x.getValue().asDouble()));
                if (!f.isEmpty()) out.add(f);
            });
        } catch (Exception e) {
            log.warn("T4-CI: run-health baseline {} unreadable ({}) -- no envelope", path, e.getMessage());
            out.clear();
        }
        return out;
    }
}
