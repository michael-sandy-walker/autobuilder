package com.walpro.autobuilder.core.tuning;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.CycleStartSignalSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TuningParameterStoreTest {

    @Test
    void get_prefersEnvironmentProperty_overCodeDefault() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("autobuilder.tuning.score.merge-acceptance-floor", "0.85");
        TuningParameterStore store = new TuningParameterStore(env);
        assertThat(store.get("score.merge-acceptance-floor", 0.75)).isEqualTo(0.85);
    }

    @Test
    void get_fallsBackToCodeDefault_whenPropertyAbsent() {
        TuningParameterStore store = new TuningParameterStore(new MockEnvironment());
        assertThat(store.get("score.merge-acceptance-floor", 0.75)).isEqualTo(0.75);
    }

    @Test
    void get_isStableForARun_firstResolutionWins() {
        MockEnvironment env = new MockEnvironment();
        TuningParameterStore store = new TuningParameterStore(env);
        assertThat(store.get("score.subgoal-closure", 0.75)).isEqualTo(0.75);
        env.setProperty("autobuilder.tuning.score.subgoal-closure", "0.90");
        assertThat(store.get("score.subgoal-closure", 0.75))
                .as("cached after first read — per-run stability")
                .isEqualTo(0.75);
    }

    @Test
    void set_isARejectedStub_noAutonomousWritePath() {
        TuningParameterStore store = new TuningParameterStore(new MockEnvironment());
        assertThatThrownBy(() -> store.set("anything", 1.0))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** DoD check: a configured value provably changes live behavior (seeder emission decay). */
    @Test
    void configuredRoleAnalysisSeedDecay_changesTheSeededSignal() {
        CycleStartSignalSeeder seeder = new CycleStartSignalSeeder();
        seeder.setTuningParameterStore(new TuningParameterStore(new MockEnvironment()
                .withProperty("autobuilder.tuning.signal-decay.role-analysis.seed", "0.30")));

        CycleContextData seeded = seeder.seedIfEmpty(
                CycleContextData.builder().build().withCurrentGoalId("g"));

        assertThat(seeded.windows().get("analysis").decayEquivalent()).isEqualTo(0.30);
        // untouched roles keep their code defaults
        assertThat(seeded.windows().get("evolution").decayEquivalent()).isEqualTo(0.25);
    }

    @Test
    void withoutStore_seederKeepsCodeDefaults() {
        CycleStartSignalSeeder seeder = new CycleStartSignalSeeder();
        CycleContextData seeded = seeder.seedIfEmpty(
                CycleContextData.builder().build().withCurrentGoalId("g"));
        assertThat(seeded.windows().get("analysis").decayEquivalent()).isEqualTo(0.0);
    }
}
