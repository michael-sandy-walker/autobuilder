package com.walpro.autobuilder.observability;

import com.walpro.autobuilder.core.build.BehaviouralCriterionParser;
import com.walpro.autobuilder.core.build.ExercisableCriterionQuery;
import com.walpro.autobuilder.goal.CriteriaBinding;
import com.walpro.autobuilder.goal.GoalEntity;

import java.util.Collection;
import java.util.List;

/**
 * T2-EX: how much of a run is verifiable at all.
 *
 * <p><b>Why this exists.</b> A criterion that names no endpoint cannot be turned into a request,
 * so it yields no probe, so no contradiction, so no repair and no closure evidence either.
 * Measured over the three frozen runs of 2026-07-30 — same build, same seed, same ceiling — the
 * share of criteria naming an endpoint was <b>77%, 32% and 79%</b>, and the share parsing into an
 * executable assertion tracked it almost exactly (71%, 31%, 74%). The run at 32% was dark on
 * verification for the whole of its length and nothing said so: T4-DB's body-borne probe produces
 * 5 and 19 probes in the other two runs and <b>zero</b> in that one, from identical code.
 *
 * <p><b>Measurement only, on purpose.</b> The ticket forbids a steering change before the share is
 * measured over several runs and a direction call is written. The reason is in the record: T2-ED's
 * own body documents two creation-side prompt rules leaking, and a rule that rewards
 * endpoint-shaped wording would buy the metric rather than the verifiability. There is also a
 * confound to settle first — a run may author structurally BECAUSE it is going badly, rather than
 * the reverse — and that direction cannot be read off one run.
 *
 * <p>Nothing here decides anything. It counts, and the counting uses
 * {@link BehaviouralCriterionParser} itself rather than a second copy of its rules, so the
 * "executable" figure is the one the probe path will actually see.
 *
 * <p><b>T1-FW: that last sentence was false for one class of criterion and is now true again.</b>
 * The count asked {@code parse(...).isPresent()}, which succeeds for a requirement naming an
 * input pattern the probe path always abstains on — so the figure reported an upper bound and
 * called it the observed one. It now asks whether the criterion is exercisable, and those criteria
 * are counted under their own cause rather than folded into the wording shortfall, because the
 * shortfall is neither the wording nor the parser: it is a missing capability.
 *
 * <p><b>T4-DK: it went false a second time, for a whole class of TARGET, and the fix is the same
 * one word — ask what the probe path asks.</b> The count kept asking
 * {@link BehaviouralCriterionParser#exercisable} after the probe path moved to
 * {@link ExercisableCriterionQuery}, which answers for a target that does not serve HTTP as well as
 * one that does. So on a command-line target the figure was a structural ZERO: run
 * {@code 2026-09-10_12-33-26} recorded {@code criteria_executable_count=0} in the durable ledger
 * while three closures were refuted by executing those very criteria. Measured over five archived
 * runs, moving to the query leaves both firestarter rows exactly where they were (10 and 28) and
 * turns three command-target zeros into 4, 8 and 8 — so no comparable row changes meaning, which
 * is the objection {@link Counts#executableShare()} raises against redefining this figure.
 */
public final class CriteriaExecutabilityReadout {

    /**
     * @param authored          acceptance criteria declared anywhere in the goal tree
     * @param addressingSurface those addressed to the target's surface at all — a verb-qualified
     *                          path on a target that serves, and anything a probe can be built
     *                          from on one that does not. Deliberately a SUPERSET of
     *                          {@code executable} by construction: it used to be the endpoint
     *                          mention alone, which on a command-line target counted 0 while
     *                          {@code executable} counted 4, and {@link Counts#namedButUnparsed()}
     *                          subtracts one from the other — a wording shortfall of MINUS four
     * @param executable        those a probe can actually be built from
     * @param ungroundableInput those that parse but name an input component nothing can ground —
     *                          counted apart because their owner is a capability gap (T1-FV), not
     *                          the wording and not the parser
     * @param elaboration       those the loop AUTHORED beyond the specification (T3-CC). Reported
     *                          alongside rather than removed from {@code authored}: the two words
     *                          collide unhappily and mean different things here — {@code authored}
     *                          is "written down at all", {@code elaboration} is "written down by
     *                          us rather than asked for".
     */
    public record Counts(int authored, int addressingSurface, int executable, int ungroundableInput,
                         int elaboration) {

        public boolean isEmpty() {
            return authored == 0;
        }

        /**
         * The share that reaches the probe path, or 0 when nothing was authored.
         *
         * <p><b>Denominator deliberately unchanged by T3-CC.</b> It stays every declared
         * criterion, elaboration included. {@code criteria_executable_pct} already changed
         * meaning on 2026-09-08 under T1-FW, and moving it twice in two days would leave no
         * window in which rows are comparable at all
         * ([[feedback-dont-evaluate-a-build-with-older-runs]]). {@link #elaboration()} is emitted
         * beside it so a reader who wants the specification-only share can compute it —
         * {@code executable / (authored - elaboration)} — rather than being handed a third
         * silently-redefined figure.
         */
        public double executableShare() {
            return authored == 0 ? 0.0 : (double) executable / authored;
        }

        /**
         * Whether the shortfall is in the WORDING rather than in the parser: criteria that name an
         * endpoint but do not parse. Kept separate because the two have different owners, and
         * conflating them is the mistake this readout was written after making.
         */
        public int namedButUnparsed() {
            return addressingSurface - executable - ungroundableInput;
        }
    }

    private CriteriaExecutabilityReadout() {
    }

    /**
     * @param query    the same question the probe path asks. Required rather than defaulted: a
     *                 fallback to the serving-only predicate is exactly how this figure went dark
     *                 on a whole class of target while continuing to report a number.
     * @param specText the specification as stated, or null. The command half of {@code query}
     *                 needs it to ground a criterion against the declared surface; the serving
     *                 half never reads it, so null costs a serving target nothing.
     */
    public static Counts over(Collection<GoalEntity> goals, ExercisableCriterionQuery query,
                              String specText) {
        if (goals == null || goals.isEmpty() || query == null) return new Counts(0, 0, 0, 0, 0);

        // The exercisable set is computed ONCE over every declared criterion rather than per
        // criterion, because the command half parses the specification's surface to answer and
        // doing that per criterion would re-parse the seed for every row of the readout.
        List<String> all = new java.util.ArrayList<>();
        for (GoalEntity goal : goals) all.addAll(criteriaOf(goal));
        java.util.Set<String> exercisable = new java.util.HashSet<>(query.exercisable(all, specText));
        // The surface question is asked of the same seam that answers exercisability, so the two
        // cannot drift. This class used to carry its own HTTP regex for it -- target vocabulary in
        // an observability class, and a second derivation of one question.
        java.util.Set<String> addressing = new java.util.HashSet<>(query.addressingSurface(all, specText));

        int authored = 0;
        int addressingSurface = 0;
        int executable = 0;
        int ungroundableInput = 0;
        int elaboration = 0;
        for (GoalEntity goal : goals) {
            List<String> criteria = criteriaOf(goal);
            for (int i = 0; i < criteria.size(); i++) {
                String criterion = criteria.get(i);
                authored++;
                // T3-CC: the stamp is 1-based, and CriteriaBinding is its single reader.
                if (CriteriaBinding.isAuthored(goal, i + 1)) elaboration++;
                boolean canProbe = exercisable.contains(criterion);
                // Surface-addressing must contain the exercisable, or the cause decomposition
                // reports a negative shortfall. The query guarantees that superset relation.
                if (addressing.contains(criterion)) addressingSurface++;
                if (canProbe) {
                    executable++;
                } else if (BehaviouralCriterionParser.parse(criterion).isPresent()) {
                    ungroundableInput++;
                }
            }
        }
        return new Counts(authored, addressingSurface, executable, ungroundableInput, elaboration);
    }

    private static List<String> criteriaOf(GoalEntity goal) {
        if (goal == null || goal.getMetadata() == null) return List.of();
        Object raw = goal.getMetadata().get("acceptanceCriteria");
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new java.util.ArrayList<>();
        for (Object o : list) {
            if (o != null) out.add(o.toString());
        }
        return out;
    }
}
