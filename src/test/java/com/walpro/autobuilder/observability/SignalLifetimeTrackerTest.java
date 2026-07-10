package com.walpro.autobuilder.observability;

import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-BO: the two-snapshot decayed-vs-cleared algorithm. See project_t4bo_design_note.md for the
 * reasoning; these tests pin the exact scenarios that reasoning depends on.
 */
class SignalLifetimeTrackerTest {

    private static final SignalType A = SignalType.of("SIGNAL_A");
    private static final SignalType B = SignalType.of("SIGNAL_B");

    private SignalLifetimeTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new SignalLifetimeTracker();
    }

    private static Signal sig(SignalType type, String source) {
        return Signal.of(type, 1.0, 0.1, source);
    }

    @Test
    void firstCycle_freshSignalSurvivingTheTick_producesNoDeath_andIsTracked() {
        Map<SignalType, Signal> pre = Map.of(A, sig(A, "agent-x"));
        List<SignalLifetimeTracker.Death> deaths = tracker.observe(pre, pre); // survives its own tick

        assertThat(deaths).isEmpty();
    }

    @Test
    void signalAbsentFromPostTick_afterBeingPresentPreTick_isReportedAsDecayed() {
        Map<SignalType, Signal> pre = Map.of(A, sig(A, "agent-x"));
        tracker.observe(pre, pre); // cycle 1: emitted, survives

        // cycle 2: present pre-tick (untouched by any agent), but the tick pushes it under threshold
        List<SignalLifetimeTracker.Death> deaths = tracker.observe(pre, Map.of());

        assertThat(deaths).hasSize(1);
        SignalLifetimeTracker.Death d = deaths.get(0);
        assertThat(d.signalType()).isEqualTo(A);
        assertThat(d.emitCycle()).isEqualTo(1);
        assertThat(d.deathCycle()).isEqualTo(2);
        assertThat(d.deathReason()).isEqualTo("decayed");
        assertThat(d.emittedBySource()).isEqualTo("agent-x");
    }

    @Test
    void signalAbsentFromPreTick_afterBeingTrackedAlive_isReportedAsCleared() {
        Map<SignalType, Signal> pre = Map.of(A, sig(A, "agent-x"));
        tracker.observe(pre, pre); // cycle 1: emitted, survives

        // cycle 2: some agent called withoutSignal on A before the tick ran — already gone pre-tick
        List<SignalLifetimeTracker.Death> deaths = tracker.observe(Map.of(), Map.of());

        assertThat(deaths).hasSize(1);
        SignalLifetimeTracker.Death d = deaths.get(0);
        assertThat(d.signalType()).isEqualTo(A);
        assertThat(d.emitCycle()).isEqualTo(1);
        assertThat(d.deathCycle()).isEqualTo(2);
        assertThat(d.deathReason()).isEqualTo("cleared");
    }

    @Test
    void clearedAndDecayed_areNeverBothReportedForTheSameDeath() {
        // A type that is cleared mid-cycle must not ALSO be reported as decayed in the same
        // observe() call — the algorithm must produce exactly one death per episode.
        Map<SignalType, Signal> pre = Map.of(A, sig(A, "agent-x"));
        tracker.observe(pre, pre);

        List<SignalLifetimeTracker.Death> deaths = tracker.observe(Map.of(), Map.of());

        assertThat(deaths).extracting(SignalLifetimeTracker.Death::deathReason).containsExactly("cleared");
    }

    @Test
    void continuingSignal_thatSurvivesManyCycles_neverProducesADeath_untilItDoes() {
        Map<SignalType, Signal> pre = Map.of(A, sig(A, "agent-x"));
        for (int i = 0; i < 5; i++) {
            assertThat(tracker.observe(pre, pre)).isEmpty();
        }
        List<SignalLifetimeTracker.Death> deaths = tracker.observe(pre, Map.of());
        assertThat(deaths).hasSize(1);
        SignalLifetimeTracker.Death d = deaths.get(0);
        assertThat(d.emitCycle()).isEqualTo(1);
        assertThat(d.deathCycle()).isEqualTo(6);
    }

    @Test
    void reEmissionAfterDeath_startsAFreshEpisode_withANewEmitCycle() {
        Map<SignalType, Signal> pre = Map.of(A, sig(A, "agent-x"));
        tracker.observe(pre, pre);          // cycle 1: emitted
        tracker.observe(pre, Map.of());     // cycle 2: decayed, emitCycle=1 deathCycle=2

        // cycle 3: re-emitted fresh by a different agent
        Map<SignalType, Signal> reEmitted = Map.of(A, sig(A, "agent-y"));
        assertThat(tracker.observe(reEmitted, reEmitted)).isEmpty();

        // cycle 4: dies again — the new episode's emitCycle must be 3, not the stale 1
        List<SignalLifetimeTracker.Death> deaths = tracker.observe(reEmitted, Map.of());
        assertThat(deaths).hasSize(1);
        assertThat(deaths.get(0).emitCycle()).isEqualTo(3);
        assertThat(deaths.get(0).deathCycle()).isEqualTo(4);
        assertThat(deaths.get(0).emittedBySource()).isEqualTo("agent-y");
    }

    @Test
    void multipleSignalTypes_areTrackedIndependently() {
        Map<SignalType, Signal> both = Map.of(A, sig(A, "src-a"), B, sig(B, "src-b"));
        tracker.observe(both, both); // cycle 1: both emitted

        // cycle 2: A was explicitly cleared before pre-tick (absent from both maps); B
        // continues untouched (present in both pre and post).
        Map<SignalType, Signal> onlyB = Map.of(B, sig(B, "src-b"));
        List<SignalLifetimeTracker.Death> deaths = tracker.observe(onlyB, onlyB);

        assertThat(deaths).hasSize(1);
        assertThat(deaths.get(0).signalType()).isEqualTo(A);
        assertThat(deaths.get(0).deathReason()).isEqualTo("cleared");
        // B survives untouched — no death reported for it.
    }

    @Test
    void subCycleClearAndReEmit_withinOneObserveCall_isInvisible_documentedLimitation() {
        // A type cleared and re-created within the SAME cycle (before the next observe() call)
        // looks identical to "never died" from a once-per-cycle observer — accepted limitation,
        // see the design note. Confirms no spurious death is reported for this case.
        Map<SignalType, Signal> pre = Map.of(A, sig(A, "agent-x"));
        tracker.observe(pre, pre); // cycle 1

        // cycle 2: same type present in pre (re-emitted by a later agent, after an earlier
        // agent's clear this same cycle) — indistinguishable from continuous presence.
        Map<SignalType, Signal> rebuilt = Map.of(A, sig(A, "agent-z"));
        assertThat(tracker.observe(rebuilt, rebuilt)).isEmpty();
    }

    @Test
    void emptyMaps_produceNoDeathsAndNoErrors() {
        assertThat(tracker.observe(Map.of(), Map.of())).isEmpty();
        assertThat(tracker.observe(null, null)).isEmpty();
    }

    // ---- T4-BZ-3 (I9 re-scope): request-channel lifecycles — consumed vs expired ----

    private static com.walpro.autobuilder.core.lifecycle.ChannelRequest req(SignalType kind, String source) {
        return com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(kind, 1.0, source, 5);
    }

    @Test
    void request_goneBeforePreTick_attributesConsumed() {
        Map<SignalType, com.walpro.autobuilder.core.lifecycle.ChannelRequest> open = Map.of(A, req(A, "issuer-x"));
        tracker.observe(Map.of(), Map.of());
        assertThat(tracker.observeRequests(open, open)).isEmpty(); // tracked, alive

        tracker.observe(Map.of(), Map.of());
        List<SignalLifetimeTracker.Death> deaths = tracker.observeRequests(Map.of(), Map.of());
        assertThat(deaths).singleElement().satisfies(d -> {
            assertThat(d.signalType()).isEqualTo(A);
            assertThat(d.deathReason()).isEqualTo("consumed");
            assertThat(d.emittedBySource()).isEqualTo("issuer-x");
        });
    }

    @Test
    void request_removedByTheTick_attributesExpired() {
        Map<SignalType, com.walpro.autobuilder.core.lifecycle.ChannelRequest> open = Map.of(A, req(A, "issuer-x"));
        tracker.observe(Map.of(), Map.of());
        // present pre-tick, gone post-tick = the channel tick expired it
        List<SignalLifetimeTracker.Death> deaths = tracker.observeRequests(open, Map.of());
        assertThat(deaths).singleElement().satisfies(d -> {
            assertThat(d.signalType()).isEqualTo(A);
            assertThat(d.deathReason()).isEqualTo("expired");
        });
    }

    @Test
    void requestAndSignalLifecycles_doNotInterfere() {
        Map<SignalType, Signal> preSignals = Map.of(A, sig(A, "agent-x"));
        Map<SignalType, com.walpro.autobuilder.core.lifecycle.ChannelRequest> openRequests = Map.of(B, req(B, "issuer-y"));
        assertThat(tracker.observe(preSignals, preSignals)).isEmpty();
        assertThat(tracker.observeRequests(openRequests, openRequests)).isEmpty();

        // Signal A decays; request B stays — only the signal death is reported, as "decayed".
        List<SignalLifetimeTracker.Death> deaths = tracker.observe(preSignals, Map.of());
        assertThat(deaths).singleElement().satisfies(d -> {
            assertThat(d.signalType()).isEqualTo(A);
            assertThat(d.deathReason()).isEqualTo("decayed");
        });
        assertThat(tracker.observeRequests(openRequests, openRequests)).isEmpty();
    }
}
