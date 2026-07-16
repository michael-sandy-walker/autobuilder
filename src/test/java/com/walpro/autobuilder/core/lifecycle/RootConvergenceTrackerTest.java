package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.goal.GoalPlannerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.walpro.autobuilder.goal.GoalPlannerService.VerifyBeforePauseResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RootConvergenceTrackerTest {

    @Mock private GoalPlannerService goalPlannerService;

    private RootConvergenceTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new RootConvergenceTracker(goalPlannerService);
        tracker.setCycleLimit(3);
    }

    private static CycleContextData ctx(boolean merged) {
        return CycleContextData.builder().cycleName("cycle-1").merged(merged).build();
    }

    @Test
    void rootProgress_resetsStallCounter_andEmitsNoSignals() {
        when(goalPlannerService.countCompletedSubgoalsWithMerges()).thenReturn(0, 0, 1, 1, 1);

        // Two stalled cycles, then progress, then two more stalled — never reaches limit 3.
        for (int i = 0; i < 5; i++) {
            CycleContextData result = tracker.track(ctx(false));
            assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(result)).isFalse();
            assertThat(DerivedFacts.goalExhausted(result)).isFalse();
        }
    }

    @Test
    void stallAtLimit_emitsHaltSignals_andBehaviourEvent() {
        // 0 matches the tracker's initial baseline, so every cycle is a stall cycle.
        when(goalPlannerService.countCompletedSubgoalsWithMerges()).thenReturn(0);
        // T2-EH: verify-before-pause finds nothing to promote → the halt still fires.
        when(goalPlannerService.verifyAndPromoteBeforePause(any()))
                .thenReturn(new VerifyBeforePauseResult(false, 0));

        // Baseline resync consumes nothing — count never moves, so the counter climbs.
        CycleContextData result = null;
        for (int i = 0; i < 3; i++) {
            result = tracker.track(ctx(false));
        }

        assertThat(DerivedFacts.goalExhausted(result)).isTrue();
        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(result)).isTrue();
        // T5-BF follow-up: the tracker no longer emits system_paused itself — the reason on the
        // context is what the single AutobuilderService emit home consumes.
        assertThat(result.pausedReason()).isEqualTo("root-convergence-stall");
    }

    @Test
    void mergeAlone_doesNotResetTheCounter() {
        // The defining difference from the activity-based brakes: merged cycles still count as
        // stall when no new completed-with-merge subgoal appears.
        when(goalPlannerService.countCompletedSubgoalsWithMerges()).thenReturn(1);
        when(goalPlannerService.verifyAndPromoteBeforePause(any()))
                .thenReturn(new VerifyBeforePauseResult(false, 0));
        tracker.track(ctx(false)); // resync baseline happens via first-change semantics below

        CycleContextData result = null;
        for (int i = 0; i < 3; i++) {
            result = tracker.track(ctx(true));
        }

        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(result)).isTrue();
    }

    @Test
    void verifyCompletesRootAtLimit_doesNotHalt() {
        // T2-EH: at the stall limit, verify-before-pause completes the root → no halt, counter resets.
        when(goalPlannerService.countCompletedSubgoalsWithMerges()).thenReturn(2);
        when(goalPlannerService.verifyAndPromoteBeforePause(any()))
                .thenReturn(new VerifyBeforePauseResult(true, 1));
        tracker.track(ctx(false)); // baseline 0 -> 2, reset

        CycleContextData result = null;
        for (int i = 0; i < 3; i++) {
            result = tracker.track(ctx(false));
        }

        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(result)).isFalse();
        assertThat(DerivedFacts.goalExhausted(result)).isFalse();
    }

    @Test
    void verifyPromotesSubgoalsAtLimit_doesNotHalt_andResyncsBaseline() {
        // T2-EH: verify promotes subgoals (real progress the stall was hiding) without completing
        // the root — the brake resets and the loop continues toward formal completion.
        // Frozen at 1 through the limit cycle (so the stall reaches the limit); only the
        // post-verify resync call sees the promoted count (3).
        when(goalPlannerService.countCompletedSubgoalsWithMerges()).thenReturn(1, 1, 1, 1, 3);
        when(goalPlannerService.verifyAndPromoteBeforePause(any()))
                .thenReturn(new VerifyBeforePauseResult(false, 2));
        tracker.track(ctx(false)); // baseline 0 -> 1, reset

        CycleContextData result = null;
        for (int i = 0; i < 3; i++) {
            result = tracker.track(ctx(false));
        }

        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(result)).isFalse();
    }

    @Test
    void verifyPromotesButRootMetricStaysFlat_haltsInsteadOfSpinning() {
        // Non-halt guard (2026-07-07 run 20): at the stall limit, verify-before-pause re-promotes
        // the same leaf every window (promoted=1) but the completed-with-merge count never moves —
        // a structural gate keeps refusing closure, so "completed-with-merge 20 -> 20" repeats.
        // Before the guard, any promoted>0 reset the counter and the loop spun ~63 min; a flat
        // promotion must NOT count as progress, so the halt fires.
        when(goalPlannerService.countCompletedSubgoalsWithMerges()).thenReturn(20);
        when(goalPlannerService.verifyAndPromoteBeforePause(any()))
                .thenReturn(new VerifyBeforePauseResult(false, 1));
        tracker.track(ctx(false)); // baseline 0 -> 20, reset

        CycleContextData result = null;
        for (int i = 0; i < 3; i++) {
            result = tracker.track(ctx(false)); // count frozen at 20 through the limit cycle
        }

        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(result)).isTrue();
        assertThat(DerivedFacts.goalExhausted(result)).isTrue();
        assertThat(result.pausedReason()).isEqualTo("root-convergence-stall");
    }

    @Test
    void countDecrease_resyncsBaselineWithoutFiring() {
        when(goalPlannerService.countCompletedSubgoalsWithMerges()).thenReturn(5, 5, 0, 0);

        tracker.track(ctx(false)); // 5 → baseline moves 0→5, reset
        tracker.track(ctx(false)); // 5 → stall 1
        CycleContextData afterShrink = tracker.track(ctx(false)); // 0 → tree reset, resync
        CycleContextData next = tracker.track(ctx(false)); // 0 → stall 1 again

        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(afterShrink)).isFalse();
        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(next)).isFalse();
    }

    @Test
    void belowLimit_emitsNothing() {
        when(goalPlannerService.countCompletedSubgoalsWithMerges()).thenReturn(1);
        tracker.track(ctx(false));
        CycleContextData result = tracker.track(ctx(false));

        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(result)).isFalse();
        assertThat(DerivedFacts.goalExhausted(result)).isFalse();
    }

    @Test
    void closureEdgeInjection_countsAsRootProgress_andResetsTheStallCounter() {
        // Run 1 (2026-07-03) verbatim shape: completed-with-merge frozen through the endgame,
        // then the package gate injects the entry-point work item — the brake paused the system
        // 34 s later, before the fresh work got a single cycle. An injection is measured
        // distance-to-done materializing; it must reset the stall counter like a completion.
        when(goalPlannerService.countCompletedSubgoalsWithMerges()).thenReturn(29);
        when(goalPlannerService.countClosureEdgeInjections()).thenReturn(0, 0, 1, 1, 1);
        tracker.track(ctx(false)); // baseline 0 -> 29, reset

        tracker.track(ctx(false)); // stall 1
        tracker.track(ctx(false)); // injection arrives -> reset
        tracker.track(ctx(false)); // stall 1
        CycleContextData result = tracker.track(ctx(false)); // stall 2 — still below limit 3

        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(result)).isFalse();
    }

    @Test
    void injectionDuringVerifyBeforePause_preventsTheHalt() {
        // verifyAndPromoteBeforePause runs the completion edge, whose gates can inject fresh
        // work without promoting anything — madeProgress() is blind to that, so the tracker
        // re-checks the injection counter before halting.
        when(goalPlannerService.countCompletedSubgoalsWithMerges()).thenReturn(29);
        // 0 on every pre-limit check; 1 on the post-verify re-check inside the limit branch.
        when(goalPlannerService.countClosureEdgeInjections()).thenReturn(0, 0, 0, 0, 1);
        when(goalPlannerService.verifyAndPromoteBeforePause(any()))
                .thenReturn(new VerifyBeforePauseResult(false, 0));
        tracker.track(ctx(false)); // baseline resync

        CycleContextData result = null;
        for (int i = 0; i < 3; i++) {
            result = tracker.track(ctx(false)); // limit 3 reached on the last call
        }

        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(result)).isFalse();
        verify(goalPlannerService).verifyAndPromoteBeforePause(any());
    }

    @Test
    void sameCycleProgressAndInjection_doesNotLeaveAStaleDeltaThatMasksALaterStall() {
        // A completion and an injection landing in the same cycle resync BOTH baselines;
        // otherwise the unconsumed injection delta would false-reset a genuine stall later.
        when(goalPlannerService.countCompletedSubgoalsWithMerges()).thenReturn(1);
        when(goalPlannerService.countClosureEdgeInjections()).thenReturn(1);
        when(goalPlannerService.verifyAndPromoteBeforePause(any()))
                .thenReturn(new VerifyBeforePauseResult(false, 0));

        tracker.track(ctx(false)); // progress 0->1 AND injection 0->1 in one cycle — both resync

        CycleContextData result = null;
        for (int i = 0; i < 3; i++) {
            result = tracker.track(ctx(false)); // pure stall from here
        }

        assertThat(com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(result)).isTrue();
    }
}
