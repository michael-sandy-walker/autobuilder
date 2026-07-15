package com.walpro.autobuilder.core.build;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** T5-P: build-cost accumulation semantics. */
class BuildCostTrackerTest {

    @Test
    void accumulatesPerCycleAndPerSession() {
        BuildCostTracker t = new BuildCostTracker();
        t.record("cycle-1", 2600);
        t.record("cycle-1", 1400); // second build same cycle (e.g. probe + post-merge gate)
        t.record("cycle-2", 3000);

        assertThat(t.cycleBuildMs("cycle-1")).isEqualTo(4000);
        assertThat(t.cycleInvocations("cycle-1")).isEqualTo(2);
        assertThat(t.cycleBuildMs("cycle-2")).isEqualTo(3000);
        assertThat(t.sessionBuildMs()).isEqualTo(7000);
        assertThat(t.sessionInvocations()).isEqualTo(3);
    }

    @Test
    void ignoresZeroAndNegativeDurations() {
        // Disabled/skipped executor returns durationMs=0 — must not inflate the count or total.
        BuildCostTracker t = new BuildCostTracker();
        t.record("cycle-1", 0);
        t.record("cycle-1", -5);
        t.record("cycle-1", 500);

        assertThat(t.cycleBuildMs("cycle-1")).isEqualTo(500);
        assertThat(t.cycleInvocations("cycle-1")).isEqualTo(1);
        assertThat(t.sessionInvocations()).isEqualTo(1);
    }

    @Test
    void nullCycleContributesToSessionOnly() {
        // The skipped path carries a null cycleId; it still counts toward the session total.
        BuildCostTracker t = new BuildCostTracker();
        t.record(null, 250);
        assertThat(t.sessionBuildMs()).isEqualTo(250);
        assertThat(t.cycleBuildMs(null)).isZero();
    }

    @Test
    void unknownCycleReadsZero() {
        BuildCostTracker t = new BuildCostTracker();
        assertThat(t.cycleBuildMs("never-seen")).isZero();
        assertThat(t.cycleInvocations("never-seen")).isZero();
    }
}
