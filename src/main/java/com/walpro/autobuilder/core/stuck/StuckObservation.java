package com.walpro.autobuilder.core.stuck;

import java.util.List;

/**
 * T4-DY slice B: one report from a detector that the loop is stuck, in one shape for every detector.
 *
 * <p>Before this, each detector carried its own reaction (a prompt paragraph, a replan request, a
 * halt) and none knew what another had tried. A detector now only says WHAT it saw; the
 * {@link StuckLedger} decides which question that poses and records what was answered.
 *
 * @param source    which detector saw it
 * @param signature what keeps failing, in the detector's own terms without positions -- the ledger's key
 * @param subGoalId the subgoal being worked on when it was seen; null when none
 * @param cycleId   the cycle it was seen in
 * @param detail    what the detector knows beyond the signature: for a refused closure, the criteria it
 *                  refused on; empty otherwise
 */
public record StuckObservation(Source source, String signature, String subGoalId, String cycleId,
                               List<String> detail) {

    public StuckObservation {
        detail = detail == null ? List.of() : List.copyOf(detail);
    }

    /** The detectors that report here. */
    public enum Source {
        /** GrindDetector: the same build failure in several cycles. */
        BUILD,
        /** GrindDetector via ProtectedPaths: the same refused write to a protected path. */
        PROTECTED_WRITE,
        /** A closure gate refused the same goal on the same grounds again. */
        CLOSURE_REFUSAL,
        /** RootConvergenceTracker: the root has not moved for its whole window. */
        FLAT_ROOT,
        /** The subgoal-exhaustion backstop: subgoal after subgoal given up without a merge. */
        EXHAUSTION,
        /**
         * TestRunGate: the loop's own test runs show the SAME failing tests in several cycles. Work items
         * can keep completing around a failure nothing fixes -- receiver-questions runs 2026-09-29_13-58-51
         * and 14-35-17 finished 3 and 2 items while the same two checks failed for 20+ minutes, so the
         * flat-root signal, which counts completions, never fired.
         */
        TEST_FAILURES,
        /**
         * BehaviouralSelfCheck: the running artifact shows the SAME defect observation after observation.
         * Firestarter run 2026-09-30_00-08-07: "the application exits immediately instead of staying up"
         * from 00:12 to the cut at 00:61 -- merges and completions kept the root moving, the project had no
         * tests, and nothing counted the repeated observation as stuck.
         */
        BOOT,
        /**
         * VetoGrind: one heuristic's veto decides negotiation after negotiation with the same finding, judged
         * persistent by ratios over the run. Rule-extraction run 2026-10-18_19-33-28: 22 of 27 candidates rejected
         * for the same lost annotations the ticket asked to remove, for 80 minutes, and nothing counted it.
         */
        VETO
    }
}
