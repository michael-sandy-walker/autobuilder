package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RootConvergenceTrackerTest {

    @Mock private GoalPlannerService goalPlannerService;
    @Mock private BehaviourLogger behaviourLogger;

    private RootConvergenceTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new RootConvergenceTracker(goalPlannerService);
        tracker.setBehaviourLogger(behaviourLogger);
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
            assertThat(result.hasSignal(BasicSignals.SYSTEM_PAUSED)).isFalse();
            assertThat(result.hasSignal(BasicSignals.GOAL_EXHAUSTED)).isFalse();
        }
    }

    @Test
    void stallAtLimit_emitsHaltSignals_andBehaviourEvent() {
        // 0 matches the tracker's initial baseline, so every cycle is a stall cycle.
        when(goalPlannerService.countCompletedSubgoalsWithMerges()).thenReturn(0);

        // Baseline resync consumes nothing — count never moves, so the counter climbs.
        CycleContextData result = null;
        for (int i = 0; i < 3; i++) {
            result = tracker.track(ctx(false));
        }

        assertThat(result.hasSignal(BasicSignals.GOAL_EXHAUSTED)).isTrue();
        assertThat(result.hasSignal(BasicSignals.SYSTEM_PAUSED)).isTrue();
        verify(behaviourLogger).systemPaused(eq("cycle-1"), eq("root-convergence-stall"), anyInt());
    }

    @Test
    void mergeAlone_doesNotResetTheCounter() {
        // The defining difference from the activity-based brakes: merged cycles still count as
        // stall when no new completed-with-merge subgoal appears.
        when(goalPlannerService.countCompletedSubgoalsWithMerges()).thenReturn(1);
        tracker.track(ctx(false)); // resync baseline happens via first-change semantics below

        CycleContextData result = null;
        for (int i = 0; i < 3; i++) {
            result = tracker.track(ctx(true));
        }

        assertThat(result.hasSignal(BasicSignals.SYSTEM_PAUSED)).isTrue();
    }

    @Test
    void countDecrease_resyncsBaselineWithoutFiring() {
        when(goalPlannerService.countCompletedSubgoalsWithMerges()).thenReturn(5, 5, 0, 0);

        tracker.track(ctx(false)); // 5 → baseline moves 0→5, reset
        tracker.track(ctx(false)); // 5 → stall 1
        CycleContextData afterShrink = tracker.track(ctx(false)); // 0 → tree reset, resync
        CycleContextData next = tracker.track(ctx(false)); // 0 → stall 1 again

        assertThat(afterShrink.hasSignal(BasicSignals.SYSTEM_PAUSED)).isFalse();
        assertThat(next.hasSignal(BasicSignals.SYSTEM_PAUSED)).isFalse();
    }

    @Test
    void belowLimit_emitsNothing() {
        when(goalPlannerService.countCompletedSubgoalsWithMerges()).thenReturn(1);
        tracker.track(ctx(false));
        CycleContextData result = tracker.track(ctx(false));

        assertThat(result.hasSignal(BasicSignals.SYSTEM_PAUSED)).isFalse();
        assertThat(result.hasSignal(BasicSignals.GOAL_EXHAUSTED)).isFalse();
    }
}
