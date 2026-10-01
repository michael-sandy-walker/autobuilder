package com.walpro.autobuilder.client;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How long a model's answer may run, learned from the run's own answers -- and what to do with one that
 * runs away.
 *
 * <p>The client sent no output limit, so an answer that never ended ran until the read timeout, was retried
 * identically, timed out longer, and five such attempts opened the circuit breaker: two of three
 * parameterized-rows runs (2026-09-30) ended that way. Probed offline on the real prompts
 * ({@code scripts/llm_cap_probe.py}, 24 calls): at temperature 0 the outcome is FIXED per prompt -- a prompt
 * that runs away (one import line repeated from ~600 tokens on, to 16k tokens and beyond) does so every time,
 * so an identical retry cannot succeed; a normal answer ends by itself (3.8k tokens, ~70 s). And no single
 * sampling setting is right for every prompt: a frequency penalty rescued both runaways but left a normal
 * answer's code fences unclosed, a higher temperature rescued one runaway and not the other.
 *
 * <p>So, per role (each role answers a different kind of question):
 * <ul>
 *   <li>every call is capped. The cap starts at the configured response reserve, grows ADDITIVELY (half a
 *       reserve) when a clean answer was cut by it, and shrinks MULTIPLICATIVELY (by half) after a runaway --
 *       never below the reserve, nor below the longest answer the role has ended by itself plus half a
 *       reserve; a runaway's rescue keeps the cap it ran into, the shrink is for the role's next call;</li>
 *   <li>a RUNAWAY is an answer cut by the cap whose tail repeats itself: at least {@link #RUNAWAY_SHARE} of
 *       its last {@link #TAIL_LINES} non-blank lines occurred earlier in it (runaways measured 0.98, normal
 *       answers 0.15-0.33). It is retried with a changed request, in {@link #RUNAWAY_VARIANTS} order -- never
 *       identically;</li>
 *   <li>the read timeout follows the cap at the run's observed seconds per completion token, with headroom,
 *       so a capped call is not cut by a timeout that predates the cap.</li>
 * </ul>
 * Thread-safe; state lives for the JVM, which is one run.
 */
public final class OutputBounds {

    /** How many of an answer's last non-blank lines are compared with what came before them. */
    static final int TAIL_LINES = 60;

    /** The share of those lines already seen that makes a cut answer a runaway. */
    static final double RUNAWAY_SHARE = 0.8;

    /** The time a cap may take, as a multiple of what the run's answers so far took per token. */
    static final double TIMEOUT_HEADROOM = 1.5;

    /** A changed request for a runaway: what the probe rescued runaways with, in that order. */
    public record Variant(String name, Double temperature, Double frequencyPenalty) {}

    public static final List<Variant> RUNAWAY_VARIANTS = List.of(
            new Variant("frequency penalty 0.5", null, 0.5),
            new Variant("temperature 0.4", 0.4, null));

    /** How one answer ended, as far as the bound is concerned. */
    public enum Verdict {
        /** The model ended it. */
        ENDED,
        /** The cap ended a clean answer: it needed more room. */
        CUT_CLEAN,
        /** The cap ended an answer that was repeating itself. */
        RUNAWAY
    }

    private final int reserve;
    private final Map<String, Integer> cap = new ConcurrentHashMap<>();
    private final Map<String, Integer> longestEnded = new ConcurrentHashMap<>();
    private volatile double secondsPerToken;

    /** {@code responseReserveTokens}: the output room the prompt budget leaves; the first cap of every role. */
    public OutputBounds(int responseReserveTokens) {
        this.reserve = Math.max(256, responseReserveTokens);
    }

    /** The cap for the next call of {@code role}. */
    public int capFor(String role) {
        return cap.getOrDefault(key(role), reserve);
    }

    /** What an answer was: ended by the model, cut clean, or a runaway. */
    public static Verdict verdict(String finishReason, String content) {
        if (!"length".equals(finishReason)) return Verdict.ENDED;
        return tailRepeats(content) >= RUNAWAY_SHARE ? Verdict.RUNAWAY : Verdict.CUT_CLEAN;
    }

    /** The share of the last {@link #TAIL_LINES} non-blank lines that already occurred earlier in the text. */
    static double tailRepeats(String content) {
        if (content == null) return 0.0;
        List<String> lines = content.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        if (lines.size() <= TAIL_LINES) {
            // A short text: compare each line with those before it.
            Set<String> seen = new HashSet<>();
            int repeated = 0;
            for (String l : lines) if (!seen.add(l)) repeated++;
            return lines.isEmpty() ? 0.0 : repeated / (double) lines.size();
        }
        Set<String> head = new HashSet<>(lines.subList(0, lines.size() - TAIL_LINES));
        int repeated = 0;
        for (String l : lines.subList(lines.size() - TAIL_LINES, lines.size())) if (head.contains(l)) repeated++;
        return repeated / (double) TAIL_LINES;
    }

    /**
     * Learns from one answer of {@code role} and returns the cap for its next call: unchanged after an answer
     * the model ended (which only raises the floor), half a reserve more after a clean cut, halved (down to the
     * floor) after a runaway.
     */
    public int learn(String role, Verdict verdict, Integer completionTokens, long durationMs) {
        String k = key(role);
        if (completionTokens != null && completionTokens > 0 && durationMs > 0) {
            double observed = durationMs / 1000.0 / completionTokens;
            // A moving average: recent answers weigh most, one slow call does not set the timeout alone.
            secondsPerToken = secondsPerToken == 0 ? observed : secondsPerToken * 0.8 + observed * 0.2;
        }
        int current = capFor(role);
        int next = switch (verdict) {
            case ENDED -> {
                if (completionTokens != null) longestEnded.merge(k, completionTokens, Math::max);
                yield Math.max(current, floor(k));
            }
            case CUT_CLEAN -> current + reserve / 2;
            case RUNAWAY -> Math.max(floor(k), current / 2);
        };
        cap.put(k, next);
        return next;
    }

    /** The read timeout for a call capped at {@code capTokens}: never below {@code baseMs}. */
    public int timeoutFor(int capTokens, int baseMs) {
        double spt = secondsPerToken;
        if (spt <= 0) return baseMs;
        long ms = (long) (capTokens * spt * TIMEOUT_HEADROOM * 1000);
        return (int) Math.max(baseMs, Math.min(ms, Integer.MAX_VALUE));
    }

    /**
     * The lowest cap a role may get: the configured reserve (the output room the operator stated), or its
     * longest answer the model ended plus half a reserve when that is more. Below the reserve, the live check
     * (2026-10-01) showed what a shrink costs: a normal answer then needs two growths and three calls.
     */
    private int floor(String k) {
        return Math.max(reserve, longestEnded.getOrDefault(k, 0) + reserve / 2);
    }

    private static String key(String role) {
        return role == null || role.isBlank() ? "unknown" : role;
    }
}
