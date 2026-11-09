package com.walpro.autobuilder.observability.narration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CycleRecordTest {

    private static Map<String, Object> env(String event, Map<String, Object> payload) {
        return Map.of("event", event, "cycleId", "cycle-1", "payload", payload);
    }

    @Test
    void writesTheCycleInTheOrderItHappenedWithTheAgentsOwnReasons() {
        CycleRecord r = new CycleRecord();
        r.add(env("cycle_start", Map.of("subGoalDescription", "Add EditHunks.apply")), null);
        r.add(env("mutation_proposed", Map.of("files", List.of("src/main/java/a/EditHunks.java"))), null);
        r.add(env("mutation_scored", Map.of("llmState", "REJECTED", "message", "8 tests fail because parse(String) skips edits.")), null);
        r.add(env("test_delta", Map.of("newlyFailing", 8, "newlyPassing", 0)), null);
        r.add(env("mutation_rejected", Map.of("reason", "the suite got worse")), null);
        r.add(env("cycle_end", Map.of("resultState", "CONTINUE")), null);

        assertThat(r.ended()).isTrue();
        assertThat(r.worthTelling()).isTrue();
        assertThat(r.render()).isEqualTo(String.join("\n",
                "Working on the subgoal: Add EditHunks.apply",
                "Files the loop changed: EditHunks.java.",
                "The evaluator's verdict: rejected.",
                "The evaluator's reason, in its own words: 8 tests fail because parse(String) skips edits.",
                "With the change, 8 tests that passed before now fail.",
                "The change was rejected: the suite got worse",
                "Nothing was kept in the project this cycle."));
    }

    @Test
    void namesTheGoalWhoseStatusChangedAndThePlannersReason() {
        CycleRecord r = new CycleRecord();
        r.add(env("planning_run", Map.of("injected", 2, "rationale", "The broad subgoal was stuck.")), null);
        r.add(env("goal_status_changed", Map.of("to", "SUBSUMED")), "Add tests for EditHunks");
        r.add(env("mutation_merged", Map.of("files", List.of("x/A.java", "x/B.java"))), null);
        r.add(env("cycle_end", Map.of("resultState", "GOAL_FULFILLED")), null);

        assertThat(r.render()).isEqualTo(String.join("\n",
                "The planner added 2 subgoals.",
                "The planner's reason for its plan, in its own words: The broad subgoal was stuck.",
                "The subgoal \"Add tests for EditHunks\" was replaced by narrower work.",
                "Kept in the project: A.java, B.java.",
                "With this cycle, the whole goal is fulfilled."));
    }

    @Test
    void aCycleThatOnlyStartedIsNotWorthTellingAndGateBuildsAreNotFixes() {
        CycleRecord r = new CycleRecord();
        r.add(env("cycle_start", Map.of("subGoalDescription", "Add X")), null);
        r.add(env("build_fix_attempt", Map.of("source", "test-gate", "outcome", "FAILED")), null);
        r.add(env("signal_snapshot", Map.of()), null);
        r.add(env("cycle_end", Map.of("resultState", "CONTINUE")), null);

        assertThat(r.worthTelling()).isFalse();
    }

    @Test
    void checksThatKeepTheEvaluatorsVerdictAgreeAndOnlyAChangedVerdictIsAnOverrule() {
        CycleRecord agreed = new CycleRecord();
        agreed.add(env("mutation_scored", Map.of("llmState", "REJECTED")), null);
        agreed.add(env("mutation_negotiated", Map.of("finalState", "REJECTED", "overrides", List.of("TestRegressionHeuristic"))), null);
        assertThat(agreed.render()).contains("Automatic checks agreed with the evaluator: rejected.").doesNotContain("overruled");

        CycleRecord overruled = new CycleRecord();
        overruled.add(env("mutation_scored", Map.of("llmState", "REJECTED")), null);
        overruled.add(env("mutation_negotiated", Map.of("finalState", "ACCEPTED", "overrides", List.of("X"))), null);
        assertThat(overruled.render()).contains("Automatic checks overruled the evaluator; the change ended up accepted.");

        CycleRecord noVerdict = new CycleRecord();
        noVerdict.add(env("mutation_negotiated", Map.of("finalState", "ACCEPTED", "overrides", List.of("X"))), null);
        assertThat(noVerdict.render()).isEqualTo("Automatic checks decided the change: accepted.");
    }

    @Test
    void theRunsWholeGoalIsNotQuotedAsASubgoalAndPathsAreSaidAsFileNames() {
        CycleRecord r = new CycleRecord();
        r.add(env("goal_status_changed", Map.of("to", "COMPLETED")), "An existing project, adopted ... (the seed, pages long)", true);
        r.add(env("cycle_start", Map.of("subGoalDescription", "Create src/test/java/a/b/ParserEditTest.java with tests")), null);
        assertThat(r.render()).isEqualTo(String.join("\n",
                "The whole goal of the run was completed.",
                "Working on the subgoal: Create ParserEditTest.java with tests"));
    }

    @Test
    void aHelpersTaggedIdBelongsToItsCycle() {
        assertThat(CycleRecord.cycleOf("cycle-1794153735178-eval-tests")).isEqualTo("cycle-1794153735178");
        assertThat(CycleRecord.cycleOf("cycle-42")).isEqualTo("cycle-42");
        assertThat(CycleRecord.cycleOf("start")).isNull();
        assertThat(CycleRecord.cycleOf(null)).isNull();
    }
}
