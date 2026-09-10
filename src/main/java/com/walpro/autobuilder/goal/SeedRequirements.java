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

    /**
     * Sentence boundaries, bracket- and quote-aware. A regex split at {@code [.;!?]} honoured the
     * semicolons INSIDE the firestarter seed's parentheticals — "POST /books (create; title
     * required...)" — splicing list items into dangling-paren fragments that then sat at the
     * closure edge as unconfirmable requirements. Found by running the extractor over the three
     * real seeds, not by reading it.
     *
     * <p><b>T6-S: this is the ONE HOME for the question, and it has a second caller now.</b>
     * {@code SeedCoverageAnalyzer} split the same seeds with a bare {@code (?<=[.;])\s+} and made
     * the identical mistake on the identical sentence, four months apart — the fix was written
     * once and not shared, so the second copy never got it ([[feedback-one-invariant-one-home]]).
     * A single newline is deliberately NOT a boundary: every seed here is hard-wrapped prose, so
     * treating one as a break tears a sentence in half. A blank line still separates paragraphs,
     * which keeps a heading off the prose beneath it.
     */
    /**
     * Specification prose split the way this class splits it: bracket- and quote-aware sentences,
     * then clause boundaries for any sentence long enough to be bundling several obligations. No
     * obligation or meta filtering — that part is this class's own question, not a shared one.
     *
     * <p><b>T6-S: shared because the second copy of this logic had the bug the first one fixed.</b>
     * {@code SeedCoverageAnalyzer} split the same seeds with a bare {@code (?<=[.;])\s+} over
     * physical lines and made both mistakes this class had already met — cutting at the semicolons
     * inside the firestarter seed's parentheticals, and tearing hard-wrapped sentences in half.
     * Taking only the sentence half swung it the other way: the whole Loans paragraph became ONE
     * 250-character clause, and a goal covering one of its obligations could no longer clear it.
     * Both stages together are what produce clauses that are whole AND separable.
     */
    public static List<String> clausesOf(String specText) {
        if (specText == null || specText.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String sentence : sentences(specText)) out.addAll(clauses(sentence, false));
        return out;
    }

    /**
     * Split specification prose at the given separator characters, but only where they are OUTSIDE
     * brackets and quotes; a blank line always separates. The depth-tracking scan is the shared
     * part — the separator SET and what a caller does with the pieces are its own policy.
     *
     * <p><b>Why the granularity is not shared.</b> This class wants whole obligations, because a
     * fragment reaching the closure edge becomes a requirement the verifier can never confirm and
     * walls the run; it therefore splits a bundled sentence only at boundaries that open a new item.
     * {@code SeedCoverageAnalyzer} wants the opposite trade: its readout is advisory prompt text, a
     * spurious line costs a reader a glance, and a clause covering three endpoints at once can
     * never be cleared by a plan that implements one of them. Sharing the SCAN and not the POLICY
     * is what lets both be right.
     */
    public static List<String> splitOutsideBracketsAndQuotes(String text, String separators) {
        if (text == null || text.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0; boolean inQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') inQuote = false;
            if (c == '"') inQuote = !inQuote;
            else if (!inQuote && (c == '(' || c == '[' || c == '{')) depth++;
            else if (!inQuote && (c == ')' || c == ']' || c == '}')) depth = Math.max(0, depth - 1);
            current.append(c);
            // A separator separates only when whitespace or end-of-text follows it. Without that,
            // the "?" in "GET /loans?memberId={id}" ends a sentence and the requirement is
            // truncated to "GET /loans?" — which the T6-S audit found on the verifier side as a
            // yardstick check with no licence behind it, and which reappeared here verbatim the
            // moment the analyzer started using the same scan. It also protects a decimal point
            // and an abbreviation from ending a clause.
            boolean followedByBreak = i + 1 >= text.length() || Character.isWhitespace(text.charAt(i + 1));
            boolean separator = !inQuote && depth == 0 && separators.indexOf(c) >= 0 && followedByBreak;
            boolean paragraph = c == '\n' && i + 1 < text.length() && text.charAt(i + 1) == '\n';
            if (separator || paragraph) {
                out.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) out.add(current.toString());
        return out;
    }

    private static List<String> sentences(String text) {
        return splitOutsideBracketsAndQuotes(text, ".;!?");
    }

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
        return clauses(sentence, true);
    }

    /**
     * @param wholeItemsOnly this class's own precision policy. A comma or "and" that does not open
     *                       a new noun phrase continues the current requirement, so "may contain
     *                       commas and newlines" stays one obligation and "a simple (i.e.,
     *                       polygonic), randomly generated environment" is not cut in half.
     *                       <p>{@code false} for a consumer that wants SEPARABLE clauses rather
     *                       than whole obligations. The firestarter seed states three endpoints as
     *                       one comma-separated list — "…\\d{13}), GET /books (list all), GET
     *                       /books/{id} (single; 404 if absent)" — and each is a requirement a plan
     *                       can address on its own. Kept whole, a plan implementing one of them can
     *                       never clear the clause, so it is reported for the life of the run.
     */
    private static List<String> clauses(String sentence, boolean wholeItemsOnly) {
        if (sentence == null) return List.of();
        String s = sentence.strip();
        if (s.length() <= BUNDLED_LENGTH) return List.of(s);
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0; boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') inQuote = !inQuote;
            else if (!inQuote && (c == '(' || c == '[')) depth++;
            else if (!inQuote && (c == ')' || c == ']')) depth = Math.max(0, depth - 1);
            // Separators only separate OUTSIDE brackets AND quotes: the clitarget seed's own
            // format string — "line <n>: expected <h> fields, found <f>" — was being cut at its
            // interior comma, leaving half a quotation as a requirement.
            boolean atComma = depth == 0 && !inQuote && c == ',';
            // " and " separates two ITEMS ("a cockpit HUD and a randomly generated environment")
            // but not a compound OBJECT of one verb ("may contain commas and newlines"). The
            // difference is whether what follows opens a new noun phrase, which is the same test
            // the comma-after-parenthesis branch below already makes — reused rather than
            // re-invented so the two cannot drift.
            //
            // T6-S measured what splitting blind costs: the clitarget seed's "a quoted field may
            // contain commas and newlines" became "a quoted field may contain commas", dropping
            // the operative word, and the closure edge's seed backstop then licensed neither of
            // the two yardstick checks that the T4-DK false completion actually failed.
            boolean atAnd = depth == 0 && !inQuote && s.startsWith(" and ", i)
                    && (!wholeItemsOnly || opensNewItem(s.substring(Math.min(i + 5, s.length()))));
            // A comma directly after a closing parenthesis usually CONTINUES the same noun phrase
            // — "a simple (i.e., polygonic), randomly generated environment" is one requirement —
            // unless what follows starts a new item ("..., the flight simulator itself").
            if (wholeItemsOnly && atComma && i > 0 && s.charAt(i - 1) == ')'
                    && !opensNewItem(s.substring(Math.min(i + 1, s.length())))) {
                current.append(c);
                continue;
            }
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
            // Normalise BEFORE the stop-word check: a clause spanning a line break defeated the
            // `.*` (which stops at newlines), so "where <n> counts…" slipped past the filter.
            String p = part.strip().replaceAll("\\s+", " ")
                    .replaceAll("^(?:and|together with|plus)\\s+", "");
            if (p.length() < MIN_CLAUSE_LENGTH) continue;
            // A fragment that OPENS with a conjunction, relative pronoun or preposition is the
            // tail of another clause, not a requirement of its own — "which are then part of the
            // value", "in ascending line order", "NOT separators" all reached the closure gate as
            // unconfirmable text. Dropping them loses a little recall and cannot wall a run;
            // keeping them can.
            if (p.toLowerCase(Locale.ROOT).matches(
                    "(?:which|that|where|when|while|whose|who|not|nor|or|but|so|then|than|if|unless|in|on|at|as|of|by|for|to|into|onto)\\b.*")) continue;
            kept.add(p);
        }
        return kept.isEmpty() ? List.of(s) : kept;
    }

    /**
     * Does what follows a separator START A NEW ITEM, rather than continue the current one?
     *
     * <p>A determiner is the signal: "…, the flight simulator itself" and "and a randomly generated
     * environment" each begin a fresh requirement, while "and newlines" and ", polygonic" continue
     * the phrase they belong to. One home for the test because both separators need it and a second
     * copy would drift ([[feedback-one-invariant-one-home]]).
     */
    private static boolean opensNewItem(String rest) {
        return rest.stripLeading().matches("(?is)(a|an|the|some)\\s.*");
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
        for (String sentence : sentences(specText)) {
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
    /**
     * The VERIFIABLE subset: only clauses that themselves carry an obligation verb. Two consumers,
     * two risk profiles — the planner readout (T4-DA) wants recall and is advisory, so a verbless
     * clause like "a simple cockpit HUD" belongs there; the CLOSURE edge (T4-DK) wants precision,
     * because an unconfirmable shard ("NOT separators", "one per line" — real extractor output,
     * found by running it over the three seeds) becomes a requirement the verifier can never
     * confirm, and the walling direction is the one failure this class must not have.
     */
    public static List<String> statedVerifiableIn(String specText) {
        List<String> strict = new ArrayList<>();
        for (String r : statedIn(specText)) {
            if (OBLIGATION.matcher(r).find()) strict.add(r);
        }
        return strict;
    }

    /** {@link #notAlreadyCovered} over the verifiable subset — the closure edge's entry point. */
    public static List<String> notAlreadyCoveredVerifiable(String specText, List<String> bankedCriteria) {
        List<String> stated = statedVerifiableIn(specText);
        if (stated.isEmpty() || bankedCriteria == null || bankedCriteria.isEmpty()) return stated;
        List<String> uncovered = new ArrayList<>();
        for (String requirement : stated) {
            if (bankedCriteria.stream().noneMatch(c -> overlaps(requirement, c))) uncovered.add(requirement);
        }
        return uncovered;
    }

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
