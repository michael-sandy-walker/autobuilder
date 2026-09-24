package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.graph.goal.GoalGraphService;
import lombok.RequiredArgsConstructor;
import org.simmetrics.StringMetric;
import org.simmetrics.metrics.StringMetrics;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GoalSimilarityService {

    private final GoalGraphService goalGraphService;

    private final StringMetric metric = StringMetrics.mongeElkan();
    private static final float SIMILARITY_THRESHOLD = 0.88f;
    private static final int CANDIDATE_LIMIT = 200;

    /** T1-DE: how many GIVEN_UP matches are allowed before a proposal is blocked from re-injection. */
    public static final int GIVEN_UP_RETRY_LIMIT = 1;

    /** The dedup threshold, exposed for the gate-item revival that reuses its notion of "same". */
    public static final float GIVEN_UP_REVIVAL_SIMILARITY = SIMILARITY_THRESHOLD;

    public boolean isDuplicate(String candidate) {
        if (candidate == null || normalize(candidate).isBlank()) return false;
        return isDuplicate(candidate, goalGraphService.findAllDescriptions(CANDIDATE_LIMIT));
    }

    /**
     * Checks whether {@code candidate} is similar to any description in the provided snapshot.
     * Use this overload when injecting goals in a batch: snapshot the known descriptions once
     * before the loop so that goals added mid-loop do not contaminate the deduplication check.
     */
    public boolean isDuplicate(String candidate, List<String> knownDescriptions) {
        if (candidate == null) return false;
        String c = normalize(candidate);
        if (c.isBlank()) return false;

        for (String known : knownDescriptions) {
            if (known == null) continue;
            float score = metric.compare(c, normalize(known));
            if (score >= SIMILARITY_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /**
     * T2-DO: returns the highest-similarity goal entity matching {@code candidate} (above
     * {@link #SIMILARITY_THRESHOLD}) across ALL provided goals regardless of status, or
     * {@code null} when no match exists. Use when callers need to surface the match's status
     * to decide between blocking silently (ACTIVE/COMPLETED) and registering a rehabilitation
     * vote (GIVEN_UP). The status-narrow variant {@link #findGivenUpMatch(String, List)} stays
     * available for the existing planner rehabilitation path.
     */
    public GoalEntity findEquivalent(String candidate, List<GoalEntity> allGoals) {
        if (candidate == null || allGoals == null || allGoals.isEmpty()) return null;
        String c = normalize(candidate);
        if (c.isBlank()) return null;

        GoalEntity best = null;
        float bestScore = SIMILARITY_THRESHOLD;
        for (GoalEntity g : allGoals) {
            if (g == null || g.getDescription() == null) continue;
            float score = metric.compare(c, normalize(g.getDescription()));
            if (score >= bestScore) {
                bestScore = score;
                best = g;
            }
        }
        return best;
    }

    /**
     * T1-EI: returns the highest-similarity GIVEN_UP goal entity matching {@code candidate}
     * (above {@link #SIMILARITY_THRESHOLD}), or {@code null} when no match exists. Used by
     * the rehabilitation-vote counter so a dedup-blocked proposal can be attributed to the
     * specific abandoned goal it conflicts with.
     */
    public GoalEntity findGivenUpMatch(String candidate, List<GoalEntity> givenUpGoals) {
        if (candidate == null || givenUpGoals == null || givenUpGoals.isEmpty()) return null;
        String c = normalize(candidate);
        if (c.isBlank()) return null;

        GoalEntity best = null;
        float bestScore = SIMILARITY_THRESHOLD;
        for (GoalEntity g : givenUpGoals) {
            if (g == null || g.getDescription() == null) continue;
            float score = metric.compare(c, normalize(g.getDescription()));
            if (score >= bestScore) {
                bestScore = score;
                best = g;
            }
        }
        return best;
    }

    /**
     * Returns a snapshot of all known goal descriptions from the graph.
     * Call once before a batch injection loop and pass the result to
     * {@link #isDuplicate(String, List)} to avoid false-positive deduplication
     * caused by goals added mid-loop appearing in subsequent graph queries.
     */
    public List<String> snapshotKnownDescriptions() {
        return goalGraphService.findAllDescriptions(CANDIDATE_LIMIT);
    }

    /**
     * Counts how many GIVEN_UP goals in the graph are textually similar to {@code candidate}.
     * Used to enforce a per-description retry limit: if this count reaches the limit, the
     * proposal is treated as unachievable and blocked from re-injection.
     */
    public long countGivenUpMatches(String candidate) {
        if (candidate == null) return 0;
        String c = normalize(candidate);
        if (c.isBlank()) return 0;
        List<String> givenUp = goalGraphService.findDescriptionsByStatus("GIVEN_UP", CANDIDATE_LIMIT);
        return givenUp.stream()
                .filter(d -> d != null && metric.compare(c, normalize(d)) >= SIMILARITY_THRESHOLD)
                .count();
    }

    public float similarity(String a, String b) {
        if (a == null || b == null) return 0.0f;
        String aa = normalize(a);
        String bb = normalize(b);
        if (aa.isBlank() || bb.isBlank()) return 0.0f;
        return metric.compare(aa, bb);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }
}
