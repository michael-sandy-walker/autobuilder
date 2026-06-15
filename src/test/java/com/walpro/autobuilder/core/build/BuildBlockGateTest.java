package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.blocker.BlockerSubgoalInjector;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BuildBlockGateTest {

    @Mock GoalPlannerService goalPlannerService;
    @Mock BlockerSubgoalInjector blockerSubgoalInjector;
    @Mock RecurringDefectTracker recurringDefectTracker;

    BuildBlockGate gate;

    @BeforeEach
    void setUp() {
        gate = new BuildBlockGate(goalPlannerService, blockerSubgoalInjector);
    }

    @Test
    void isActive_falseWhenNoBuildOutcome() {
        CycleContextData ctx = CycleContextData.builder().cycleName("c").build();
        assertThat(gate.isActive(ctx)).isFalse();
    }

    @Test
    void isActive_falseWhenCompileOk() {
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .buildOutcome(new BuildOutcome(BuildResult.COMPILE_OK, "", 50L, "mvn", "c"))
                .build();
        assertThat(gate.isActive(ctx)).isFalse();
    }

    @Test
    void isActive_trueWhenCompileError() {
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .buildOutcome(new BuildOutcome(BuildResult.COMPILE_ERROR, "[ERROR]", 50L, "mvn", "c"))
                .build();
        assertThat(gate.isActive(ctx)).isTrue();
    }

    @Test
    void isEligible_analysisAlwaysAllowedUnderBuildBlock() {
        CycleContextData ctx = ctxWithCompileError();
        assertThat(gate.isEligible("analysis", ctx)).isTrue();
    }

    @Test
    void isEligible_plannerAllowedWhenNoBuildFixSubgoal() {
        when(blockerSubgoalInjector.hasActiveBuildFixSubgoal()).thenReturn(false);
        assertThat(gate.isEligible("planner", ctxWithCompileError())).isTrue();
    }

    @Test
    void isEligible_plannerBlockedWhenBuildFixSubgoalActive() {
        when(blockerSubgoalInjector.hasActiveBuildFixSubgoal()).thenReturn(true);
        assertThat(gate.isEligible("planner", ctxWithCompileError())).isFalse();
    }

    @Test
    void isEligible_evolutionAllowedOnlyWhenCurrentSubgoalIsBuildFix() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(buildFixGoal());
        CycleContextData ctx = CycleContextData.builder().cycleName("c").currentSubGoalId("sg-bf")
                .buildOutcome(new BuildOutcome(BuildResult.COMPILE_ERROR, "[ERROR]", 50L, "mvn", "c"))
                .build();
        assertThat(gate.isEligible("evolution", ctx)).isTrue();
        assertThat(gate.isEligible("merge", ctx)).isTrue();
    }

    @Test
    void isEligible_evolutionBlockedWhenCurrentSubgoalIsNotBuildFix() {
        GoalEntity feature = new GoalEntity();
        feature.setStatus(GoalStatus.ACTIVE);
        feature.setMetadata(new HashMap<>());
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(feature);
        CycleContextData ctx = CycleContextData.builder().cycleName("c").currentSubGoalId("sg-feat")
                .buildOutcome(new BuildOutcome(BuildResult.COMPILE_ERROR, "[ERROR]", 50L, "mvn", "c"))
                .build();
        assertThat(gate.isEligible("evolution", ctx)).isFalse();
        assertThat(gate.isEligible("merge", ctx)).isFalse();
    }

    // ── T1-EA: evaluation allowed under BUILD_FIX subgoal ──

    @Test
    void isEligible_T1EA_evaluationAllowedWhenCurrentSubgoalIsBuildFix() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(buildFixGoal());
        CycleContextData ctx = CycleContextData.builder().cycleName("c").currentSubGoalId("sg-bf")
                .buildOutcome(new BuildOutcome(BuildResult.COMPILE_ERROR, "[ERROR]", 50L, "mvn", "c"))
                .build();
        assertThat(gate.isEligible("evaluation", ctx)).isTrue();
    }

    @Test
    void isEligible_T1EA_evaluationBlockedWhenCurrentSubgoalIsNotBuildFix() {
        GoalEntity feature = new GoalEntity();
        feature.setStatus(GoalStatus.ACTIVE);
        feature.setMetadata(new HashMap<>());
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(feature);
        CycleContextData ctx = CycleContextData.builder().cycleName("c").currentSubGoalId("sg-feat")
                .buildOutcome(new BuildOutcome(BuildResult.COMPILE_ERROR, "[ERROR]", 50L, "mvn", "c"))
                .build();
        assertThat(gate.isEligible("evaluation", ctx)).isFalse();
    }

    @Test
    void isEligible_T1EA_evaluationBlockedWhenNoCurrentSubgoal() {
        // No current subgoal at all → evaluation blocked under build block.
        assertThat(gate.isEligible("evaluation", ctxWithCompileError())).isFalse();
    }

    @Test
    void ensureBuildFixSubgoalExists_injectsWhenAbsent() {
        when(blockerSubgoalInjector.hasActiveBuildFixSubgoal()).thenReturn(false);
        gate.ensureBuildFixSubgoalExists(ctxWithCompileError());
        verify(blockerSubgoalInjector).injectBuildFixSubgoalIfAbsent(any(), anyList(), anyInt(), any());
    }

    // ── T2-EF: recurring-defect redirection, decoupled from the post-merge build-broken gate ──

    @Test
    void ensureRecurringDefectRedirection_injectsWhenDefectDominatesAcrossSubgoals() {
        // The 2026-06-14 repro: Member missing, share 1.0, spanning 7 subgoals, streak reset to 0.
        // No post-merge build error needed — this fires off the residual-channel dominance.
        gate.setRecurringDefectTracker(recurringDefectTracker);
        when(blockerSubgoalInjector.hasActiveBuildFixSubgoal()).thenReturn(false);
        when(recurringDefectTracker.dominantDefect()).thenReturn(java.util.Optional.of(
                new RecurringDefectTracker.DominantDefect("CANNOT_FIND_SYMBOL|Member", 1.0, 7, true)));

        CycleContextData ctx = CycleContextData.builder().cycleName("c").build(); // streak 0, build OK

        gate.ensureRecurringDefectRedirection(ctx);

        verify(blockerSubgoalInjector).injectRecurringDefectRedirection("CANNOT_FIND_SYMBOL", "Member", 7, true);
    }

    @Test
    void ensureRecurringDefectRedirection_noOpWhenNoDominantDefect() {
        gate.setRecurringDefectTracker(recurringDefectTracker);
        when(blockerSubgoalInjector.hasActiveBuildFixSubgoal()).thenReturn(false);
        when(recurringDefectTracker.dominantDefect()).thenReturn(java.util.Optional.empty());

        gate.ensureRecurringDefectRedirection(CycleContextData.builder().cycleName("c").build());

        verify(blockerSubgoalInjector, never()).injectRecurringDefectRedirection(any(), any(), anyInt(), anyBoolean());
    }

    @Test
    void ensureRecurringDefectRedirection_noOpWhenPerSubgoalStreakAlreadyEscalating() {
        // streak >= 2 means the T2-DD per-subgoal divergence already owns this — don't duplicate.
        gate.setRecurringDefectTracker(recurringDefectTracker);
        CycleContextData ctx = CycleContextData.builder().cycleName("c").sameFailureStreak(2).build();

        gate.ensureRecurringDefectRedirection(ctx);

        verify(blockerSubgoalInjector, never()).injectRecurringDefectRedirection(any(), any(), anyInt(), anyBoolean());
        verifyNoInteractions(recurringDefectTracker);
    }

    @Test
    void ensureRecurringDefectRedirection_noOpWhenBuildFixAlreadyInFlight() {
        // A build-fix subgoal is already active — fire once per dominant defect, not every cycle.
        gate.setRecurringDefectTracker(recurringDefectTracker);
        when(blockerSubgoalInjector.hasActiveBuildFixSubgoal()).thenReturn(true);

        gate.ensureRecurringDefectRedirection(CycleContextData.builder().cycleName("c").build());

        verify(blockerSubgoalInjector, never()).injectRecurringDefectRedirection(any(), any(), anyInt(), anyBoolean());
    }

    @Test
    void ensureBuildFixSubgoalExists_noOpWhenPresent() {
        when(blockerSubgoalInjector.hasActiveBuildFixSubgoal()).thenReturn(true);
        gate.ensureBuildFixSubgoalExists(ctxWithCompileError());
        verify(blockerSubgoalInjector, never()).injectBuildFixSubgoalIfAbsent(any(), anyList(), anyInt(), any());
    }

    @Test
    void ensureBuildFixSubgoalExists_noOpWhenBuildOk() {
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .buildOutcome(new BuildOutcome(BuildResult.COMPILE_OK, "", 50L, "mvn", "c"))
                .build();
        gate.ensureBuildFixSubgoalExists(ctx);
        verify(blockerSubgoalInjector, never()).injectBuildFixSubgoalIfAbsent(any(), anyList(), anyInt(), any());
    }

    // ── T1-DZ: rollback evidence triggers gate even when buildOutcome is null ──

    @Test
    void isActive_trueWhenLastRollbackPresent_evenIfBuildOutcomeNull() {
        // T1-DW restores buildOutcome to the previous OK value on rollback. T1-DZ: the
        // lastRollback evidence is the cross-cycle signal that the codebase is broken.
        com.walpro.autobuilder.core.merge.RolledBackEvidence evidence = new com.walpro.autobuilder.core.merge.RolledBackEvidence(
                "cycle-rb", java.util.List.of("Foo.java"),
                "no POM in this directory",
                BuildErrorClassifier.Kind.MISSING_POM);
        CycleContextData ctx = CycleContextData.builder().cycleName("c-next")
                .buildOutcome(null)
                .lastRollback(evidence)
                .build();
        assertThat(gate.isActive(ctx)).isTrue();
    }

    @Test
    void isActive_trueWhenLastRollbackPresent_evenIfBuildOutcomeOk() {
        com.walpro.autobuilder.core.merge.RolledBackEvidence evidence = new com.walpro.autobuilder.core.merge.RolledBackEvidence(
                "cycle-rb", java.util.List.of("Foo.java"),
                "no POM in this directory",
                BuildErrorClassifier.Kind.MISSING_POM);
        CycleContextData ctx = CycleContextData.builder().cycleName("c-next")
                .buildOutcome(new BuildOutcome(BuildResult.COMPILE_OK, "", 50L, "mvn", "c-prev"))
                .lastRollback(evidence)
                .build();
        assertThat(gate.isActive(ctx)).isTrue();
    }

    @Test
    void ensureBuildFixSubgoalExists_T1DZ_synthesizesOutcomeFromRollbackEvidence() {
        // No current buildOutcome (rollback restored it) but lastRollback present.
        // The injector must still receive a BuildOutcome so the existing classifier flow
        // identifies the kind and produces criteria.
        com.walpro.autobuilder.core.merge.RolledBackEvidence evidence = new com.walpro.autobuilder.core.merge.RolledBackEvidence(
                "cycle-rb", java.util.List.of("Foo.java"),
                "no POM in this directory",
                BuildErrorClassifier.Kind.MISSING_POM);
        CycleContextData ctx = CycleContextData.builder().cycleName("c-next")
                .buildOutcome(null)
                .lastRollback(evidence)
                .build();
        when(blockerSubgoalInjector.hasActiveBuildFixSubgoal()).thenReturn(false);

        gate.ensureBuildFixSubgoalExists(ctx);

        org.mockito.ArgumentCaptor<BuildOutcome> captor = org.mockito.ArgumentCaptor.forClass(BuildOutcome.class);
        verify(blockerSubgoalInjector).injectBuildFixSubgoalIfAbsent(captor.capture(), anyList(), anyInt(), any());
        BuildOutcome synthetic = captor.getValue();
        assertThat(synthetic).isNotNull();
        assertThat(synthetic.result()).isEqualTo(BuildResult.COMPILE_ERROR);
        assertThat(synthetic.stderrTail()).contains("no POM in this directory");
        assertThat(synthetic.cycleId()).isEqualTo("cycle-rb");
        // Confirm the classifier in the injector flow would correctly identify MISSING_POM:
        BuildErrorClassifier.Classification reclassified = BuildErrorClassifier.classify(synthetic.stderrTail());
        assertThat(reclassified.kind()).isEqualTo(BuildErrorClassifier.Kind.MISSING_POM);
    }

    @Test
    void ensureBuildFixSubgoalExists_T2CY_threadsRestoredPathsIntoInjector() {
        // T2-CY: when synthesising a BuildOutcome from rollback evidence, the gate must
        // pass the restoredPaths to the injector so a non-build-config file can be named
        // in the BUILD_FIX subgoal description.
        com.walpro.autobuilder.core.merge.RolledBackEvidence evidence = new com.walpro.autobuilder.core.merge.RolledBackEvidence(
                "cycle-rb",
                java.util.List.of("src/main/java/com/example/Foo.java"),
                "no POM in this directory",
                BuildErrorClassifier.Kind.MISSING_POM);
        CycleContextData ctx = CycleContextData.builder().cycleName("c-next")
                .buildOutcome(null)
                .lastRollback(evidence)
                .build();
        when(blockerSubgoalInjector.hasActiveBuildFixSubgoal()).thenReturn(false);

        gate.ensureBuildFixSubgoalExists(ctx);

        org.mockito.ArgumentCaptor<java.util.List> pathsCaptor = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(blockerSubgoalInjector).injectBuildFixSubgoalIfAbsent(any(BuildOutcome.class), pathsCaptor.capture(), anyInt(), any());
        assertThat(pathsCaptor.getValue()).containsExactly("src/main/java/com/example/Foo.java");
    }

    @Test
    void ensureBuildFixSubgoalExists_T1DZ_prefersCurrentBuildOutcomeOverRollback() {
        // When BOTH a fresh COMPILE_ERROR and rollback evidence are present, the current
        // build outcome wins so the freshest stderr drives the BUILD_FIX subgoal.
        com.walpro.autobuilder.core.merge.RolledBackEvidence stale = new com.walpro.autobuilder.core.merge.RolledBackEvidence(
                "cycle-old", java.util.List.of("Old.java"),
                "no POM in this directory",
                BuildErrorClassifier.Kind.MISSING_POM);
        BuildOutcome fresh = new BuildOutcome(BuildResult.COMPILE_ERROR,
                "[ERROR] symbol: class FreshBroken", 100L, "mvn compile", "cycle-current");
        CycleContextData ctx = CycleContextData.builder().cycleName("c-current")
                .buildOutcome(fresh)
                .lastRollback(stale)
                .build();
        when(blockerSubgoalInjector.hasActiveBuildFixSubgoal()).thenReturn(false);

        gate.ensureBuildFixSubgoalExists(ctx);

        verify(blockerSubgoalInjector).injectBuildFixSubgoalIfAbsent(eq(fresh), anyList(), anyInt(), any());
    }

    private static CycleContextData ctxWithCompileError() {
        return CycleContextData.builder().cycleName("c")
                .buildOutcome(new BuildOutcome(BuildResult.COMPILE_ERROR, "[ERROR]", 50L, "mvn", "c"))
                .build();
    }

    private static GoalEntity buildFixGoal() {
        GoalEntity g = new GoalEntity();
        g.setStatus(GoalStatus.ACTIVE);
        Map<String, Object> meta = new HashMap<>();
        meta.put(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX);
        g.setMetadata(meta);
        return g;
    }
}
