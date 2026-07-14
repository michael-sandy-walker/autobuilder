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
        com.walpro.autobuilder.core.merge.RolledBackReport rb =
                new com.walpro.autobuilder.core.merge.RolledBackReport(
                        "cycle-1", List.of("Foo.java"), "cannot find symbol",
                        com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL);
        CycleContextData first = CycleContextData.builder()
                .cycleName("cycle-1")
                .rolledBackThisCycle(true)
                .lastRollback(rb)
                .build();

        CycleContextData secondBase = CycleContextData.builder().cycleName("cycle-2").build();
        CycleContextData second = tracker.enrichLineage(secondBase, first);
        assertThat(second.sameFailureStreak()).isEqualTo(1);
        assertThat(second.sameFailureStreakKey()).isEqualTo("CANNOT_FIND_SYMBOL|Foo.java");
        assertThat(DerivedFacts.buildFixLocusStuck(second))
                .as("streak=1 must NOT fire BUILD_FIX_LOCUS_STUCK")
                .isFalse();

        // Cycle 2 also rolls back with same key. enrichLineage at cycle-3 sees streak=2.
        CycleContextData secondWithRb = second
                .withRolledBackThisCycle(true)
                .withLastRollback(rb);
        CycleContextData thirdBase = CycleContextData.builder().cycleName("cycle-3").build();
        CycleContextData third = tracker.enrichLineage(thirdBase, secondWithRb);
        assertThat(third.sameFailureStreak()).isEqualTo(2);
        assertThat(DerivedFacts.buildFixLocusStuck(third))
                .as("streak=2 must fire BUILD_FIX_LOCUS_STUCK")
                .isTrue();
    }

    @Test
    void enrichLineage_T2DD_differentKindResetsStreak() {
        com.walpro.autobuilder.core.merge.RolledBackReport rb1 =
                new com.walpro.autobuilder.core.merge.RolledBackReport(
                        "cycle-1", List.of("Foo.java"), "cannot find symbol",
                        com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL);
        CycleContextData prev = CycleContextData.builder()
                .cycleName("cycle-2")
                .rolledBackThisCycle(true)
                .lastRollback(rb1)
                .sameFailureStreakKey("CANNOT_FIND_SYMBOL|Foo.java")
                .sameFailureStreak(1)
                .build();

        // New rollback, different kind → streak resets to 1.
        com.walpro.autobuilder.core.merge.RolledBackReport rb2 =
                new com.walpro.autobuilder.core.merge.RolledBackReport(
                        "cycle-2", List.of("Foo.java"), "package x does not exist",
                        com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind.PACKAGE_DOES_NOT_EXIST);
        CycleContextData prevWithRb2 = prev.withLastRollback(rb2);

        CycleContextData nextBase = CycleContextData.builder().cycleName("cycle-3").build();
        CycleContextData next = tracker.enrichLineage(nextBase, prevWithRb2);
        assertThat(next.sameFailureStreak()).isEqualTo(1);
        assertThat(DerivedFacts.buildFixLocusStuck(next)).isFalse();
    }

    @Test
    void enrichLineage_T2DD_successfulMergeResetsStreak() {
        CycleContextData prev = CycleContextData.builder()
                .cycleName("cycle-merged")
                .merged(true)
                .rolledBackThisCycle(false)
                .sameFailureStreakKey("CANNOT_FIND_SYMBOL|Foo.java")
                .sameFailureStreak(2)
                .build();

        CycleContextData nextBase = CycleContextData.builder().cycleName("cycle-next").build();
        CycleContextData next = tracker.enrichLineage(nextBase, prev);
        assertThat(next.sameFailureStreak()).isZero();
        assertThat(next.sameFailureStreakKey()).isNull();
        assertThat(DerivedFacts.buildFixLocusStuck(next)).isFalse();
    }

    // ── T4-BX: authored rejection-reason carry-forward ──────────────────────

    @Test
    void enrichLineage_T4BX_carriesRejectionReasonsForwardWhenNotMerged() {
        List<String> reasons = List.of("The rule is not implemented.",
                "TestCoherenceHeuristic: implementation is absent.");
        CycleContextData prev = CycleContextData.builder()
                .cycleName("cycle-1")
                .merged(false)
                .rejectionFeedback(reasons)
                .build();

        CycleContextData next = tracker.enrichLineage(
                CycleContextData.builder().cycleName("cycle-2").build(), prev);

        assertThat(next.rejectionFeedback()).isEqualTo(reasons);
    }

    @Test
    void enrichLineage_T4BX_clearsRejectionReasonsOnMerge() {
        CycleContextData prev = CycleContextData.builder()
                .cycleName("cycle-merged")
                .merged(true)
                .rejectionFeedback(List.of("some reason"))
                .build();

        CycleContextData next = tracker.enrichLineage(
                CycleContextData.builder().cycleName("cycle-next").build(), prev);

        assertThat(next.rejectionFeedback()).isNull();
    }

    // ── T2-DD extension: pre-merge probe-rejection channel (the live one since T1-EC) ──

    @Test
    void enrichLineage_probeRejection_sameMissingSymbolAcrossFiles_incrementsStreakAndEmitsLocusStuck() {
        String stderr1 = "[ERROR] /x/src/main/java/com/example/demo/error/ErrorHandler.java:[12,8] cannot find symbol\n"
                + "  symbol:   class ErrorResponse\n  location: class com.example.demo.error.ErrorHandler";
        CycleContextData first = CycleContextData.builder()
                .cycleName("cycle-1")
                .lastProbeOutcome(new com.walpro.autobuilder.core.build.BuildOutcome(
                        com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR, stderr1, 100, "mvn compile", "cycle-1"))
                .build();

        CycleContextData second = tracker.enrichLineage(
                CycleContextData.builder().cycleName("cycle-2").build(), first);
        assertThat(second.sameFailureStreak()).isEqualTo(1);
        assertThat(second.sameFailureStreakKey()).isEqualTo("CANNOT_FIND_SYMBOL|ErrorResponse");
        assertThat(DerivedFacts.buildFixLocusStuck(second)).isFalse();

        // Next candidate probe-fails on the same missing symbol from a DIFFERENT file —
        // one logical defect, so the streak continues.
        String stderr2 = "[ERROR] /x/src/main/java/com/example/demo/BooksController.java:[3,1] cannot find symbol\n"
                + "  symbol:   class ErrorResponse\n  location: package com.example.demo.error";
        CycleContextData secondWithProbe = second.withLastProbeOutcome(
                new com.walpro.autobuilder.core.build.BuildOutcome(
                        com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR, stderr2, 100, "mvn compile", "cycle-2"));
        CycleContextData third = tracker.enrichLineage(
                CycleContextData.builder().cycleName("cycle-3").build(), secondWithProbe);
        assertThat(third.sameFailureStreak()).isEqualTo(2);
        assertThat(third.sameFailureStreakKey()).isEqualTo("CANNOT_FIND_SYMBOL|ErrorResponse");
        assertThat(DerivedFacts.buildFixLocusStuck(third)).isTrue();
    }

    @Test
    void enrichLineage_probeChannel_noBuildObservationCarriesStreak() {
        // A planning/analysis cycle runs no probe — the streak counts failed attempts,
        // not wall-clock cycles, so it carries unchanged.
        CycleContextData prev = CycleContextData.builder()
                .cycleName("cycle-5")
                .sameFailureStreakKey("CANNOT_FIND_SYMBOL|ErrorResponse")
                .sameFailureStreak(2)
                .build();

        CycleContextData next = tracker.enrichLineage(
                CycleContextData.builder().cycleName("cycle-6").build(), prev);
        assertThat(next.sameFailureStreak()).isEqualTo(2);
        assertThat(next.sameFailureStreakKey()).isEqualTo("CANNOT_FIND_SYMBOL|ErrorResponse");
        assertThat(DerivedFacts.buildFixLocusStuck(next)).isTrue();
    }

    @Test
    void enrichLineage_probeChannel_greenProbeResetsStreak() {
        CycleContextData prev = CycleContextData.builder()
                .cycleName("cycle-5")
                .lastProbeOutcome(new com.walpro.autobuilder.core.build.BuildOutcome(
                        com.walpro.autobuilder.core.build.BuildResult.COMPILE_OK, "", 50, "mvn compile", "cycle-5"))
                .sameFailureStreakKey("CANNOT_FIND_SYMBOL|ErrorResponse")
                .sameFailureStreak(2)
                .build();

        CycleContextData next = tracker.enrichLineage(
                CycleContextData.builder().cycleName("cycle-6").build(), prev);
        assertThat(next.sameFailureStreak()).isZero();
        assertThat(next.sameFailureStreakKey()).isNull();
        assertThat(DerivedFacts.buildFixLocusStuck(next)).isFalse();
    }

    @Test
    void enrichLineage_probeChannel_subgoalChangeResetsStreak() {
        CycleContextData prev = CycleContextData.builder()
                .cycleName("cycle-5")
                .currentSubGoalId("sg-1")
                .sameFailureStreakKey("CANNOT_FIND_SYMBOL|ErrorResponse")
                .sameFailureStreak(3)
                .build();

        CycleContextData next = tracker.enrichLineage(
                CycleContextData.builder().cycleName("cycle-6").currentSubGoalId("sg-2").build(), prev);
        assertThat(next.sameFailureStreak()).isZero();
        assertThat(next.sameFailureStreakKey()).isNull();
        assertThat(DerivedFacts.buildFixLocusStuck(next)).isFalse();
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

        assertThat(DerivedFacts.buildBroken(result)).isTrue();
        SignalReadout.Entry entry = readoutEntry(result, BasicSignals.BUILD_BROKEN);
        assertThat(entry.strength()).isEqualTo(1.0);
        assertThat(entry.decayPerCycle()).isEqualTo(0.0);
    }

    private static SignalReadout.Entry readoutEntry(CycleContextData ctx,
            com.walpro.autobuilder.core.lifecycle.SignalType type) {
        return SignalReadout.of(ctx).entries().stream()
                .filter(e -> e.name().equals(type.name()))
                .findFirst().orElseThrow();
    }

    @Test
    void t1du_compileOk_clearsBuildBrokenSignal() {
        com.walpro.autobuilder.core.build.BuildOutcome ok = new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_OK, "", 100L, "mvn", "c-prev");
        CycleContextData previous = CycleContextData.builder().cycleName("c-prev").buildOutcome(ok).build();
        CycleContextData base = CycleContextData.builder().cycleName("c-next").build()
                .withBuildOutcome(new com.walpro.autobuilder.core.build.BuildOutcome(com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR, "fixture", 0L, "mvn", null));

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(DerivedFacts.buildBroken(result))
                .as("COMPILE_OK must clear stale BUILD_BROKEN signal")
                .isFalse();
    }

    // ── T1-DX: rollback observation carry-forward ──

    @Test
    void t1dx_carriesLastRollbackForwardOneCycle() {
        com.walpro.autobuilder.core.merge.RolledBackReport observation = new com.walpro.autobuilder.core.merge.RolledBackReport(
                "cycle-rb", List.of("a.java"), "cannot find symbol: Foo",
                com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL);
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-rb")
                .rolledBackThisCycle(true)
                .lastRollback(observation)
                .build();
        CycleContextData base = CycleContextData.builder().cycleName("cycle-next").build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.lastRollback()).isSameAs(observation);
        // rolledBackThisCycle is per-cycle state — must NOT survive carry-forward.
        assertThat(result.rolledBackThisCycle()).isFalse();
    }

    @Test
    void t1dx_dropsLastRollbackAfterOneCycleTtl() {
        // Cycle N rolled back; cycle N+1 carried the observation; cycle N+2 must NOT see it.
        com.walpro.autobuilder.core.merge.RolledBackReport observation = new com.walpro.autobuilder.core.merge.RolledBackReport(
                "cycle-rb", List.of("a.java"), "cannot find symbol: Foo",
                com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL);
        // previous (cycle N+1) carried the observation but did NOT roll back itself.
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-after-rb")
                .rolledBackThisCycle(false)
                .lastRollback(observation)
                .build();
        CycleContextData base = CycleContextData.builder().cycleName("cycle-N+2").build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.lastRollback())
                .as("rollback observation has single-cycle TTL — must not propagate to N+2")
                .isNull();
    }

    // ── T4-BA: package-fragmentation observation carry-forward ──

    @Test
    void t4ba_carriesPackageFragmentationForwardOneCycle() {
        com.walpro.autobuilder.core.merge.PackageFragmentationReport observation =
                new com.walpro.autobuilder.core.merge.PackageFragmentationReport("cycle-frag",
                        List.of(new com.walpro.autobuilder.core.merge.PackageFragmentationReport.Conflict(
                                "Foo", "a/Foo.java", "a/b/Foo.java")));
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-frag")
                .packageFragmentationThisCycle(true)
                .packageFragmentation(observation)
                .build();
        CycleContextData base = CycleContextData.builder().cycleName("cycle-next").build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.packageFragmentation()).isSameAs(observation);
        // packageFragmentationThisCycle is per-cycle state — must NOT survive carry-forward.
        assertThat(result.packageFragmentationThisCycle()).isFalse();
    }

    @Test
    void t4ba_dropsPackageFragmentationAfterOneCycleTtl() {
        // Cycle N rejected a fragmenting mutation; cycle N+1 carried it; cycle N+2 must not.
        com.walpro.autobuilder.core.merge.PackageFragmentationReport observation =
                new com.walpro.autobuilder.core.merge.PackageFragmentationReport("cycle-frag",
                        List.of(new com.walpro.autobuilder.core.merge.PackageFragmentationReport.Conflict(
                                "Foo", "a/Foo.java", "a/b/Foo.java")));
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-after-frag")
                .packageFragmentationThisCycle(false)
                .packageFragmentation(observation)
                .build();
        CycleContextData base = CycleContextData.builder().cycleName("cycle-N+2").build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(result.packageFragmentation())
                .as("fragmentation observation has single-cycle TTL — must not propagate to N+2")
                .isNull();
    }

    @Test
    void t1du_noBuildOutcome_noBuildBrokenSignal() {
        CycleContextData previous = CycleContextData.builder().cycleName("c-prev").build();
        CycleContextData base = CycleContextData.builder().cycleName("c-next").build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(DerivedFacts.buildBroken(result)).isFalse();
    }

    // ── T1-DZ: BUILD_BROKEN also fires from rollback observation ──

    @Test
    void t1dz_lastRollbackPresent_emitsBuildBrokenSignal() {
        // T1-DW restores buildOutcome=null on rollback. T1-DZ: lastRollback observation still
        // signals BUILD_BROKEN so Chain H fitness depression / boost continues to apply.
        com.walpro.autobuilder.core.merge.RolledBackReport observation = new com.walpro.autobuilder.core.merge.RolledBackReport(
                "cycle-rb", List.of("Foo.java"), "no POM in this directory",
                com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind.MISSING_POM);
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-rb")
                .rolledBackThisCycle(true)
                .lastRollback(observation)
                .build();
        CycleContextData base = CycleContextData.builder().cycleName("cycle-next").build();

        CycleContextData result = tracker.enrichLineage(base, previous);

        assertThat(DerivedFacts.buildBroken(result)).isTrue();
        assertThat(readoutEntry(result, BasicSignals.BUILD_BROKEN).source()).isEqualTo("lastRollback");
    }

    @Test
    void t1dz_buildOutcomePrefersCompileErrorSourceOverRollback() {
        // When both signals are present, the source label should reflect the freshest observation.
        com.walpro.autobuilder.core.build.BuildOutcome err = new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR, "[ERROR]", 100L, "mvn", "c-current");
        com.walpro.autobuilder.core.merge.RolledBackReport stale = new com.walpro.autobuilder.core.merge.RolledBackReport(
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

        assertThat(DerivedFacts.buildBroken(result)).isTrue();
        assertThat(readoutEntry(result, BasicSignals.BUILD_BROKEN).source()).isEqualTo("buildOutcome");
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

    // ── T4-BN-3: TEST_REPAIR_FOCUS gating ────────────────────────────────────

    private static com.walpro.autobuilder.core.build.BuildOutcome testOnlyOutcome() {
        return new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR,
                "/proj/output/src/test/java/app/FooTest.java:[1,1] cannot find symbol",
                10L, "mvn test-compile", "c");
    }

    private static com.walpro.autobuilder.core.build.BuildOutcome mainBrokenOutcome() {
        return new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR,
                "/proj/output/src/main/java/app/Foo.java:[1,1] cannot find symbol",
                10L, "mvn test-compile", "c");
    }

    @Test
    void enrichLineage_T4BN3_specRequiresTestsAndTestOnlyFailure_derivesTestRepairFocus() {
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-1")
                .lastProbeOutcome(testOnlyOutcome())
                .build();

        CycleContextData next = tracker.enrichLineage(
                CycleContextData.builder().cycleName("cycle-2")
                        .currentGoalDescription("Provide a REST API with tests for every endpoint").build(),
                previous);

        assertThat(DerivedFacts.testRepairFocus(next)).isTrue();
    }

    @Test
    void enrichLineage_T4BN3_specDoesNotRequireTests_factStaysFalseEvenWithTestOnlyFailure() {
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-1")
                .lastProbeOutcome(testOnlyOutcome())
                .build();

        CycleContextData next = tracker.enrichLineage(
                CycleContextData.builder().cycleName("cycle-2")
                        .currentGoalDescription("Provide a REST API for managing books").build(),
                previous);

        assertThat(DerivedFacts.testRepairFocus(next)).isFalse();
    }

    @Test
    void enrichLineage_T4BN3_nullRootGoalDescription_factStaysFalse() {
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-1")
                .lastProbeOutcome(testOnlyOutcome())
                .build();

        CycleContextData next = tracker.enrichLineage(
                CycleContextData.builder().cycleName("cycle-2").build(), previous);

        assertThat(DerivedFacts.testRepairFocus(next)).isFalse();
    }

    @Test
    void enrichLineage_T4BN3_mainBrokenFailure_doesNotEmitTestRepairFocus() {
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-1")
                .buildOutcome(mainBrokenOutcome())
                .build();

        CycleContextData next = tracker.enrichLineage(
                CycleContextData.builder().cycleName("cycle-2")
                        .currentGoalDescription("Provide a REST API with tests for every endpoint").build(),
                previous);

        assertThat(DerivedFacts.testRepairFocus(next)).isFalse();
        assertThat(DerivedFacts.buildBroken(next)).isTrue();
    }

    @Test
    void enrichLineage_T4BN3_compileOkClearsTestRepairFocus() {
        CycleContextData previous = CycleContextData.builder()
                .cycleName("cycle-1")
                .buildOutcome(new com.walpro.autobuilder.core.build.BuildOutcome(
                        com.walpro.autobuilder.core.build.BuildResult.COMPILE_OK, "", 1L, "c", "c"))
                .lastProbeOutcome(null)
                .build();

        CycleContextData next = tracker.enrichLineage(
                CycleContextData.builder().cycleName("cycle-2")
                        .currentGoalDescription("Provide a REST API with tests for every endpoint").build(),
                previous);

        assertThat(DerivedFacts.testRepairFocus(next)).isFalse();
    }
}
