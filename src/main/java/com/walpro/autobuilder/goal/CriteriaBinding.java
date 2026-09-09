package com.walpro.autobuilder.goal;

import java.util.Map;

/**
 * The one reader of a goal's per-criterion binding stamp.
 *
 * <p>The stamp itself is written by {@code GoalPlannerService.stampCriteriaBinding} from
 * {@code CriteriaGroundingService}'s answer: {@code SPEC} for a criterion the root specification
 * states or implies, {@code AUTHORED} for one the loop added beyond it. What the two mean is a
 * question of AUTHORITY, not of content — the rule asks only "did the specification say this, or
 * did we", never what the claim is about.
 *
 * <p><b>Why this class exists.</b> The read was a private method on {@code GoalPlannerService}
 * with four call sites. T3-CC slice 2 needed a fifth, from the observability readout, and copying
 * a two-line metadata lookup is how one invariant acquires a second home
 * ([[feedback-one-invariant-one-home]]). Everything that asks the question asks it here.
 *
 * <p><b>Absence means SPEC, deliberately.</b> A goal with no stamp — a pre-gate goal, an abstained
 * classification, a run with grounding disabled — is treated as fully specified. That is the safe
 * direction: it can only ever make the loop hold itself to MORE than it authored, never to less.
 */
public final class CriteriaBinding {

    private static final String METADATA_KEY = "criteriaBinding";
    private static final String AUTHORED = "AUTHORED";

    private CriteriaBinding() {
    }

    /**
     * Whether the criterion at {@code oneBasedIndex} was authored by the loop beyond the
     * specification. False whenever there is no stamp to read.
     */
    public static boolean isAuthored(GoalEntity goal, int oneBasedIndex) {
        if (goal == null || goal.getMetadata() == null) return false;
        Object raw = goal.getMetadata().get(METADATA_KEY);
        if (!(raw instanceof Map<?, ?> m)) return false;
        return AUTHORED.equals(m.get(String.valueOf(oneBasedIndex)));
    }
}
