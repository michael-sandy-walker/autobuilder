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
    /**
     * A clause is a fragment of an obligation-bearing sentence, so it carries meaning at a length
     * a standalone sentence would not. "a simple cockpit HUD" is twenty characters — under the
     * sentence floor, and it is exactly the requirement the false completion dropped.
     */
    private static final int MIN_CLAUSE_LENGTH = 12;
    private static final int MAX_REQUIREMENTS = 12;

    private SeedRequirements() {}

    /**
     * A long sentence can bundle several obligations — the flight-simulator seed states a start
     * menu, the simulator, keyboard input, a cockpit HUD and a generated environment in ONE
     * sentence. Judged whole, covering any part marks all of it covered, and the HUD that no
     * subgoal addressed read as satisfied. Long sentences are therefore also split at clause
     * boundaries; short ones are left intact, since splitting them only manufactures fragments.
     */
    private static List<String> clauses(String sentence) {
        if (sentence == null) return List.of();
        String s = sentence.strip();
        if (s.length() <= BUNDLED_LENGTH) return List.of(s);
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth = Math.max(0, depth - 1);
            // A separator only separates OUTSIDE brackets: "(including Start, Pause, Exit)" is one
            // parenthetical, and cutting it produced "a start menu (including Start" as a
            // requirement while losing the HUD entirely.
            boolean atComma = depth == 0 && c == ',';
            boolean atAnd = depth == 0 && s.startsWith(" and ", i);
            if (atComma || atAnd) {
                parts.add(current.toString());
                current.setLength(0);
                if (atAnd) i += 4;
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString());
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            String p = part.strip().replaceAll("^(?:and|together with|plus)\\s+", "");
            if (p.length() >= MIN_CLAUSE_LENGTH) kept.add(p);
        }
        return kept.isEmpty() ? List.of(s) : kept;
    }

    /** Above this, a sentence is treated as bundling several obligations rather than stating one. */
    private static final int BUNDLED_LENGTH = 120;

    /**
     * The obligations the specification states, in its own words, newest-first order preserved.
     * Empty when the text states none it can recognise — and empty must be read as "no opinion",
     * never as "the seed asks for nothing".
     */
    public static List<String> statedIn(String specText) {
        if (specText == null || specText.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String sentence : SPLIT.split(specText)) {
            // The obligation is a property of the SENTENCE. Its clauses inherit it — "together
            // with a simple cockpit HUD" states a requirement even though the verb sits earlier —
            // so requiring the verb per fragment dropped exactly the clause that mattered.
            if (!OBLIGATION.matcher(sentence).find()) continue;
            for (String raw : clauses(sentence)) {
            String s = raw.replaceAll("\\s+", " ").strip();
            if (s.length() < MIN_CLAUSE_LENGTH) continue;
            if (META.matcher(s).find()) continue;
            out.add(s.endsWith(".") ? s.substring(0, s.length() - 1) : s);
            if (out.size() >= MAX_REQUIREMENTS) break;
            }
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
        // Proportional, not absolute. A fixed threshold of three was written for whole sentences;
        // against CLAUSES it demanded more distinctive words than a short clause contains, so a
        // criterion that genuinely covered "a start menu (Start, Pause, Exit)" was judged not to.
        // That direction WALLS — it manufactures uncovered requirements — so the bar is half the
        // clause's distinctive terms, and never fewer than two, which keeps a single shared word
        // from claiming coverage of a two-word requirement like "cockpit HUD".
        return shared >= Math.min(2, wordsA.size()) && shared * 2 >= wordsA.size();
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
