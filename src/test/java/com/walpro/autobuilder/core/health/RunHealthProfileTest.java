package com.walpro.autobuilder.core.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * T4-CI phase 3: the live profile computes the features scripts/run_profile.py froze the envelope from.
 * Expected numbers are the script's own output on the same streams (run_profile.profile_events).
 */
class RunHealthProfileTest {

    private static final ObjectMapper M = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private static void feed(RunHealthProfile p, JsonNode e) {
        Map<String, Object> payload = e.hasNonNull("payload") ? M.convertValue(e.get("payload"), Map.class) : Map.of();
        p.observe(e.path("event").asText(null), e.hasNonNull("cycleId") ? e.get("cycleId").asText() : null, payload);
    }

    private RunHealthProfile archivedRun() throws Exception {
        RunHealthProfile p = new RunHealthProfile();
        try (InputStream in = new GZIPInputStream(getClass().getResourceAsStream(
                "/health/firestarter-2026-09-23_02-16-07.behaviour.ndjson.gz"));
             MappingIterator<JsonNode> it = M.readerFor(JsonNode.class).readValues(in)) {
            while (it.hasNext()) feed(p, it.next());
        }
        return p;
    }

    @Test
    void anArchivedRunsStreamGivesTheScriptsFeatures() throws Exception {
        Map<String, Double> expected = new HashMap<>(Map.of("cycles", 15.0, "merge_rate", 0.2, "reject_rate", 0.6,
                "inject_rate", 0.3333333333333333, "build_fix_rate", 2.0, "skip_rate", 3.0,
                "proposal_rate", 0.9333333333333333, "planner_only_share", 0.0, "max_inject_refires", 1.0,
                "inject_refire_share", 0.0));
        expected.put("merge_drought_share", 0.5012224938875306);
        expected.put("tail_share", 0.5012224938875306);

        Map<String, Double> f = archivedRun().features();

        expected.forEach((k, v) -> assertThat(f.get(k)).as(k).isCloseTo(v, within(1e-9)));
    }

    @Test
    void plannerOnlyCyclesAndRepeatedInjectionsAreCountedAsTheScriptCountsThem() {
        RunHealthProfile p = new RunHealthProfile();
        for (String c : List.of("c1", "c2", "c3", "c4")) p.observe("cycle_start", c, Map.of());
        p.observe("agent_selected", "c1", Map.of("role", "planner"));
        p.observe("agent_selected", "c2", Map.of("role", "planner"));
        p.observe("mutation_proposed", "c2", Map.of());
        p.observe("agent_selected", "c3", Map.of("role", "planner"));
        p.observe("mutation_merged", "c3", Map.of());
        p.observe("agent_selected", "c4", Map.of("role", "evolution"));
        p.observe("mutation_injected", "c4", Map.of("rule", "r1", "missingToken", "Nib"));
        p.observe("mutation_injected", "c4", Map.of("rule", "r1", "missingToken", "Nib"));
        p.observe("mutation_injected", "c4", Map.of("rule", "r2", "missingToken", "Ink"));
        p.observe("mutation_rejected", "c4", Map.of());
        p.observe("build_fix_attempt", null, Map.of());

        Map<String, Double> f = p.features();

        assertThat(f.get("planner_only_share")).isEqualTo(0.25);
        assertThat(f.get("max_inject_refires")).isEqualTo(2.0);
        assertThat(f.get("inject_refire_share")).isCloseTo(1 / 3.0, within(1e-12));
        assertThat(f.get("merge_drought_share")).isCloseTo(8 / 15.0, within(1e-12));
        assertThat(f.get("tail_share")).isCloseTo(0.4, within(1e-12));
        assertThat(f.get("build_fix_rate")).isEqualTo(0.25);
    }

    @Test
    void theRunIsReadAgainstTheOperatorsFrozenEnvelopeOnItsUnhealthySideOnly() throws Exception {
        RunHealthEnvelope envelope = new RunHealthEnvelope(RunHealth.load("scripts/run_health_baseline.json"));

        assertThat(envelope.size()).isGreaterThanOrEqualTo(RunHealthEnvelope.MIN_HEALTHY);
        // run_profile.py --report on the same run, same baseline: these five, and nothing else.
        assertThat(envelope.deviations(archivedRun().features())).extracting(RunHealthEnvelope.Deviation::feature)
                .containsExactlyInAnyOrder("reject_rate", "inject_rate", "skip_rate", "merge_drought_share", "tail_share");
    }

    @Test
    void noEnvelopeOrNoShapeYetMeansNoDeviations() {
        RunHealthProfile p = new RunHealthProfile();
        p.observe("cycle_start", "c1", Map.of());
        assertThat(new RunHealthEnvelope(RunHealth.load("scripts/run_health_baseline.json")).deviations(p.features()))
                .as("one cycle is no shape").isEmpty();
        assertThat(new RunHealthEnvelope(RunHealth.load("absent.json")).deviations(Map.of("cycles", 20.0, "tail_share", 1.0)))
                .as("no envelope").isEmpty();
    }
}
