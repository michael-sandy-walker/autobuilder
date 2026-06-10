package com.walpro.autobuilder.graph.evaluation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * T3-BB: Represents a single per-criterion pass/fail result from one evaluation cycle,
 * to be persisted as a {@code CriterionResult} node in Neo4j linked from its parent
 * {@code EvaluationAction} via {@code HAS_CRITERION}.
 *
 * <p>T4-BP step 2: {@link #criterionKey()} is the stable identity — a hash of the normalized
 * criterion text — so a criterion met under subgoal A is recognizable as the same requirement
 * when the planner re-words it under subgoal B. Free-text equality demonstrably misses
 * cross-subgoal matches (see T4-BP).
 */
public record CriterionResultEntity(
        String actionId,
        int criterionIndex,
        String criterionText,
        boolean passed,
        String cycleId,
        String subgoalId
) {

    /** Stable identity of this criterion across subgoals and re-wordings. */
    public String criterionKey() {
        return keyOf(criterionText);
    }

    /**
     * Hash of the normalized criterion text: lower-cased, punctuation collapsed to spaces,
     * whitespace squeezed. Identical requirements with cosmetic re-phrasings of casing or
     * punctuation map to the same key; genuinely re-worded criteria still need the semantic
     * reviewer (T4-AJ) — this key is the cheap exact-after-normalization tier.
     */
    public static String keyOf(String text) {
        if (text == null || text.isBlank()) return "";
        String norm = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(norm.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(24);
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(norm.hashCode());
        }
    }
}
