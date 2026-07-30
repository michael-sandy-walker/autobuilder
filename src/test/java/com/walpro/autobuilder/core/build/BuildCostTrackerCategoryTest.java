package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T6-J: the cost tracker was widened from builds to named categories because builds were never
 * the expensive part — LLM calls and builds together explained under a quarter of a run's minutes
 * while the gates that package and boot the artifact were untimed entirely.
 */
class BuildCostTrackerCategoryTest {

    @Test
    void theBuildCategoryKeepsItsOriginalSessionTotals() {
        // T5-P's readings must stay comparable across the widening, or the change quietly
        // invalidates every build-cost figure recorded before it.
        BuildCostTracker t = new BuildCostTracker();
        t.record("c1", 500);
        t.record("c1", BuildCostTracker.BUILD, 300);

        assertThat(t.sessionBuildMs()).isEqualTo(800);
        assertThat(t.sessionInvocations()).isEqualTo(2);
        assertThat(t.cycleBuildMs("c1")).isEqualTo(800);
    }

    @Test
    void aNonBuildCategoryDoesNotInflateTheBuildTotals() {
        // A boot gate is not a build. Folding it into sessionBuildMs would make the one figure
        // that WAS trustworthy stop being so.
        BuildCostTracker t = new BuildCostTracker();
        t.record("c1", 500);
        t.record("c1", "boot-gate", 90_000);

        assertThat(t.sessionBuildMs()).isEqualTo(500);
        assertThat(t.sessionMs("boot-gate")).isEqualTo(90_000);
        assertThat(t.sessionCount("boot-gate")).isEqualTo(1);
    }

    @Test
    void theReadoutNamesTheMostExpensiveCategoryFirst() {
        BuildCostTracker t = new BuildCostTracker();
        t.record("c1", 1_000);
        t.record("c1", "boot-gate", 90_000);
        t.record("c1", "package-gate", 20_000);

        String readout = t.costReadout();

        assertThat(readout).startsWith("boot-gate=90000ms/1");
        assertThat(readout).contains("package-gate=20000ms/1");
        assertThat(readout).contains("build=1000ms/1");
        assertThat(readout.indexOf("boot-gate")).isLessThan(readout.indexOf("package-gate"));
    }

    @Test
    void aRunThatRecordedNothingSaysSoRatherThanReportingZeroes() {
        assertThat(new BuildCostTracker().costReadout()).isEqualTo("(nothing recorded)");
    }

    @Test
    void zeroAndNegativeDurationsAreIgnoredInEveryCategory() {
        BuildCostTracker t = new BuildCostTracker();
        t.record("c1", "boot-gate", 0);
        t.record("c1", "boot-gate", -5);

        assertThat(t.sessionMs("boot-gate")).isZero();
        assertThat(t.sessionCount("boot-gate")).isZero();
    }

    @Test
    void aNullCategoryIsTreatedAsABuild() {
        // The single-argument record() delegates with BUILD; a null arriving by another route
        // must not silently create an unnamed bucket nobody reads.
        BuildCostTracker t = new BuildCostTracker();
        t.record("c1", null, 400);

        assertThat(t.sessionBuildMs()).isEqualTo(400);
        assertThat(t.costReadout()).contains("build=400ms/1");
    }
}
