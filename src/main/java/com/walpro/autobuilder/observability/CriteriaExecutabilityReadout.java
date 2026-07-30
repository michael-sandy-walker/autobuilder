package com.walpro.autobuilder.observability;

import com.walpro.autobuilder.core.build.BehaviouralCriterionParser;
import com.walpro.autobuilder.goal.GoalEntity;

import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

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
 */
public final class CriteriaExecutabilityReadout {

    /** The same verb-qualified path shape the parser looks for, used only to separate the causes. */
    private static final Pattern NAMES_ENDPOINT =
            Pattern.compile("\\b(GET|POST|PUT|PATCH|DELETE)\\s+/");

    /**
     * @param authored       acceptance criteria declared anywhere in the goal tree
     * @param namingEndpoint those addressed to an endpoint at all
     * @param executable     those the parser turns into an assertion a probe can be built from
     */
    public record Counts(int authored, int namingEndpoint, int executable) {

        public boolean isEmpty() {
            return authored == 0;
        }

        /** The share that reaches the probe path, or 0 when nothing was authored. */
        public double executableShare() {
            return authored == 0 ? 0.0 : (double) executable / authored;
        }

        /**
         * Whether the shortfall is in the WORDING rather than in the parser: criteria that name an
         * endpoint but do not parse. Kept separate because the two have different owners, and
         * conflating them is the mistake this readout was written after making.
         */
        public int namedButUnparsed() {
            return namingEndpoint - executable;
        }
    }

    private CriteriaExecutabilityReadout() {
    }

    public static Counts over(Collection<GoalEntity> goals) {
        if (goals == null || goals.isEmpty()) return new Counts(0, 0, 0);
        int authored = 0;
        int namingEndpoint = 0;
        int executable = 0;
        for (GoalEntity goal : goals) {
            for (String criterion : criteriaOf(goal)) {
                authored++;
                if (NAMES_ENDPOINT.matcher(criterion).find()) namingEndpoint++;
                if (BehaviouralCriterionParser.parse(criterion).isPresent()) executable++;
            }
        }
        return new Counts(authored, namingEndpoint, executable);
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
