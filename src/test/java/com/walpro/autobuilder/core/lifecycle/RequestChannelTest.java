package com.walpro.autobuilder.core.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-BZ-3: the typed request channel — issue/raise/consume/tick semantics, the migration
 * bridge on CycleContextData, and the decay-implied expiry horizons (1:1 equivalence pins).
 */
class RequestChannelTest {

    // ---- horizonFromDecay: the migration formula reproduces stored-signal lifetimes ----

    @Test
    void horizonFromDecay_reproducesDecayImpliedLifetimes() {
        // strength 1.0 / decay 0.25: survived ticks at ages 1..4 (first tick no decay), died on the 5th
        assertThat(ChannelRequest.horizonFromDecay(1.0, 0.25)).isEqualTo(4);
        // 1.0/0.05 → 19 decel steps
        assertThat(ChannelRequest.horizonFromDecay(1.0, 0.05)).isEqualTo(19);
        // 1.0/0.5 → dies quickly
        assertThat(ChannelRequest.horizonFromDecay(1.0, 0.5)).isEqualTo(2);
        // decay 0.0 = never decays = open until consumed
        assertThat(ChannelRequest.horizonFromDecay(1.0, 0.0)).isNull();
    }

    // ---- raise mirrors Signal.reinforce (Q-c: additive, capped, age reset) ----

    @Test
    void raise_isAdditiveCappedAndAgeResetting() {
        ChannelRequest first = ChannelRequest.of(BasicSignals.REPETITION_DETECTED, 1.0, "a", 4);
        ChannelRequest aged = first.tick().tick(); // age 2
        ChannelRequest reissued = aged.raise(ChannelRequest.of(BasicSignals.REPETITION_DETECTED, 1.0, "b", 4));
        assertThat(reissued.urgency()).isEqualTo(2.0);
        assertThat(reissued.age()).isZero();
        @SuppressWarnings("unchecked")
        List<String> sources = (List<String>) reissued.meta().get("sources");
        assertThat(sources).containsExactly("a", "b");

        ChannelRequest capped = reissued.raise(ChannelRequest.of(BasicSignals.REPETITION_DETECTED, 2.5, "c", 4));
        assertThat(capped.urgency()).isEqualTo(ChannelRequest.MAX_URGENCY);
    }

    @Test
    void raise_untilConsumedValidityWins() {
        ChannelRequest bounded = ChannelRequest.of(BasicSignals.STAGNATION, 0.7, "a", 4);
        ChannelRequest unbounded = ChannelRequest.of(BasicSignals.STAGNATION, 1.0, "b", null);
        assertThat(bounded.raise(unbounded).validForCycles()).isNull();
    }

    // ---- channel ops ----

    @Test
    void issue_consume_roundTrip() {
        RequestChannel ch = RequestChannel.empty()
                .issue(ChannelRequest.of(BasicSignals.REPLAN_REQUESTED, 1.0, "test", 5));
        assertThat(ch.hasOpen(BasicSignals.REPLAN_REQUESTED)).isTrue();
        assertThat(ch.urgency(BasicSignals.REPLAN_REQUESTED)).isEqualTo(1.0);
        RequestChannel consumed = ch.consume(BasicSignals.REPLAN_REQUESTED);
        assertThat(consumed.hasOpen(BasicSignals.REPLAN_REQUESTED)).isFalse();
    }

    @Test
    void issue_existingKindRaisesInsteadOfReplacing() {
        RequestChannel ch = RequestChannel.empty()
                .issue(ChannelRequest.of(BasicSignals.REPLAN_REQUESTED, 0.8, "first", 5))
                .issue(ChannelRequest.of(BasicSignals.REPLAN_REQUESTED, 0.8, "second", 5));
        assertThat(ch.urgency(BasicSignals.REPLAN_REQUESTED)).isEqualTo(1.6);
    }

    @Test
    void tick_agesAndExpiresPastHorizon() {
        RequestChannel ch = RequestChannel.empty()
                .issue(ChannelRequest.of(BasicSignals.STAGNATION, 0.7, "test", 2));
        RequestChannel.TickResult t1 = ch.tick(null);
        RequestChannel.TickResult t2 = t1.channel().tick(null);
        assertThat(t2.channel().hasOpen(BasicSignals.STAGNATION)).isTrue();
        RequestChannel.TickResult t3 = t2.channel().tick(null);
        assertThat(t3.channel().hasOpen(BasicSignals.STAGNATION)).isFalse();
        assertThat(t3.expired()).hasSize(1);
        assertThat(t3.expired().get(0).kind()).isEqualTo(BasicSignals.STAGNATION);
    }

    @Test
    void tick_untilConsumedRequestNeverExpires() {
        RequestChannel ch = RequestChannel.empty()
                .issue(ChannelRequest.of(BasicSignals.EVOLUTION_NOOP, 1.0, "noop", null));
        for (int i = 0; i < 30; i++) {
            RequestChannel.TickResult t = ch.tick(null);
            assertThat(t.expired()).isEmpty();
            ch = t.channel();
        }
        assertThat(ch.hasOpen(BasicSignals.EVOLUTION_NOOP)).isTrue();
    }

    @Test
    void tick_verifiedEarlyResolutionExpiresRequest() {
        RequestChannel ch = RequestChannel.empty()
                .issue(ChannelRequest.of(BasicSignals.MISSING_SYMBOL, 1.0, "planner-grounding:LoanRepository", 4));
        RequestChannel.TickResult t = ch.tick(r -> r.source().endsWith(":LoanRepository"));
        assertThat(t.channel().hasOpen(BasicSignals.MISSING_SYMBOL)).isFalse();
        assertThat(t.expired()).hasSize(1);
    }

    @Test
    void withoutKinds_and_ofKinds_scopeTheSubgoalFamily() {
        RequestChannel ch = RequestChannel.empty()
                .issue(ChannelRequest.of(BasicSignals.STAGNATION, 0.7, "a", 4))
                .issue(ChannelRequest.of(BasicSignals.REPLAN_REQUESTED, 1.0, "b", 5));
        assertThat(ch.ofKinds(BasicSignals.SUBGOAL_SCOPED_SIGNALS))
                .extracting(ChannelRequest::kind).containsExactly(BasicSignals.STAGNATION);
        RequestChannel scopedOut = ch.withoutKinds(BasicSignals.SUBGOAL_SCOPED_SIGNALS);
        assertThat(scopedOut.hasOpen(BasicSignals.STAGNATION)).isFalse();
        assertThat(scopedOut.hasOpen(BasicSignals.REPLAN_REQUESTED)).isTrue();
    }

    // ---- CycleContextData unified reads + migration bridge ----

    @Test
    void contextReads_channelEntryVisible() {
        CycleContextData ctx = CycleContextData.builder().build()
                .issueRequest(ChannelRequest.of(BasicSignals.REPLAN_REQUESTED, 0.8, "test", 5));
        assertThat(ctx.hasOpenRequest(BasicSignals.REPLAN_REQUESTED)).isTrue();
        assertThat(ctx.hasStrongRequest(BasicSignals.REPLAN_REQUESTED, 0.5)).isTrue();
        assertThat(ctx.hasStrongRequest(BasicSignals.REPLAN_REQUESTED, 0.9)).isFalse();
        assertThat(ctx.requestUrgency(BasicSignals.REPLAN_REQUESTED)).isEqualTo(0.8);
    }

    @Test
    void contextReads_bridgeHonorsStoredSignalTwin() {
        // Recorded fixture / REST-injected stored entry of a request kind still reads.
        CycleContextData ctx = CycleContextData.builder().build()
                .withSignal(Signal.of(BasicSignals.STAGNATION, 0.7, 0.2, "recorded"));
        assertThat(ctx.hasOpenRequest(BasicSignals.STAGNATION)).isTrue();
        assertThat(ctx.requestUrgency(BasicSignals.STAGNATION)).isEqualTo(0.7);
        ChannelRequest peeked = ctx.peekRequest(BasicSignals.STAGNATION);
        assertThat(peeked).isNotNull();
        assertThat(peeked.source()).isEqualTo("recorded");
    }

    @Test
    void issueAbsorbsTheStoredTwin_consumeClearsBothCarriers() {
        // T4-BZ-4 fix: an issue over a stored twin ABSORBS it (raise = old Signal.reinforce
        // semantics: 0.7 + 1.0), instead of dropping the accumulated strength (the original
        // clear-only bridge flipped the REPLAN>=1.0 throttle-override read on log-11).
        CycleContextData ctx = CycleContextData.builder().build()
                .withSignal(Signal.of(BasicSignals.STAGNATION, 0.7, 0.2, "recorded"));
        CycleContextData issued = ctx.issueRequest(ChannelRequest.of(BasicSignals.STAGNATION, 1.0, "fresh", 4));
        assertThat(issued.hasSignal(BasicSignals.STAGNATION)).isFalse();
        assertThat(issued.requestUrgency(BasicSignals.STAGNATION)).isEqualTo(1.7);

        CycleContextData consumed = issued.consumeRequest(BasicSignals.STAGNATION);
        assertThat(consumed.hasOpenRequest(BasicSignals.STAGNATION)).isFalse();
    }

    @Test
    void consumeRequestIfVerified_leavesUnverifiedOpen() {
        CycleContextData ctx = CycleContextData.builder().build()
                .issueRequest(ChannelRequest.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, "test", 5));
        CycleContextData still = ctx.consumeRequestIfVerified(BasicSignals.DIVERGENCE_REQUEST, false);
        assertThat(still.hasOpenRequest(BasicSignals.DIVERGENCE_REQUEST)).isTrue();
        CycleContextData gone = ctx.consumeRequestIfVerified(BasicSignals.DIVERGENCE_REQUEST, true);
        assertThat(gone.hasOpenRequest(BasicSignals.DIVERGENCE_REQUEST)).isFalse();
    }

    // ---- readout composition ----

    @Test
    void readout_rendersOpenRequestsAsRequestFamilyEntries() {
        CycleContextData ctx = CycleContextData.builder().cycleName("c1").build()
                .issueRequest(ChannelRequest.of(BasicSignals.REPLAN_REQUESTED, 0.8, "test", 5));
        SignalReadout readout = SignalReadout.of(ctx);
        assertThat(readout.entries())
                .anySatisfy(e -> {
                    assertThat(e.name()).isEqualTo("REPLAN_REQUESTED");
                    assertThat(e.family()).isEqualTo(SignalReadout.Family.REQUEST);
                    assertThat(e.strength()).isEqualTo(0.8);
                    assertThat(e.meta()).containsEntry("validForCycles", 5);
                });
    }

    @Test
    void readout_storedTwinWinsOverChannelEntry() {
        CycleContextData ctx = CycleContextData.builder().cycleName("c1").build()
                .withSignal(Signal.of(BasicSignals.STAGNATION, 0.4, 0.2, "recorded"));
        ctx = ctx.withRequests(ctx.requests().issue(ChannelRequest.of(BasicSignals.STAGNATION, 1.0, "channel", 4)));
        SignalReadout readout = SignalReadout.of(ctx);
        assertThat(readout.entries())
                .filteredOn(e -> e.name().equals("STAGNATION"))
                .singleElement()
                .satisfies(e -> assertThat(e.strength()).isEqualTo(0.4));
    }

    @Test
    void readout_unconsumedRequestsListsOnlyAgedOpenRequests() {
        CycleContextData ctx = CycleContextData.builder().cycleName("c1").build()
                .issueRequest(ChannelRequest.of(BasicSignals.REPLAN_REQUESTED, 1.0, "fresh", 5));
        assertThat(SignalReadout.of(ctx).unconsumedRequests()).isEmpty();

        CycleContextData aged = ctx.withRequests(ctx.requests().tick(null).channel());
        assertThat(SignalReadout.of(aged).unconsumedRequests())
                .singleElement()
                .satisfies(e -> assertThat(e.name()).isEqualTo("REPLAN_REQUESTED"));
    }

    // ---- seeder interplay ----

    @Test
    void seeder_treatsOpenRequestsAsCarryOver() {
        CycleStartSignalSeeder seeder = new CycleStartSignalSeeder();
        CycleContextData withRequest = CycleContextData.builder()
                .signals(new java.util.LinkedHashMap<>())
                .currentGoalId("g1")
                .build()
                .issueRequest(ChannelRequest.of(BasicSignals.REPLAN_REQUESTED, 1.0, "carry", 5));
        CycleContextData seeded = seeder.seedIfEmpty(withRequest);
        assertThat(seeded.windows().isEmpty()).as("open request = carry-over, no re-seed").isTrue();

        CycleContextData bare = CycleContextData.builder()
                .signals(new java.util.LinkedHashMap<>())
                .currentGoalId("g1")
                .build();
        assertThat(seeder.seedIfEmpty(bare).windows().isEmpty()).as("bare context seeds windows").isFalse();
    }
}
