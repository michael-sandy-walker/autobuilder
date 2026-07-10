package com.walpro.autobuilder.core.engine;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * T4-BZ-4 gate, step 1: records the OLD selection path's per-state decisions across the frozen
 * state library BEFORE the participation-window/selection-rule swap, as the committed baseline
 * {@code src/test/resources/frozen-states/selection-baseline-t4bz4.txt} that
 * {@link ParticipationWindowEquivalenceTest} compares the new path against.
 *
 * The recorder only writes when the baseline file does NOT exist — after the engine swap the
 * observer reproduces the NEW path, so regenerating would destroy the pin. To legitimately
 * re-record (e.g. the state library itself grew), delete the file and run this on a tree whose
 * engine still ranks by fitness arithmetic (git history: the commit this recorder landed in).
 *
 * Line format: {@code name|mode|halted|walkDown(role,role,...)|firstEligible}
 * where walkDown = ranked roles that pass eligibility, in ranked order — the projection of the
 * walk-down pass the engine would execute.
 */
class SelectionBaselineRecorder {

    static final Path BASELINE =
            Path.of("src/test/resources/frozen-states/selection-baseline-t4bz4.txt");

    @Test
    void recordBaselineIfAbsent() throws Exception {
        if (Files.exists(BASELINE)) {
            return; // pinned — see class javadoc
        }
        List<String> lines = new ArrayList<>();
        lines.add("# T4-BZ-4 pre-change selection baseline — OLD fitness-arithmetic path.");
        lines.add("# name|mode|halted|walkDown|firstEligible");
        for (FrozenStateFixtures.FrozenState fs : FrozenStateFixtures.all()) {
            lines.add(line(fs));
        }
        Files.createDirectories(BASELINE.getParent());
        Files.write(BASELINE, lines);
        System.out.println("T4-BZ-4: recorded selection baseline for "
                + (lines.size() - 2) + " states at " + BASELINE);
    }

    static String line(FrozenStateFixtures.FrozenState fs) {
        FrozenCycleObserver.Observation obs = FrozenCycleObserver.observe(fs.ctx(), fs.world());
        List<String> walkDown = obs.rankedRoles().stream()
                .filter(r -> obs.blockersByRole().getOrDefault(r, List.of("absent")).isEmpty())
                .toList();
        return fs.name() + "|" + obs.mode() + "|" + obs.haltedBeforeRanking() + "|"
                + String.join(",", walkDown) + "|" + obs.firstEligibleRole();
    }
}
