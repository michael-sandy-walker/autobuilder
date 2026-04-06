package com.walpro.autobuilder.graph.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for T2-AW: recurring CRITICAL heuristic detection and formatter output.
 * Uses a hand-rolled stub for Neo4jClient to avoid Spring context overhead.
 */
class RecurringCriticalHeuristicsTest {

    private static final ObjectMapper OM = new ObjectMapper();

    // Minimal stub to supply payloads directly without a real Neo4j connection.
    // fetchRecurringCriticalHeuristics is package-private, called with pre-seeded payloads.
    private OrchestrationSnapshotService serviceWithPayloads(List<String> payloads) {
        Neo4jClient neo4j = mock(Neo4jClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);

        List<Map<String, Object>> rows = payloads.stream()
                .map(p -> (Map<String, Object>) Map.<String, Object>of("content", p))
                .toList();

        when(neo4j.query(anyString())
                .bindAll(org.mockito.ArgumentMatchers.anyMap())
                .fetch()
                .all())
                .thenReturn(rows);

        return new OrchestrationSnapshotService(neo4j);
    }

    private static String payload(boolean critical, String name, String rationale) throws Exception {
        return OM.writeValueAsString(Map.of(
                "heuristicOpinions", List.of(
                        Map.of("name", name, "confidence", 0.95, "level", "CRITICAL",
                                "rationale", rationale, "critical", critical)
                )
        ));
    }

    // -------------------------------------------------------------------------

    @Test
    void detectsHeuristicWithTwoCriticalHits() throws Exception {
        String p1 = payload(true, "SemanticClarityHeuristic", "full-file replace");
        String p2 = payload(true, "SemanticClarityHeuristic", "full-file replace again");

        var service = serviceWithPayloads(List.of(p1, p2));
        var warnings = service.fetchRecurringCriticalHeuristics("sub-1", 10);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).name()).isEqualTo("SemanticClarityHeuristic");
        assertThat(warnings.get(0).criticalCount()).isEqualTo(2);
        assertThat(warnings.get(0).lastRationale()).isEqualTo("full-file replace again");
    }

    @Test
    void ignoresNonCriticalOpinions() throws Exception {
        String p1 = payload(false, "ScoreRegressionHeuristic", "minor drop");
        String p2 = payload(false, "ScoreRegressionHeuristic", "minor drop");

        var service = serviceWithPayloads(List.of(p1, p2));
        var warnings = service.fetchRecurringCriticalHeuristics("sub-1", 10);

        assertThat(warnings).isEmpty();
    }

    @Test
    void doesNotFlagSingleCriticalHit() throws Exception {
        String p1 = payload(true, "SuspiciousScoreHeuristic", "score too high");
        String p2 = payload(false, "SuspiciousScoreHeuristic", "ok");

        var service = serviceWithPayloads(List.of(p1, p2));
        var warnings = service.fetchRecurringCriticalHeuristics("sub-1", 10);

        assertThat(warnings).isEmpty();
    }

    @Test
    void handlesEmptyOrUnparsablePayloads() throws Exception {
        var service = serviceWithPayloads(List.of("{}", "not-json", ""));
        var warnings = service.fetchRecurringCriticalHeuristics("sub-1", 10);

        assertThat(warnings).isEmpty();
    }

    @Test
    void withRecurringCriticalHeuristics_attachesWarningsToSnapshot() {
        OrchestrationSnapshot base = OrchestrationSnapshot.empty("g", "s", "a");
        var warning = new OrchestrationSnapshot.HeuristicWarning("H1", 3, "repeated pattern");

        OrchestrationSnapshot updated = base.withRecurringCriticalHeuristics(List.of(warning));

        assertThat(updated.recurringCriticalHeuristics()).hasSize(1);
        assertThat(updated.recurringCriticalHeuristics().get(0).name()).isEqualTo("H1");
        assertThat(updated.recurringCriticalHeuristics().get(0).criticalCount()).isEqualTo(3);
        // other fields unchanged
        assertThat(updated.goalId()).isEqualTo("g");
        assertThat(updated.recentActions()).isEmpty();
    }

    // -------------------------------------------------------------------------

    @Test
    void formatter_includesRecurringHeuristicBlock() {
        var warning = new OrchestrationSnapshot.HeuristicWarning(
                "SemanticClarityHeuristic", 4, "full-file blob replace, no deletions");
        OrchestrationSnapshot snap = OrchestrationSnapshot.empty("g", "s", "a")
                .withRecurringCriticalHeuristics(List.of(warning));

        String output = OrchestrationSnapshotFormatter.toLLMBlock(snap, 5);

        assertThat(output).contains("--- Recurring Heuristic Warnings ---");
        assertThat(output).contains("SemanticClarityHeuristic");
        assertThat(output).contains("critical x4");
        assertThat(output).contains("full-file blob replace");
    }

    @Test
    void formatter_omitsBlockWhenNoWarnings() {
        OrchestrationSnapshot snap = OrchestrationSnapshot.empty("g", "s", "a");

        String output = OrchestrationSnapshotFormatter.toLLMBlock(snap, 5);

        assertThat(output).doesNotContain("Recurring Heuristic Warnings");
    }
}
