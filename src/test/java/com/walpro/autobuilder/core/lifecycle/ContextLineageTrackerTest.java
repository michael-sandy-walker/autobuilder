package com.walpro.autobuilder.core.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    @Test
    void enrichLineage_withNullPrevious_leavesLastEvaluatedEvolutionFingerprintNull() {
        CycleContextData base = CycleContextData.builder()
                .cycleName("cycle-next")
                .build();

        CycleContextData result = tracker.enrichLineage(base, null);

        assertThat(result.lastEvaluatedEvolutionFingerprint()).isNull();
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
                .as("After merge, fingerprints must match so notYetEvaluated=false and EVALUATE/T2-E branch is skipped")
                .isFalse();
    }
}
