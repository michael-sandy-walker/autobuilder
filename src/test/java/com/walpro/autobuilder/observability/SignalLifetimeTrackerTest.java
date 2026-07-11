package com.walpro.autobuilder.observability;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.ChannelRequest;
import com.walpro.autobuilder.core.lifecycle.ParticipationWindow;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-BO / T4-BZ-5: the two-snapshot lifetime attribution over the carriers. A request's
 * lifecycle ends "consumed" (gone from the pre-tick open map — the addressed mechanism acted)
 * or "expired" (removed by the channel tick); a window's ends "closed" (revoked mid-cycle) or
 * "expired" (horizon passed / shrunk below threshold at tick). Same two-snapshot algorithm the
 * stored map's decayed/cleared pair used (project_t4bo_design_note.md); per-carrier state, one
 * shared cycle counter (the request observation increments it).
 */
class SignalLifetimeTrackerTest {

    private static final SignalType KIND = BasicSignals.REPLAN_REQUESTED;

    private SignalLifetimeTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new SignalLifetimeTracker();
    }

    private static Map<SignalType, ChannelRequest> open(SignalType kind) {
        return Map.of(kind, ChannelRequest.of(kind, 1.0, "test-src", 5));
    }

    private static Map<String, ParticipationWindow> window(String role) {
        return Map.of(role, ParticipationWindow.of(role, 1.0, 0.1, "test-src"));
    }

    // ---------------------------------------------------------------- requests

    @Test
    void firstCycle_freshRequestSurvivingTheTick_producesNoEnding_andIsTracked() {
        var pre = open(KIND);
        assertThat(tracker.observeRequests(pre, pre)).isEmpty();
    }

    @Test
    void requestAbsentFromPostTick_afterBeingPresentPreTick_isReportedAsExpired() {
        var pre = open(KIND);
        tracker.observeRequests(pre, pre); // cycle 1: issued, survives
        List<SignalLifetimeTracker.LifecycleEnd> ended = tracker.observeRequests(pre, Map.of());
        assertThat(ended).hasSize(1);
        SignalLifetimeTracker.LifecycleEnd end = ended.get(0);
        assertThat(end.signalType()).isEqualTo(KIND);
        assertThat(end.endReason()).isEqualTo("expired");
        assertThat(end.emitCycle()).isEqualTo(1);
        assertThat(end.endCycle()).isEqualTo(2);
        assertThat(end.emittedBySource()).isEqualTo("test-src");
    }

    @Test
    void requestAbsentFromPreTick_afterBeingTrackedOpen_isReportedAsConsumed() {
        var pre = open(KIND);
        tracker.observeRequests(pre, pre); // cycle 1: issued, survives
        // cycle 2: gone before the tick — the addressed mechanism consumed it
        List<SignalLifetimeTracker.LifecycleEnd> ended = tracker.observeRequests(Map.of(), Map.of());
        assertThat(ended).hasSize(1);
        assertThat(ended.get(0).endReason()).isEqualTo("consumed");
    }

    @Test
    void consumedAndExpired_areNeverBothReportedForTheSameEnding() {
        var pre = open(KIND);
        tracker.observeRequests(pre, pre);
        assertThat(tracker.observeRequests(Map.of(), Map.of())).hasSize(1);
    }

    @Test
    void continuingRequest_thatSurvivesManyCycles_neverProducesAnEnding_untilItDoes() {
        var pre = open(KIND);
        for (int i = 0; i < 7; i++) {
            assertThat(tracker.observeRequests(pre, pre)).isEmpty();
        }
        List<SignalLifetimeTracker.LifecycleEnd> ended = tracker.observeRequests(pre, Map.of());
        assertThat(ended).hasSize(1);
        assertThat(ended.get(0).emitCycle()).isEqualTo(1);
        assertThat(ended.get(0).endCycle()).isEqualTo(8);
    }

    @Test
    void reIssueAfterExpiry_startsAFreshEpisode_withANewEmitCycle() {
        var pre = open(KIND);
        tracker.observeRequests(pre, pre);          // cycle 1: issued
        tracker.observeRequests(pre, Map.of());     // cycle 2: expired
        var reIssued = open(KIND);
        assertThat(tracker.observeRequests(reIssued, reIssued)).isEmpty(); // cycle 3: fresh episode
        List<SignalLifetimeTracker.LifecycleEnd> ended = tracker.observeRequests(reIssued, Map.of());
        assertThat(ended).hasSize(1);
        assertThat(ended.get(0).emitCycle()).isEqualTo(3);
        assertThat(ended.get(0).endCycle()).isEqualTo(4);
    }

    @Test
    void multipleRequestKinds_areTrackedIndependently() {
        Map<SignalType, ChannelRequest> both = Map.of(
                KIND, ChannelRequest.of(KIND, 1.0, "a", 5),
                BasicSignals.MISSING_SYMBOL, ChannelRequest.of(BasicSignals.MISSING_SYMBOL, 1.0, "b", 4));
        tracker.observeRequests(both, both); // cycle 1: both open
        var onlyReplan = open(KIND);
        List<SignalLifetimeTracker.LifecycleEnd> ended = tracker.observeRequests(onlyReplan, onlyReplan);
        assertThat(ended).hasSize(1);
        assertThat(ended.get(0).signalType()).isEqualTo(BasicSignals.MISSING_SYMBOL);
        assertThat(ended.get(0).endReason()).isEqualTo("consumed");
    }

    // ---------------------------------------------------------------- windows

    @Test
    void windowAbsentFromPostTick_isReportedAsExpired_underItsRoleSignalName() {
        var pre = window("merge");
        tracker.observeRequests(Map.of(), Map.of()); // cycle 1
        tracker.observeWindows(pre, pre);            // window opens
        tracker.observeRequests(Map.of(), Map.of()); // cycle 2
        List<SignalLifetimeTracker.LifecycleEnd> ended = tracker.observeWindows(pre, Map.of());
        assertThat(ended).hasSize(1);
        assertThat(ended.get(0).signalType()).isEqualTo(BasicSignals.ROLE_MERGE);
        assertThat(ended.get(0).endReason()).isEqualTo("expired");
    }

    @Test
    void windowAbsentFromPreTick_isReportedAsClosed() {
        var pre = window("planner");
        tracker.observeRequests(Map.of(), Map.of());
        tracker.observeWindows(pre, pre);
        tracker.observeRequests(Map.of(), Map.of());
        List<SignalLifetimeTracker.LifecycleEnd> ended = tracker.observeWindows(Map.of(), Map.of());
        assertThat(ended).hasSize(1);
        assertThat(ended.get(0).signalType()).isEqualTo(BasicSignals.ROLE_PLANNER);
        assertThat(ended.get(0).endReason()).isEqualTo("closed");
    }
}
