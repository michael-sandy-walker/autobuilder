package com.walpro.autobuilder.core.text;

/**
 * Generic string-distance utilities. Currently exposes Levenshtein only; add Damerau-Levenshtein,
 * Jaro-Winkler, etc. here as callers need them rather than letting each callsite write its own.
 *
 * <p>Levenshtein here is intentionally minimal: an iterative two-row implementation with an
 * early-bail on length difference, no allocation per character. The callers that drive this today
 * are typo-of-existing-symbol checks (e.g. skeleton-resolver similarity-abort) where the cutoff
 * threshold is small (≤ 2) and inputs are short identifiers — the early-bail is the dominant
 * optimisation.
 */
public final class StringDistances {

    private StringDistances() {}

    /**
     * Standard Levenshtein distance. Returns {@link Integer#MAX_VALUE} when either argument is
     * {@code null}, which lets distance-threshold checks fall through to "definitely not similar"
     * without an extra null guard at the callsite.
     */
    public static int levenshtein(String a, String b) {
        return levenshtein(a, b, Integer.MAX_VALUE);
    }

    /**
     * Bounded Levenshtein: returns {@link Integer#MAX_VALUE} when the distance is guaranteed to
     * exceed {@code maxThreshold} (today: length difference > threshold). The full DP still runs
     * for inputs that survive the length filter — a banded DP would be a useful optimisation if
     * we ever start computing distances on large strings.
     */
    public static int levenshtein(String a, String b, int maxThreshold) {
        if (a == null || b == null) return Integer.MAX_VALUE;
        int n = a.length();
        int m = b.length();
        if (Math.abs(n - m) > maxThreshold) return Integer.MAX_VALUE;
        if (n == 0) return m;
        if (m == 0) return n;
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[m];
    }
}
