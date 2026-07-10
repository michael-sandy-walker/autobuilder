package com.walpro.autobuilder.core.engine;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-BZ-4/5 standing regression net: the selection pass's walk-down (eligible roles in
 * selection order) must reproduce the OLD fitness-arithmetic path's recorded decisions
 * ({@code frozen-states/selection-baseline-t4bz4.txt}, recorded by SelectionBaselineRecorder
 * BEFORE the T4-BZ-4 engine swap, with the fixed proposal flow). Divergences are legitimate
 * only when individually adjudicated below — never ignored.
 *
 * The slice-4 store-vs-carrier half of this gate (window form ≡ stored form, zero flips
 * across the library) was deleted with the migration bridge in T4-BZ-5: with the stored map
 * gone there is only one carrier left to compare. Its result is recorded in the design note.
 */
class ParticipationWindowEquivalenceTest {

    /**
     * Adjudicated divergences from the pre-swap baseline — each one traced to a deliberate,
     * documented change, never to accident. Additions require a written justification here
     * and in the design note's deviations section.
     */
    private static final Map<String, String> ACCEPTED_BASELINE_DIVERGENCES = Map.of(
            // Pre-accepted at T4-BZ-0 (design note, Q-a check): today's planner-first pick
            // arose from fitness SUMMING five co-present near-cap attention signals — the F1
            // arithmetic slice 4 deleted; the rule serves the strongest open request
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
                .as("selection pass diverged from the recorded baseline without adjudication")
                .isEmpty();
    }
}
