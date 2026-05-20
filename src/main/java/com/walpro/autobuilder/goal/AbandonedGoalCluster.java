package com.walpro.autobuilder.goal;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * T2-DL: snapshot of a GIVEN_UP goal cluster that's textually related to current work.
 * Surfaces enough context for analysis/planner to extend prior work rather than
 * re-propose from scratch.
 *
 * @param root                       the GIVEN_UP cluster root (parent of all descendants below)
 * @param givenUpAt                  timestamp when the root was marked GIVEN_UP
 * @param resolution                 short rationale string (e.g. "given up after 7 stagnating cycles")
 * @param activeDescendants          descendants still ACTIVE within the cluster (live islands)
 * @param mergeBearingDescendants    descendants with mergeCount &gt; 0, regardless of status
 * @param mergedFiles                flat de-duplicated list of merged filenames produced anywhere in the cluster
 * @param criteriaPassStatus         latest cumulative per-criterion pass status on the root (may be null/empty)
 */
public record AbandonedGoalCluster(
        GoalEntity root,
        Instant givenUpAt,
        String resolution,
        List<GoalEntity> activeDescendants,
        List<GoalEntity> mergeBearingDescendants,
        List<String> mergedFiles,
        Map<Integer, Boolean> criteriaPassStatus
) {
}
