package com.walpro.autobuilder.graph.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class OrchestrationSnapshotContractTest {

    @Test
    void empty_setsStableDefaults() {
        OrchestrationSnapshot s = OrchestrationSnapshot.empty("goal-1", "sub-1", "attempt-1");

        assertThat(s.goalId()).isEqualTo("goal-1");
        assertThat(s.subGoalId()).isEqualTo("sub-1");
        assertThat(s.attemptId()).isEqualTo("attempt-1");

        // critical: empty snapshot must not imply evidence exists
        assertThat(s.attemptCycleId()).isNull();
        assertThat(s.changedFilesCount()).isNull();
        assertThat(s.deletedFilesCount()).isNull();
        assertThat(s.fingerprint()).isNull();

        assertThat(s.lastEvolution()).isNull();
        assertThat(s.lastEvaluation()).isNull();
        assertThat(s.evalScoreNow()).isNull();
        assertThat(s.evalScoreBefore()).isNull();
        assertThat(s.evalDelta()).isNull();
        assertThat(s.evalContentNow()).isNull();

        assertThat(s.lastMerge()).isNull();
        assertThat(s.mergedViaGraph()).isNull();

        assertThat(s.recentActions()).isNotNull();
        assertThat(s.recentActions()).isEmpty();
    }

    @Test
    void fromRow_parsesNumericFieldsAndBuildsEvidence() {
        Map<String, Object> row = Map.ofEntries(
                Map.entry("attemptId", "attempt-1"),
                Map.entry("attemptCycleId", "cycle-123"),
                Map.entry("changedFilesCount", "2"),
                Map.entry("deletedFilesCount", 1),
                Map.entry("fingerprint", "fp-xyz"),

                Map.entry("evoActionId", "evo-1"),
                Map.entry("evoStatus", "ok"),
                Map.entry("evoTs", "2026-02-22T10:00:00Z"),

                Map.entry("evalActionId", "eval-1"),
                Map.entry("evalStatus", "ok"),
                Map.entry("evalTs", "2026-02-22T10:01:00Z"),
                Map.entry("evalScoreNow", "0.70"),
                Map.entry("evalScoreBefore", 0.55d),
                Map.entry("evalDelta", "-0.05"),
                Map.entry("evalContentNow", "{\"summary\":\"fine\",\"n\":1}"),

                Map.entry("mergeActionId", "merge-1"),
                Map.entry("mergeStatus", "ok"),
                Map.entry("mergeTs", "2026-02-22T10:02:00Z"),
                Map.entry("mergedViaGraph", true),

                Map.entry("recentActions", List.of(
                        Map.of("id", "a1", "type", "evolution", "status", "ok", "cycleId", "c1", "attemptId", "attempt-1", "ts", "t1"),
                        Map.of("id", "a2", "type", "evaluation", "status", "ok", "cycleId", "c1", "attemptId", "attempt-1", "ts", "t2")
                ))
        );

        OrchestrationSnapshot s = OrchestrationSnapshot.fromRow("goal-1", "sub-1", row);

        assertThat(s.goalId()).isEqualTo("goal-1");
        assertThat(s.subGoalId()).isEqualTo("sub-1");

        assertThat(s.attemptId()).isEqualTo("attempt-1");
        assertThat(s.attemptCycleId()).isEqualTo("cycle-123");
        assertThat(s.changedFilesCount()).isEqualTo(2);
        assertThat(s.deletedFilesCount()).isEqualTo(1);
        assertThat(s.fingerprint()).isEqualTo("fp-xyz");

        assertThat(s.lastEvolution()).isNotNull();
        assertThat(s.lastEvolution().id()).isEqualTo("evo-1");
        assertThat(s.lastEvolution().type()).isEqualTo("evolution");
        assertThat(s.lastEvolution().status()).isEqualTo("ok");
        assertThat(s.lastEvolution().attemptId()).isEqualTo("attempt-1");
        assertThat(s.lastEvolution().ts()).isEqualTo("2026-02-22T10:00:00Z");
        assertThat(s.lastEvolution().payload()).isNull();

        assertThat(s.lastEvaluation()).isNotNull();
        assertThat(s.lastEvaluation().id()).isEqualTo("eval-1");
        assertThat(s.lastEvaluation().type()).isEqualTo("evaluation");
        assertThat(s.lastEvaluation().status()).isEqualTo("ok");
        assertThat(s.lastEvaluation().attemptId()).isEqualTo("attempt-1");
        assertThat(s.lastEvaluation().ts()).isEqualTo("2026-02-22T10:01:00Z");
        assertThat(s.lastEvaluation().payload()).isNotNull();
        assertThat(s.lastEvaluation().payload()).containsEntry("summary", "fine");
        assertThat(s.lastEvaluation().payload()).containsEntry("n", 1);

        assertThat(s.evalScoreNow()).isEqualTo(0.70d);
        assertThat(s.evalScoreBefore()).isEqualTo(0.55d);
        assertThat(s.evalDelta()).isEqualTo(-0.05d);
        assertThat(s.evalContentNow()).isEqualTo("{\"summary\":\"fine\",\"n\":1}");

        assertThat(s.lastMerge()).isNotNull();
        assertThat(s.lastMerge().id()).isEqualTo("merge-1");
        assertThat(s.lastMerge().type()).isEqualTo("merge");
        assertThat(s.lastMerge().status()).isEqualTo("ok");
        assertThat(s.lastMerge().attemptId()).isEqualTo("attempt-1");
        assertThat(s.mergedViaGraph()).isTrue();

        assertThat(s.recentActions()).hasSize(2);
        assertThat(s.recentActions().get(0).id()).isEqualTo("a1");
        assertThat(s.recentActions().get(1).id()).isEqualTo("a2");
    }

    @Test
    void fromRow_invalidEvalContentNow_doesNotCrashAndDoesNotAttachPayload() {
        Map<String, Object> row = Map.of(
                "attemptId", "attempt-1",
                "evalActionId", "eval-1",
                "evalStatus", "ok",
                "evalTs", "t",
                "evalContentNow", "not-json"
        );

        OrchestrationSnapshot s = OrchestrationSnapshot.fromRow("g", "s", row);

        assertThat(s.lastEvaluation()).isNotNull();
        assertThat(s.lastEvaluation().payload()).isNull();
        assertThat(s.evalContentNow()).isEqualTo("not-json");
    }
}