package com.walpro.autobuilder.core.stuck;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * T4-CT: what EARLIER runs of the same task learned about a stuck signature -- the first use of
 * knowledge that outlives a run.
 *
 * <p>The ledger starts empty at every boot, and across the archive 10 of the 19 stuck signatures ever
 * noticed came back in a later run of the same target (one failing test in 8 runs, one build error in 7);
 * the question whose answer had ended a signature in one run was unknown to the next, which again waited
 * for the third cycle before asking anything.
 *
 * <p>A task is the target's seed: {@link #taskKey} is its SHA-256, so the key names no target and a
 * changed seed is a different task. What is kept is how a signature ENDED, stayed UNANSWERED, or came
 * back after an answer -- never the answer itself, which is always read from the tree as it is now.
 */
public interface StuckPriors {

    /** How one ledger entry turned out in the run that recorded it. */
    enum Outcome {
        /** The answer to the question at {@code rung} was delivered and the signature did not come back. */
        ENDED,
        /** The answer to the question at {@code rung} was delivered and the signature came back anyway. */
        RECURRED,
        /** No question was left that an authority could answer. */
        UNANSWERED
    }

    /** One outcome, as recorded. */
    record Recorded(String taskKey, String source, String signature, int rung, String question, Outcome outcome) {
    }

    /** One recorded outcome as read back: which run, which question, how it turned out. */
    record Row(String runId, int rung, Outcome outcome) {
    }

    /**
     * What other runs of the task recorded for one signature: in how many runs it was noticed, in how many
     * an answer ended it, and the question (by rung) that ended it most often -- null when none did.
     */
    record Prior(int runs, int endedRuns, Integer endingRung) {
        public static final Prior NONE = new Prior(0, 0, null);

        public boolean seenBefore() {
            return runs > 0;
        }

        /**
         * The prior of a set of recorded outcomes. The ending rung is the one that ended the signature in
         * most runs; a tie goes to the earlier question.
         */
        public static Prior of(List<Row> rows) {
            java.util.Set<String> runs = new java.util.HashSet<>();
            java.util.Set<String> ended = new java.util.HashSet<>();
            Map<Integer, java.util.Set<String>> endedAt = new java.util.TreeMap<>();
            for (Row row : rows) {
                runs.add(row.runId());
                if (row.outcome() == Outcome.ENDED) {
                    ended.add(row.runId());
                    endedAt.computeIfAbsent(row.rung(), k -> new java.util.HashSet<>()).add(row.runId());
                }
            }
            Integer best = null;
            for (var e : endedAt.entrySet()) {
                if (best == null || e.getValue().size() > endedAt.get(best).size()) best = e.getKey();
            }
            return new Prior(runs.size(), ended.size(), best);
        }
    }

    /** Keeps one outcome of this run, attributed to {@code cycleId}. */
    void record(Recorded outcome, String cycleId);

    /** What runs other than this one recorded for the signature under this task; {@link Prior#NONE} when nothing. */
    Prior priorFor(String taskKey, String source, String signature);

    /** The task a seed states: its SHA-256 in hex; null for an absent or blank seed, which names no task. */
    static String taskKey(String seed) {
        if (seed == null || seed.isBlank()) return null;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
