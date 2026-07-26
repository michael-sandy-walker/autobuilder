package com.walpro.autobuilder.graph.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class OrchestrationSnapshotFromRowEdgeCasesTest {

    @Test
    void fromRow_recentActions_ignoresNonMapsAndKeepsValidOnes() {
        Map<String, Object> row = Map.of(
                "attemptId", "attempt-1",
                "recentActions", List.of(
                        "not-a-map",
                        123,
                        Map.of("id", "a1", "type", "evolution", "status", "ok", "cycleId", "c1", "attemptId", "attempt-1", "ts", "t1"),
                        Map.of("id", "a2") // partial map: should still parse with nulls
                )
        );

        OrchestrationSnapshot s = OrchestrationSnapshot.fromRow("g", "s", row);

        assertThat(s.recentActions()).hasSize(2);

        OrchestrationSnapshot.ActionRecord e1 = s.recentActions().get(0);
        assertThat(e1.id()).isEqualTo("a1");
        assertThat(e1.type()).isEqualTo("evolution");
        assertThat(e1.status()).isEqualTo("ok");
        assertThat(e1.cycleId()).isEqualTo("c1");
        assertThat(e1.attemptId()).isEqualTo("attempt-1");
        assertThat(e1.ts()).isEqualTo("t1");

        OrchestrationSnapshot.ActionRecord e2 = s.recentActions().get(1);
        assertThat(e2.id()).isEqualTo("a2");
        assertThat(e2.type()).isNull();
        assertThat(e2.status()).isNull();
        assertThat(e2.cycleId()).isNull();
        assertThat(e2.attemptId()).isNull();
        assertThat(e2.ts()).isNull();
    }

    @Test
    void fromRow_numericParsing_toleratesNonsense_andReturnsNull() {
        Map<String, Object> row = Map.of(
                "attemptId", "attempt-1",
                "changedFilesCount", "abc",
                "deletedFilesCount", "NaN",
                "evalScoreNow", "not-a-double",
                "evalScoreBefore", "also-bad",
                "evalDelta", "???"
        );

        OrchestrationSnapshot s = OrchestrationSnapshot.fromRow("g", "s", row);

        assertThat(s.changedFilesCount()).isNull();
        assertThat(s.deletedFilesCount()).isNull();

        assertThat(s.evalScoreNow()).isNull();
        assertThat(s.evalScoreBefore()).isNull();
        assertThat(s.evalDelta()).isNull();
    }

    @Test
    void fromRow_evalPayload_onlyAttachedWhenEvalContentIsJsonObject() {
        Map<String, Object> row = Map.ofEntries(
                Map.entry("attemptId", "attempt-1"),
                Map.entry("evalActionId", "eval-1"),
                Map.entry("evalStatus", "ok"),
                Map.entry("evalTs", "t"),
                Map.entry("evalContentNow", "[1,2,3]") // not an object => must not attach payload
        );

        OrchestrationSnapshot s = OrchestrationSnapshot.fromRow("g", "s", row);

        assertThat(s.lastEvaluation()).isNotNull();
        assertThat(s.lastEvaluation().payload()).isNull();
        assertThat(s.evalContentNow()).isEqualTo("[1,2,3]");
    }

    // --- T4-CJ slice 2: run attribution travels with the snapshot -------------------------

    @Test
    void fromRow_carriesTheRunThatProducedTheAnchorAttempt() {
        Map<String, Object> row = Map.of(
                "attemptId", "attempt-1",
                "attemptRunId", "run-aeb48106");

        OrchestrationSnapshot s = OrchestrationSnapshot.fromRow("g", "s", row);

        assertThat(s.attemptRunId()).isEqualTo("run-aeb48106");
    }

    @Test
    void fromRow_withoutTheStampLeavesRunAttributionNull() {
        // Rows written before the stamp existed must not masquerade as this run's evidence:
        // a null here makes the caller's equality check fail closed.
        Map<String, Object> row = Map.of("attemptId", "attempt-1");

        OrchestrationSnapshot s = OrchestrationSnapshot.fromRow("g", "s", row);

        assertThat(s.attemptRunId()).isNull();
    }

    @Test
    void empty_hasNoRunAttribution() {
        assertThat(OrchestrationSnapshot.empty("g", "s", "attempt-1").attemptRunId()).isNull();
    }

    @Test
    void withers_preserveRunAttribution() {
        OrchestrationSnapshot s = OrchestrationSnapshot
                .fromRow("g", "s", Map.of("attemptId", "a", "attemptRunId", "run-1"))
                .withRecurringCriticalHeuristics(List.of());

        assertThat(s.attemptRunId()).isEqualTo("run-1");
    }
}