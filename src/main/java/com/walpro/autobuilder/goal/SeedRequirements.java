package com.walpro.autobuilder.goal;

import lombok.extern.slf4j.Slf4j;

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
@Slf4j
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

    /**
     * T6-S: the obligations written down for this specification, or the rule's split when none are.
     *
     * <p><b>The file is an INPUT, the rule is a fallback.</b> Where {@code bootstrap/seed.obligations.txt}
     * exists and belongs to this specification, its lines ARE the obligations — reviewed, correctable,
     * and identical on every run. Where it does not, the rule below still runs and nothing changes,
     * so a target without a file behaves exactly as before.
     *
     * <p>The verifiable filter still applies to the file's lines. A written obligation can be
     * unverifiable ("Work inside the project as it is") just as a derived one can, and the closure
     * edge's precision requirement does not soften because a human approved the wording.
     */
    public static List<String> obligationsOrSplit(String specText, String obligationsFileText) {
        return withoutRuleMarkers(written(specText, obligationsFileText, () -> statedIn(specText)));
    }

    /**
     * T4-CQ: marks a written obligation as a RULE about how the work is done rather than a property
     * the result can be checked for ("A test must assert what the route actually does", "Work inside
     * the project as it is"). A rule still guides the work -- it reaches every consumer that reads
     * obligations, without the marker -- but it never blocks completion: nothing can execute it, and
     * the first adopted project's seventh run halted with a finished artifact because a model would
     * not confirm one. The file is already the operator's to correct; this is one more correction
     * it can carry.
     */
    public static final String RULE_MARKER = "rule:";

    static boolean isRule(String line) {
        return line != null && line.strip().toLowerCase(java.util.Locale.ROOT).startsWith(RULE_MARKER);
    }

    /**
     * T4-CQ: marks a written obligation as ANSWERED BY A GATE -- {@code gate: tests} -- so closure
     * takes that gate's verdict instead of a model's reading of the source. Run 2026-09-24_18-27
     * refused closure because a model reading the code would not confirm "The whole test suite
     * passes" while the test gate had just run that suite green: a runtime fact put to the one
     * reader that cannot observe it. Which gate answers which line is the operator's declaration,
     * like {@link #RULE_MARKER}, never inferred from the wording.
     */
    public static final String GATE_MARKER = "gate:";

    /** The one gate a line may name today: the project's own test suite. */
    public static final String GATE_TESTS = "tests";

    /** The gate a line names, or null when it names none. */
    static String gateOf(String line) {
        if (line == null) return null;
        String l = line.strip();
        if (!l.toLowerCase(java.util.Locale.ROOT).startsWith(GATE_MARKER)) return null;
        String rest = l.substring(GATE_MARKER.length()).strip();
        int sp = rest.indexOf(' ');
        return (sp < 0 ? rest : rest.substring(0, sp)).toLowerCase(java.util.Locale.ROOT);
    }

    private static String withoutRuleMarker(String line) {
        if (isRule(line)) return line.strip().substring(RULE_MARKER.length()).strip();
        String gate = gateOf(line);
        if (gate != null) {
            String rest = line.strip().substring(GATE_MARKER.length()).strip();
            return rest.substring(Math.min(rest.length(), gate.length())).strip();
        }
        return line;
    }

    /** The written obligations a named gate answers, markers removed. Empty without a file. */
    public static List<String> answeredByGate(String specText, String obligationsFileText, String gate) {
        if (obligationsFileText == null || obligationsFileText.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String r : written(specText, obligationsFileText, List::of)) {
            if (gate.equals(gateOf(r))) out.add(withoutRuleMarker(r));
        }
        return out;
    }

    private static List<String> withoutRuleMarkers(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) out.add(withoutRuleMarker(l));
        return out;
    }

    private static List<String> written(String specText, String obligationsFileText,
                                        java.util.function.Supplier<List<String>> fallback) {
        if (obligationsFileText != null && !obligationsFileText.isBlank()) {
            var parsed = SeedObligationsFile.read(obligationsFileText, specText);
            var written = SeedObligationsFile.obligationsFrom(parsed);
            if (written.isPresent()) {
                log.info("T6-S: using the {} obligations written in {} (state={})",
                        written.get().size(), SeedObligationsFile.PATH, parsed.state());
                return written.get();
            }
            log.info("T6-S: {} is {} — falling back to the derived split until it is regenerated",
                    SeedObligationsFile.PATH, parsed.state());
        }
        return fallback.get();
    }

    /**
     * The written obligations, or the CLAUSE split — the coverage readout's fallback.
     *
     * <p>⚠ The two fallbacks are not interchangeable and a first cut conflated them. `clausesOf`
     * yields every clause (35 on the clitarget seed) because coverage asks what the plan has not
     * engaged with at all; `statedIn` yields the stated requirements (12) because the closure edge
     * asks what must be met. Falling back to the wrong one silently narrows whichever consumer
     * inherits it — caught by `SeedCoverageAnalyzerTest`, which noticed clauses had stopped being
     * reported as unclaimed.
     */
    public static List<String> obligationsOrClauses(String specText, String obligationsFileText) {
        return withoutRuleMarkers(written(specText, obligationsFileText, () -> clausesOf(specText)));
    }

    /**
     * {@link #obligationsOrSplit} narrowed to the clauses carrying an obligation verb -- unless the
     * written obligations already mark their rules.
     *
     * <p>T4-CQ: the verb list is a proxy for "this clause states something checkable", fitted to
     * prose. A file whose author used the {@code rule:} marker has made that split by hand, and the
     * proxy then overrules them: on the first adopted project all three unmarked lines ("Every route
     * ... is covered by a test", "The whole test suite passes", "Add tests under src/test/java")
     * carry none of its verbs, so closure had nothing to verify and run 2026-09-24_18-13-42 closed
     * GOAL_FULFILLED with three of seven routes untested. A file without the marker keeps the proxy,
     * so model-written files on the other targets are judged exactly as before.
     */
    public static List<String> verifiableObligationsOrSplit(String specText, String obligationsFileText) {
        List<String> obligations = written(specText, obligationsFileText, () -> statedIn(specText));
        boolean authorMarkedRules = obligations.stream().anyMatch(l -> isRule(l) || gateOf(l) != null);
        List<String> strict = new ArrayList<>();
        for (String r : obligations) {
            if (isRule(r)) continue;   // guides the work, never blocks completion
            if (GATE_TESTS.equals(gateOf(r))) continue;   // the test gate's verdict, not a reading
            if (gateOf(r) != null) {
                // An unknown gate cannot answer anything: kept as an ordinary obligation, so a typo
                // can only add a question at closure, never remove one.
                r = withoutRuleMarker(r);
            }
            if (authorMarkedRules || OBLIGATION.matcher(r).find()) strict.add(r);
        }
        return strict;
    }

    /** {@link #notAlreadyCoveredVerifiable} over the written obligations when they exist. */
    public static List<String> notAlreadyCoveredVerifiable(String specText, List<String> bankedCriteria,
                                                           String obligationsFileText) {
        return uncovered(verifiableObligationsOrSplit(specText, obligationsFileText), bankedCriteria, true);
    }

    /**
     * T4-CQ: a requirement over EVERY member of a set is never covered by a criterion about one of
     * them. Word overlap cannot see the quantifier: "Every route the application serves is covered
     * by a test" shared enough words with "a test for GET /basic/add ..." to count as covered, and
     * the first adopted project's eighth run closed GOAL_FULFILLED with three of seven routes
     * untested. So a universal requirement always goes to the verifier. The direction is safe: it
     * can only add a question at closure, never skip one.
     */
    static boolean isUniversal(String requirement) {
        return requirement != null && UNIVERSAL.matcher(requirement).find();
    }

    private static final Pattern UNIVERSAL = Pattern.compile("(?i)\\b(every|each|all)\\b");

    /** The verifiable requirements the spec states — the closure edge's entry point. */
    public static List<String> notAlreadyCoveredVerifiable(String specText, List<String> bankedCriteria) {
        return uncovered(statedVerifiableIn(specText), bankedCriteria, false);
    }

    /**
     * The stated requirements no banked criterion overlaps; all of them when nothing is banked.
     * With {@code universalNeverCovered}, a requirement over every member ({@link #isUniversal})
     * stays uncovered whatever overlaps it.
     */
    static List<String> uncovered(List<String> stated, List<String> bankedCriteria, boolean universalNeverCovered) {
        if (stated.isEmpty() || bankedCriteria == null || bankedCriteria.isEmpty()) return stated;
        List<String> uncovered = new ArrayList<>();
        for (String requirement : stated) {
            if ((universalNeverCovered && isUniversal(requirement))
                    || bankedCriteria.stream().noneMatch(c -> overlaps(requirement, c))) uncovered.add(requirement);
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
