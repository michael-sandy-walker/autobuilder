package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.ChannelRequest;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-BZ-3 equivalence gate: for every recorded frozen state, expressing the request-family
 * entries on the typed channel (urgency = stored strength, age preserved, expiry horizon =
 * the stored decay's implied lifetime) must produce the SAME deliberation mode, ranking
 * decision, and per-role eligibility as the stored-signal form the state was recorded with.
 *
 * The stored form keeps working through the migration bridge (recorded fixtures / REST
 * pause-and-poke); this test pins that the two carriers are decision-equivalent, so moving
 * production emitters to the channel cannot have changed selection behavior anywhere the
 * 28-state library reaches. Any future flip fails loudly and must be adjudicated individually.
 */
class RequestChannelEquivalenceTest {

    /** The 12 kinds T4-BZ-3 moves to the channel (10 requests + 2 staleness carriers). */
    private static final Set<SignalType> CHANNEL_KINDS = Set.of(
            BasicSignals.REPLAN_REQUESTED, BasicSignals.DIVERGENCE_REQUEST,
            BasicSignals.LOW_CONFIDENCE, BasicSignals.STAGNATION,
            BasicSignals.REPETITION_DETECTED, BasicSignals.SCORE_PLATEAU,
            BasicSignals.REPLAN_THRASH_DETECTED, BasicSignals.SUBGOAL_EXHAUSTED,
            BasicSignals.EVOLUTION_NOOP, BasicSignals.POTENTIALLY_COMPLETE,
            BasicSignals.MISSING_SYMBOL, BasicSignals.PLANNING_THROTTLED);

    @Test
    void allFrozenStates_channelFormReproducesStoredFormDecisions() {
        List<String> flips = new ArrayList<>();
        int migratedStates = 0;

        for (FrozenStateFixtures.FrozenState fs : FrozenStateFixtures.all()) {
            CycleContextData stored = fs.ctx();
            CycleContextData channelForm = migrateToChannel(stored);
            if (channelForm == null) continue; // state carries no request-family entries
            migratedStates++;

            FrozenCycleObserver.Observation base = FrozenCycleObserver.observe(stored, fs.world());
            FrozenCycleObserver.Observation moved = FrozenCycleObserver.observe(channelForm, fs.world());

            for (String delta : moved.decisionDeltasVs(base)) {
                flips.add(fs.name() + ": " + delta);
            }
        }

        assertThat(migratedStates)
                .as("the library must actually exercise the migration (states carrying request kinds)")
                .isGreaterThan(10);
        assertThat(flips)
                .as("store->channel migration must not flip any recorded decision")
                .isEmpty();
    }

    /**
     * Stored request-kind entries move to the channel 1:1: urgency = strength, age preserved,
     * validity = the decay-implied lifetime REMAINING at the entry's recorded age. Null when
     * the state has no request-family entries (nothing to migrate).
     */
    private static CycleContextData migrateToChannel(CycleContextData ctx) {
        if (ctx.signals() == null || ctx.signals().isEmpty()) return null;
        Map<SignalType, Signal> keep = new LinkedHashMap<>();
        List<ChannelRequest> moved = new ArrayList<>();
        for (Map.Entry<SignalType, Signal> e : ctx.signals().entrySet()) {
            Signal s = e.getValue();
            if (s != null && CHANNEL_KINDS.contains(e.getKey())) {
                Integer horizon = ChannelRequest.horizonFromDecay(s.strength(), s.decayPerCycle());
                moved.add(new ChannelRequest(s.type(), s.strength(), s.source(), null,
                        s.age(), horizon, s.meta() != null ? s.meta() : Map.of()));
            } else {
                keep.put(e.getKey(), s);
            }
        }
        if (moved.isEmpty()) return null;
        CycleContextData out = ctx.withSignals(keep);
        for (ChannelRequest r : moved) {
            out = out.withRequests(out.requests().issue(r));
        }
        return out;
    }
}
