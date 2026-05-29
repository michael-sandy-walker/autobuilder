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

    // ── T2-DD: rollback streak math ──────────────────────────────────────────

    @Test
    void enrichLineage_T2DD_consecutiveSameKindAndPath_incrementsStreakAndEmitsLocusStuck() {
        // Cycle 1: first rollback ever — streak = 1, no signal yet.
        com.walpro.autobuilder.core.merge.RolledBackEvidence rb =
                new com.walpro.autobuilder.core.merge.RolledBackEvidence(
                        "cycle-1", List.of("Foo.java"), "cannot find symbol",
                        com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL);
        CycleContextData first = CycleContextData.builder()
                .cycleName("cycle-1")
                .rolledBackThisCycle(true)
                .lastRollback(rb)
                .build();

        CycleContextData secondBase = CycleContextData.builder().cycleName("cycle-2").build();
        CycleContextData second = tracker.enrichLineage(secondBase, first);
        assertThat(second.rollbackStreak()).isEqualTo(1);
        assertThat(second.rollbackStreakKey()).isEqualTo("CANNOT_FIND_SYMBOL|Foo.java");
        assertThat(second.hasSignal(BasicSignals.BUILD_FIX_LOCUS_STUCK))
                .as("streak=1 must NOT fire BUILD_FIX_LOCUS_STUCK")
                .isFalse();

        // Cycle 2 also rolls back with same key. enrichLineage at cycle-3 sees streak=2.
        CycleContextData secondWithRb = second
                .withRolledBackThisCycle(true)
                .withLastRollback(rb);
        CycleContextData thirdBase = CycleContextData.builder().cycleName("cycle-3").build();
        CycleContextData third = tracker.enrichLineage(thirdBase, secondWithRb);
        assertThat(third.rollbackStreak()).isEqualTo(2);
        assertThat(third.hasSignal(BasicSignals.BUILD_FIX_LOCUS_STUCK))
                .as("streak=2 must fire BUILD_FIX_LOCUS_STUCK")
                .isTrue();
    }

    @Test
    void enrichLineage_T2DD_differentKindResetsStreak() {
        com.walpro.autobuilder.core.merge.RolledBackEvidence rb1 =
                new com.walpro.autobuilder.core.merge.RolledBackEvidence(
                        "cycle-1", List.of("Foo.java"), "cannot find symbol",
                        com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL);
        CycleContextData prev = CycleContextData.builder()
                .cycleName("cycle-2")
                .rolledBackThisCycle(true)
                .lastRollback(rb1)
                .rollbackStreakKey("CANNOT_FIND_SYMBOL|Foo.java")
                .rollbackStreak(1)
                .build();

        // New rollback, different kind → streak resets to 1.
        com.walpro.autobuilder.core.merge.RolledBackEvidence rb2 =
                new com.walpro.autobuilder.core.merge.RolledBackEvidence(
                        "cycle-2", List.of("Foo.java"), "package x does not exist",
                        com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind.PACKAGE_DOES_NOT_EXIST);
        CycleContextData prevWithRb2 = prev.withLastRollback(rb2);

        CycleContextData nextBase = CycleContextData.builder().cycleName("cycle-3").build();
        CycleContextData next = tracker.enrichLineage(nextBase, prevWithRb2);
        assertThat(next.rollbackStreak()).isEqualTo(1);
        assertThat(next.hasSignal(BasicSignals.BUILD_FIX_LOCUS_STUCK)).isFalse();
    }

    @Test
    void enrichLineage_T2DD_successfulMergeResetsStreak() {
        CycleContextData prev = CycleContextData.builder()
                .cycleName("cycle-merged")
                .merged(true)
                .rolledBackThisCycle(false)
                .rollbackStreakKey("CANNOT_FIND_SYMBOL|Foo.java")
                .rollbackStreak(2)
                .build();

        CycleContextData nextBase = CycleContextData.builder().cycleName("cycle-next").build();
        CycleContextData next = tracker.enrichLineage(nextBase, prev);
        assertThat(next.rollbackStreak()).isZero();
        assertThat(next.rollbackStreakKey()).isNull();
        assertThat(next.hasSignal(BasicSignals.BUILD_FIX_LOCUS_STUCK)).isFalse();
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

    // ── T1-DU: BUILD_BROKEN signal lifecycle ──

    @Test
    void t1du_compileError_emitsBuildBrokenSignal() {
        com.walpro.autobuilder.core.build.BuildOutcome err = new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR, "[ERROR]", 100L, "mvn", "c-prev");
        CycleContextData previous = CycleContextData.builder().cycleName("c-prev").buildOutcome(err).build();
        CycleContextData base = CycleContextData.builder().cycleName("c-next").build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        Signal s = result.getSignal(BasicSignals.BUILD_BROKEN);
        assertThat(s).isNotNull();
        assertThat(s.strength()).isEqualTo(1.0);
        assertThat(s.decayPerCycle()).isEqualTo(0.0);
    }

    @Test
    void t1du_compileOk_clearsBuildBrokenSignal() {
        com.walpro.autobuilder.core.build.BuildOutcome ok = new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_OK, "", 100L, "mvn", "c-prev");
        CycleContextData previous = CycleContextData.builder().cycleName("c-prev").buildOutcome(ok).build();
        CycleContextData base = CycleContextData.builder().cycleName("c-next").build()
                .withSignal(Signal.of(BasicSignals.BUILD_BROKEN, 1.0, 0.0, "stale"));

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.hasSignal(BasicSignals.BUILD_BROKEN))
                .as("COMPILE_OK must clear stale BUILD_BROKEN signal")
                .isFalse();
    }

    // ── T1-DX: rollback evidence carry-forward ──

    @Test
    void t1dx_carriesLastRollbackForwardOneCycle() {
        com.walpro.autobuilder.core.merge.RolledBackEvidence evidence = new com.walpro.autobuilder.core.merge.RolledBackEvidence(
                "cycle-rb", List.of("a.java"), "cannot find symbol: Foo",
                com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL);
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-rb")
                .rolledBackThisCycle(true)
                .lastRollback(evidence)
                .build();
        CycleContextData base = CycleContextData.builder().cycleName("cycle-next").build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.lastRollback()).isSameAs(evidence);
        // rolledBackThisCycle is per-cycle state — must NOT survive carry-forward.
        assertThat(result.rolledBackThisCycle()).isFalse();
    }

    @Test
    void t1dx_dropsLastRollbackAfterOneCycleTtl() {
        // Cycle N rolled back; cycle N+1 carried the evidence; cycle N+2 must NOT see it.
        com.walpro.autobuilder.core.merge.RolledBackEvidence evidence = new com.walpro.autobuilder.core.merge.RolledBackEvidence(
                "cycle-rb", List.of("a.java"), "cannot find symbol: Foo",
                com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL);
        // previous (cycle N+1) carried the evidence but did NOT roll back itself.
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-after-rb")
                .rolledBackThisCycle(false)
                .lastRollback(evidence)
                .build();
        CycleContextData base = CycleContextData.builder().cycleName("cycle-N+2").build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.lastRollback())
                .as("rollback evidence has single-cycle TTL — must not propagate to N+2")
                .isNull();
    }

    // ── T4-BA: package-fragmentation evidence carry-forward ──

    @Test
    void t4ba_carriesPackageFragmentationForwardOneCycle() {
        com.walpro.autobuilder.core.merge.PackageFragmentationEvidence evidence =
                new com.walpro.autobuilder.core.merge.PackageFragmentationEvidence("cycle-frag",
                        List.of(new com.walpro.autobuilder.core.merge.PackageFragmentationEvidence.Conflict(
                                "Foo", "a/Foo.java", "a/b/Foo.java")));
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-frag")
                .packageFragmentationThisCycle(true)
                .packageFragmentation(evidence)
                .build();
        CycleContextData base = CycleContextData.builder().cycleName("cycle-next").build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.packageFragmentation()).isSameAs(evidence);
        // packageFragmentationThisCycle is per-cycle state — must NOT survive carry-forward.
        assertThat(result.packageFragmentationThisCycle()).isFalse();
    }

    @Test
    void t4ba_dropsPackageFragmentationAfterOneCycleTtl() {
        // Cycle N rejected a fragmenting mutation; cycle N+1 carried it; cycle N+2 must not.
        com.walpro.autobuilder.core.merge.PackageFragmentationEvidence evidence =
                new com.walpro.autobuilder.core.merge.PackageFragmentationEvidence("cycle-frag",
                        List.of(new com.walpro.autobuilder.core.merge.PackageFragmentationEvidence.Conflict(
                                "Foo", "a/Foo.java", "a/b/Foo.java")));
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-after-frag")
                .packageFragmentationThisCycle(false)
                .packageFragmentation(evidence)
                .build();
        CycleContextData base = CycleContextData.builder().cycleName("cycle-N+2").build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.packageFragmentation())
                .as("fragmentation evidence has single-cycle TTL — must not propagate to N+2")
                .isNull();
    }

    @Test
    void t1du_noBuildOutcome_noBuildBrokenSignal() {
        CycleContextData previous = CycleContextData.builder().cycleName("c-prev").build();
        CycleContextData base = CycleContextData.builder().cycleName("c-next").build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.hasSignal(BasicSignals.BUILD_BROKEN)).isFalse();
    }

    // ── T1-DZ: BUILD_BROKEN also fires from rollback evidence ──

    @Test
    void t1dz_lastRollbackPresent_emitsBuildBrokenSignal() {
        // T1-DW restores buildOutcome=null on rollback. T1-DZ: lastRollback evidence still
        // signals BUILD_BROKEN so Chain H fitness depression / boost continues to apply.
        com.walpro.autobuilder.core.merge.RolledBackEvidence evidence = new com.walpro.autobuilder.core.merge.RolledBackEvidence(
                "cycle-rb", List.of("Foo.java"), "no POM in this directory",
                com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind.MISSING_POM);
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-rb")
                .rolledBackThisCycle(true)
                .lastRollback(evidence)
                .build();
        CycleContextData base = CycleContextData.builder().cycleName("cycle-next").build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.hasSignal(BasicSignals.BUILD_BROKEN)).isTrue();
        Signal s = result.getSignal(BasicSignals.BUILD_BROKEN);
        assertThat(s.source()).isEqualTo("lastRollback");
    }

    @Test
    void t1dz_buildOutcomePrefersCompileErrorSourceOverRollback() {
        // When both signals are present, the source label should reflect the freshest evidence.
        com.walpro.autobuilder.core.build.BuildOutcome err = new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR, "[ERROR]", 100L, "mvn", "c-current");
        com.walpro.autobuilder.core.merge.RolledBackEvidence stale = new com.walpro.autobuilder.core.merge.RolledBackEvidence(
                "cycle-old", List.of("Old.java"), "no POM in this directory",
                com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind.MISSING_POM);
        CycleContextData previous = CycleContextData.builder()
                .cycleName("c-prev")
                .buildOutcome(err)
                .rolledBackThisCycle(true)
                .lastRollback(stale)
                .build();
        CycleContextData base = CycleContextData.builder().cycleName("c-next").build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.hasSignal(BasicSignals.BUILD_BROKEN)).isTrue();
        assertThat(result.getSignal(BasicSignals.BUILD_BROKEN).source()).isEqualTo("buildOutcome");
    }

    // ── T2-DT / T1-EW: lastAuthorErrors lifecycle ────────────────────────────

    @Test
    void t2dt_noMerge_carriesAuthorErrorsForward() {
        com.walpro.autobuilder.core.mutation.CompileAuthorErrors errs =
                new com.walpro.autobuilder.core.mutation.CompileAuthorErrors(
                        List.of(new com.walpro.autobuilder.core.mutation.BlockerExtractor.ClassifiedDiagnostic(
                                "A.java", 1, 1, "unreachable statement")));
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-prev").currentSubGoalId("sg-1")
                .lastAuthorErrors(errs).build();
        CycleContextData base = CycleContextData.builder()
                .cycleName("cycle-next").currentSubGoalId("sg-1").build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.lastAuthorErrors()).isSameAs(errs);
    }

    @Test
    void t1ew_subgoalChanged_stillCarriesAuthorErrorsForward() {
        // T1-EW regression-lock: the carrier describes compile state on disk, not goal state.
        // BUILD_FIX subgoal injection by T1-DM fires on the very rejection that wrote the
        // carrier — clearing on subgoal-change silently disabled T2-DT/T2-DV.
        com.walpro.autobuilder.core.mutation.CompileAuthorErrors errs =
                new com.walpro.autobuilder.core.mutation.CompileAuthorErrors(
                        List.of(new com.walpro.autobuilder.core.mutation.BlockerExtractor.ClassifiedDiagnostic(
                                "A.java", 1, 1, "unreachable statement")));
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-prev").currentSubGoalId("sg-original")
                .lastAuthorErrors(errs).build();
        CycleContextData base = CycleContextData.builder()
                .cycleName("cycle-next").currentSubGoalId("sg-build-fix-injected").build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.lastAuthorErrors()).isSameAs(errs);
    }

    @Test
    void t2dt_merged_clearsAuthorErrors() {
        com.walpro.autobuilder.core.mutation.CompileAuthorErrors errs =
                new com.walpro.autobuilder.core.mutation.CompileAuthorErrors(
                        List.of(new com.walpro.autobuilder.core.mutation.BlockerExtractor.ClassifiedDiagnostic(
                                "A.java", 1, 1, "unreachable statement")));
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-prev").currentSubGoalId("sg-1")
                .merged(true)
                .lastAuthorErrors(errs).build();
        CycleContextData base = CycleContextData.builder()
                .cycleName("cycle-next").currentSubGoalId("sg-1").build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.lastAuthorErrors()).isNull();
    }
}
