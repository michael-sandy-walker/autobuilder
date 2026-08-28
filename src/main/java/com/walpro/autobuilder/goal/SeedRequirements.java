package com.walpro.autobuilder.goal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * T4-DK (open half): the requirement sentences a specification states in its own words.
 *
 * <p>Root closure verifies the banked criteria of surviving subgoals and never the seed. On run
 * 2026-08-28_05-27-49 that let a flight simulator complete with a placeholder renderer, no HUD and
 * no GL dependency: 23 subgoals given up and 15 subsumed, the ledger read clear, and **the one
 * party never consulted was the specification the whole system exists to serve.**
 *
 * <p>This exists so the seed can be handed to the SAME completion verifier the criteria already go
 * through — no new mechanism, no second LLM pass, one more input to a digest-cached call.
 *
 * <p><b>Conservative by construction.</b> A sentence only counts as a requirement when it says the
 * artifact should do or have something. Titles, restatements of the domain and meta-instructions
 * are dropped, because a bad requirement here is the walling direction: it would block closure on
 * something the spec never asked for, which is the failure T1-FW records from the other side.
 */
public final class SeedRequirements {

    /** Sentence-ish split: terminators, semicolons and hard line breaks all end a clause. */
    private static final Pattern SPLIT = Pattern.compile("(?<=[.;!?])\\s+|\\R{2,}");

    /** The verbs a specification uses to place an obligation on the artifact. */
    private static final Pattern OBLIGATION = Pattern.compile(
            "(?i)\\b(should|must|shall|include|includes|including|provide|provides|support|supports"
                    + "|expose|exposes|display|displays|print|prints|return|returns|accept|accepts"
                    + "|generate|generates|contain|contains|allow|allows|exit|exits|write|writes)\\b");

    /** Text about the exercise rather than the artifact — never a requirement. */
    private static final Pattern META = Pattern.compile(
            "(?i)\\b(do not|don't|you are|your task|the goal of this|for example|e\\.g\\.|note that)\\b");

    private static final int MIN_LENGTH = 25;
    private static final int MAX_REQUIREMENTS = 12;

    private SeedRequirements() {}

    /**
     * The obligations the specification states, in its own words, newest-first order preserved.
     * Empty when the text states none it can recognise — and empty must be read as "no opinion",
     * never as "the seed asks for nothing".
     */
    public static List<String> statedIn(String specText) {
        if (specText == null || specText.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : SPLIT.split(specText)) {
            String s = raw.replaceAll("\\s+", " ").strip();
            if (s.length() < MIN_LENGTH) continue;
            if (META.matcher(s).find()) continue;
            if (!OBLIGATION.matcher(s).find()) continue;
            out.add(s.endsWith(".") ? s.substring(0, s.length() - 1) : s);
            if (out.size() >= MAX_REQUIREMENTS) break;
        }
        return new ArrayList<>(out);
    }

    /**
     * Seed requirements not already covered by a banked criterion, so the verifier is not asked the
     * same question twice. Coverage is judged by distinctive-term overlap rather than equality —
     * a criterion rarely quotes the spec verbatim, and asking again costs a false block.
     */
    public static List<String> notAlreadyCovered(String specText, List<String> bankedCriteria) {
        List<String> stated = statedIn(specText);
        if (stated.isEmpty() || bankedCriteria == null || bankedCriteria.isEmpty()) return stated;
        List<String> uncovered = new ArrayList<>();
        for (String requirement : stated) {
            if (bankedCriteria.stream().noneMatch(c -> overlaps(requirement, c))) uncovered.add(requirement);
        }
        return uncovered;
    }

    /** Two texts overlap when they share several distinctive words — a cheap, domain-free proxy. */
    private static boolean overlaps(String a, String b) {
        if (b == null) return false;
        var wordsA = distinctive(a);
        var wordsB = distinctive(b);
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false;
        int shared = 0;
        for (String w : wordsA) if (wordsB.contains(w)) shared++;
        return shared >= Math.min(3, wordsA.size());
    }

    private static LinkedHashSet<String> distinctive(String text) {
        LinkedHashSet<String> words = new LinkedHashSet<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() > 3 && !STOP.contains(w)) words.add(w);
        }
        return words;
    }

    private static final java.util.Set<String> STOP = java.util.Set.of(
            "should", "must", "shall", "include", "includes", "including", "with", "that", "this",
            "into", "from", "then", "when", "which", "their", "there", "they", "have", "been",
            "also", "together", "itself", "simple", "application");
}
