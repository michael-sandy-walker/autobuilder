package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextLineageTrackerTest {

    private ContextLineageTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ContextLineageTracker();
    }

    @Test
    void enrichLineage_carriesLastEvaluatedEvolutionFingerprintFromPreviousContext() {
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-prev")
                .lastEvolutionFingerprint("fp-1")
                .lastEvaluatedEvolutionFingerprint("fp-1")
                .build();

        CycleContextData base = CycleContextData.builder()
                .cycleName("cycle-next")
                .build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.lastEvaluatedEvolutionFingerprint())
                .as("lastEvaluatedEvolutionFingerprint must be carried from previous context")
                .isEqualTo("fp-1");
    }

    // ── T1-BO: lastEvolutionOutcome reset on subgoal change ───────────────────

    @Test
    void t1bo_subgoalUnchanged_carriesLastEvolutionOutcome() {
        EvolutionOutcome outcome = new EvolutionOutcome(List.of(), List.of(), "stub", "stub");
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-prev")
                .currentSubGoalId("sg-1")
                .lastEvolutionOutcome(outcome)
                .build();
        CycleContextData base = CycleContextData.builder()
                .cycleName("cycle-next")
                .currentSubGoalId("sg-1")
                .build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.lastEvolutionOutcome())
                .as("same subgoal: lastEvolutionOutcome must be carried forward")
                .isSameAs(outcome);
    }

    @Test
    void t1bo_subgoalChanged_resetsLastEvolutionOutcome() {
        EvolutionOutcome outcome = new EvolutionOutcome(List.of(), List.of(), "stub", "stub");
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-prev")
                .currentSubGoalId("sg-1")
                .lastEvolutionOutcome(outcome)
                .build();
        CycleContextData base = CycleContextData.builder()
                .cycleName("cycle-next")
                .currentSubGoalId("sg-2")
                .build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.lastEvolutionOutcome())
                .as("subgoal changed: lastEvolutionOutcome must be null so Rule C counts from zero")
                .isNull();
    }

    @Test
    void enrichLineage_withNullPrevious_leavesLastEvaluatedEvolutionFingerprintNull() {
        CycleContextData base = CycleContextData.builder()
                .cycleName("cycle-next")
                .build();

        CycleContextData result = tracker.enrichLineage(base, null);

        assertThat(result.lastEvaluatedEvolutionFingerprint()).isNull();
    }

    @Test
    void subgoalChanged_clearsLastEvolutionFingerprint() {
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-prev")
                .currentSubGoalId("sg-1")
                .lastEvolutionFingerprint("fp-1")
                .lastEvaluatedEvolutionFingerprint("fp-1")
                .build();
        CycleContextData base = CycleContextData.builder()
                .cycleName("cycle-next")
                .currentSubGoalId("sg-2")
                .build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.lastEvolutionFingerprint())
                .as("subgoal changed: lastEvolutionFingerprint must be cleared so evaluation is not blocked on the new subgoal")
                .isNull();
        assertThat(result.lastEvaluatedEvolutionFingerprint())
                .as("subgoal changed: lastEvaluatedEvolutionFingerprint must be cleared")
                .isNull();
    }

    @Test
    void subgoalUnchanged_carriesFingerprints() {
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-prev")
                .currentSubGoalId("sg-1")
                .lastEvolutionFingerprint("fp-1")
                .lastEvaluatedEvolutionFingerprint("fp-1")
                .build();
        CycleContextData base = CycleContextData.builder()
                .cycleName("cycle-next")
                .currentSubGoalId("sg-1")
                .build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.lastEvolutionFingerprint()).isEqualTo("fp-1");
        assertThat(result.lastEvaluatedEvolutionFingerprint()).isEqualTo("fp-1");
    }

    @Test
    void enrichLineage_afterMerge_notYetEvaluatedIsFalseInNextCycle() {
        // Simulate: evolution ran (fp-1), evaluation ran (lastEvaluatedFp=fp-1), merge happened.
        // Next cycle: fingerprints should still match → notYetEvaluated=false.
        CycleContextData previousAfterMerge = CycleContextData.builder()
                .cycleName("cycle-merge")
                .lastEvolutionFingerprint("fp-1")
                .lastEvaluatedEvolutionFingerprint("fp-1")
                .build();

        CycleContextData base = CycleContextData.builder()
                .cycleName("cycle-next")
                .build();

        CycleContextData next = tracker.enrichLineage(base, previousAfterMerge);

        boolean notYetEvaluated = !java.util.Objects.equals(
                next.lastEvolutionFingerprint(),
                next.lastEvaluatedEvolutionFingerprint()
        );

        assertThat(notYetEvaluated)
                .as("After merge, fingerprints must match so notYetEvaluated=false and EVALUATE branch is skipped")
                .isFalse();
    }

    // ── T3-BR: evaluatorRepeatRetries lineage ─────────────────────────────────

    @Test
    void t3br_subgoalUnchanged_carriesEvaluatorRepeatRetries() {
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-prev")
                .currentSubGoalId("sg-1")
                .evaluatorRepeatRetries(2)
                .build();
        CycleContextData base = CycleContextData.builder()
                .cycleName("cycle-next")
                .currentSubGoalId("sg-1")
                .build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.evaluatorRepeatRetries())
                .as("same subgoal, no merge: retry counter must carry forward")
                .isEqualTo(2);
    }

    @Test
    void t3br_subgoalChanged_resetsEvaluatorRepeatRetries() {
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-prev")
                .currentSubGoalId("sg-1")
                .evaluatorRepeatRetries(2)
                .build();
        CycleContextData base = CycleContextData.builder()
                .cycleName("cycle-next")
                .currentSubGoalId("sg-2")
                .build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.evaluatorRepeatRetries())
                .as("subgoal changed: retry counter must reset to 0")
                .isEqualTo(0);
    }

    // ── T4-AN-1: buildOutcome lineage ─────────────────────────────────────────

    @Test
    void t4an1_buildOutcome_carriesForwardAcrossCycles() {
        com.walpro.autobuilder.core.build.BuildOutcome outcome = new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR,
                "[ERROR] missing semicolon",
                1234L,
                "mvn compile",
                "cycle-prev"
        );
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-prev")
                .currentSubGoalId("sg-1")
                .buildOutcome(outcome)
                .build();
        CycleContextData base = CycleContextData.builder()
                .cycleName("cycle-next")
                .currentSubGoalId("sg-1")
                .build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.buildOutcome())
                .as("buildOutcome must carry forward — codebase compile state persists across cycles")
                .isSameAs(outcome);
    }

    @Test
    void t4an1_buildOutcome_carriesForwardAcrossSubgoalChange() {
        com.walpro.autobuilder.core.build.BuildOutcome outcome = new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_OK, "", 100L, "mvn compile", "cycle-prev");
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-prev")
                .currentSubGoalId("sg-1")
                .buildOutcome(outcome)
                .build();
        CycleContextData base = CycleContextData.builder()
                .cycleName("cycle-next")
                .currentSubGoalId("sg-2")
                .build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.buildOutcome())
                .as("subgoal change does not invalidate codebase compile state")
                .isSameAs(outcome);
    }

    @Test
    void t3br_afterMerge_resetsEvaluatorRepeatRetries() {
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-prev")
                .currentSubGoalId("sg-1")
                .merged(true)
                .evaluatorRepeatRetries(2)
                .build();
        CycleContextData base = CycleContextData.builder()
                .cycleName("cycle-next")
                .currentSubGoalId("sg-1")
                .build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.evaluatorRepeatRetries())
                .as("merge clears the cycle: retry counter must reset to 0")
                .isEqualTo(0);
    }
}
