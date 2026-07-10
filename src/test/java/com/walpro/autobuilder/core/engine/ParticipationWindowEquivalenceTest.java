package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.ChannelRequest;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.ParticipationWindow;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-BZ-4 equivalence gate, two halves:
 *
 * <b>1. Carrier equivalence</b> (the RequestChannelEquivalenceTest pattern): for every frozen
 * state, expressing the ROLE_* entries as participation windows (boost = stored strength, age
 * preserved, validity = the decay-implied lifetime remaining at the recorded age) must produce
 * the SAME deliberation mode, ordering decision and per-role eligibility as the stored form —
 * the migration bridge and the window carrier can never disagree.
 *
 * <b>2. Old-path pin</b>: the new selection pass's walk-down (eligible roles in selection
 * order) must reproduce the OLD fitness-arithmetic path's recorded decisions
 * ({@code frozen-states/selection-baseline-t4bz4.txt}, recorded by SelectionBaselineRecorder
 * BEFORE the engine swap, with the fixed proposal flow). Divergences are legitimate only when
 * individually adjudicated below — never ignored.
 */
class ParticipationWindowEquivalenceTest {

    private static final Set<SignalType> ROLE_KINDS = Set.of(
            BasicSignals.ROLE_PLANNER, BasicSignals.ROLE_EVOLUTION, BasicSignals.ROLE_EVALUATION,
            BasicSignals.ROLE_MERGE, BasicSignals.ROLE_ANALYSIS);

    @Test
    void allFrozenStates_windowFormReproducesStoredFormDecisions() {
        List<String> flips = new ArrayList<>();
        int migratedStates = 0;

        for (FrozenStateFixtures.FrozenState fs : FrozenStateFixtures.all()) {
            CycleContextData stored = fs.ctx();
            CycleContextData windowForm = migrateToWindows(stored);
            if (windowForm == null) continue; // state carries no ROLE_* entries
            migratedStates++;

            FrozenCycleObserver.Observation base = FrozenCycleObserver.observe(stored, fs.world());
            FrozenCycleObserver.Observation moved = FrozenCycleObserver.observe(windowForm, fs.world());

            for (String delta : moved.decisionDeltasVs(base)) {
                flips.add(fs.name() + ": " + delta);
            }
        }

        assertThat(migratedStates)
                .as("the library must actually exercise the migration (states carrying ROLE_* entries)")
                .isGreaterThan(10);
        assertThat(flips)
                .as("store->window migration must not flip any recorded decision")
                .isEmpty();
    }

    /**
     * Adjudicated divergences from the pre-swap baseline — each one traced to a deliberate,
     * documented change, never to accident. Additions require a written justification here
     * and in the design note's deviations section.
     */
    private static final Map<String, String> ACCEPTED_BASELINE_DIVERGENCES = Map.of(
            // Pre-accepted at T4-BZ-0 (design note, Q-a check): today's planner-first pick
            // arose from fitness SUMMING five co-present near-cap attention signals — the F1
            // arithmetic this slice deletes; the rule serves the strongest open request
            // (REPETITION_DETECTED at the 3.0 cap → evolution). The field cycle behind the
            // state actually selected analysis (observer fidelity limit), so the old pick was
            // itself a reconstruction, not ground truth.
            "log-11: frequent-set-6 (n=18)",
            "rule serves the capped repetition request (evolution); old pick was F1 sum artifact",
            // Pick UNCHANGED (planner first); only the tail behind the pick reordered
            // (analysis,evolution → evolution,analysis): the walk-down's later executions now
            // follow the stated base-priority order (evolution 3.0 > analysis 1.5) instead of
            // the fitness sum's coincidental margin. No invariant pins the old tail order.
            "canon: stagnating",
            "same first pick; tail order now stated base-priority instead of sum coincidence");

    @Test
    void newSelectionPass_reproducesTheOldPathsRecordedWalkDown() throws Exception {
        assertThat(SelectionBaselineRecorder.BASELINE)
                .as("pre-swap baseline must exist (recorded before the engine change)")
                .exists();

        List<String> divergences = new ArrayList<>();
        Map<String, String> baseline = new LinkedHashMap<>();
        for (String line : Files.readAllLines(SelectionBaselineRecorder.BASELINE)) {
            if (line.startsWith("#") || line.isBlank()) continue;
            baseline.put(line.substring(0, line.indexOf('|')), line);
        }

        int compared = 0;
        for (FrozenStateFixtures.FrozenState fs : FrozenStateFixtures.all()) {
            String recorded = baseline.get(fs.name());
            if (recorded == null) continue;
            compared++;
            String now = SelectionBaselineRecorder.line(fs);
            if (!now.equals(recorded) && !ACCEPTED_BASELINE_DIVERGENCES.containsKey(fs.name())) {
                divergences.add("  recorded: " + recorded + "\n  now:      " + now);
            }
        }

        assertThat(compared).as("baseline must cover the state library").isEqualTo(baseline.size());
        assertThat(divergences)
                .as("new selection pass diverged from the old path's recorded decisions "
                        + "without adjudication")
                .isEmpty();
    }

    /**
     * Stored ROLE_* entries move to windows 1:1: boost = strength, age preserved, validity =
     * the decay-implied lifetime remaining at the entry's recorded age, decay-equivalent =
     * the stored decay. Null when the state has no ROLE_* entries (nothing to migrate).
     */
    private static CycleContextData migrateToWindows(CycleContextData ctx) {
        if (ctx.signals() == null || ctx.signals().isEmpty()) return null;
        Map<SignalType, Signal> keep = new LinkedHashMap<>();
        List<ParticipationWindow> moved = new ArrayList<>();
        for (Map.Entry<SignalType, Signal> e : ctx.signals().entrySet()) {
            Signal s = e.getValue();
            if (s != null && ROLE_KINDS.contains(e.getKey())) {
                Integer remaining = ChannelRequest.horizonFromDecay(s.strength(), s.decayPerCycle());
                moved.add(new ParticipationWindow(BasicSignals.roleNameFor(e.getKey()), s.strength(),
                        s.source(), s.age(), remaining == null ? null : s.age() + remaining,
                        s.decayPerCycle()));
            } else {
                keep.put(e.getKey(), s);
            }
        }
        if (moved.isEmpty()) return null;
        CycleContextData out = ctx.withSignals(keep);
        for (ParticipationWindow w : moved) {
            out = out.withWindows(out.windows().grant(w));
        }
        return out;
    }
}
