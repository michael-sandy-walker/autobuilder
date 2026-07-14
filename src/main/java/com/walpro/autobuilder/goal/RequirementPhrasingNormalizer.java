package com.walpro.autobuilder.goal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T2-EB: rewrites defect-statement goal descriptions into requirement form at creation time.
 *
 * <p>Analysis-spawned goals can carry descriptions that assert the current defect
 * ("&lt;endpoint&gt; does not return all matching records") rather than the required behaviour.
 * Downstream, {@code CriteriaVerificationService} treats a criteria-less goal's description as
 * its single requirement — a defect-statement phrasing inverts that check: the description can
 * never be confirmed against a fixed output, so the goal can never bank an observation and blocks
 * root closure through the T2-DR ledger.
 *
 * <p>The rewrite is deliberately conservative: only a main-clause negation is rewritten, and
 * only when the description is not already an imperative task or an existing requirement.
 * When in doubt the description is returned unchanged — a missed normalization costs an honest
 * non-confirm, while a wrong rewrite corrupts a requirement.
 */
public final class RequirementPhrasingNormalizer {

    private RequirementPhrasingNormalizer() {
    }

    /**
     * The exact placeholder the planner contract's ACCEPTANCE_CRITERIA rule tells the model to
     * emit when a subgoal is purely structural and has no observable output criterion. It is a
     * protocol sentinel, not a requirement: persisted as a criterion it can never be confirmed
     * against code and blocks root closure on every completion-verification call (run 4,
     * 2026-07-03 — first blocker in all 7 T4-BP-3 blocks). The single definition lives here;
     * the prompt renders it and {@code GoalPlannerService.setAcceptanceCriteria} drops it.
     */
    public static final String STRUCTURAL_CRITERION_PLACEHOLDER =
            "(subgoal is structural — no observable output criterion)";

    private static final String STRUCTURAL_PLACEHOLDER_NORMALIZED =
            normalizeForPlaceholderMatch(STRUCTURAL_CRITERION_PLACEHOLDER);

    /**
     * @return true when the criterion text is the structural placeholder — tolerating a leading
     * bullet, case differences, dash variants and whitespace noise, since the text round-trips
     * through the model.
     */
    public static boolean isStructuralCriterionPlaceholder(String criterion) {
        if (criterion == null || criterion.isBlank()) return false;
        return normalizeForPlaceholderMatch(criterion).equals(STRUCTURAL_PLACEHOLDER_NORMALIZED);
    }

    private static String normalizeForPlaceholderMatch(String text) {
        return text.strip()
                .replaceFirst("^[*\\-•]+\\s*", "")
                .toLowerCase()
                .replaceAll("[—–-]+", "-")
                .replaceAll("\\s+", " ");
    }

    /** Leading tag such as "[BUILD_FIX] " — ignored when locating the first word. */
    private static final Pattern BRACKET_PREFIX = Pattern.compile("^\\s*\\[[^\\]]+\\]\\s*");

    /**
     * Descriptions opening with one of these verbs are tasks ("Fix …", "Add …"), already
     * actionable and verifiable as "was this done" — never rewritten.
     */
    private static final Set<String> IMPERATIVE_STARTERS = Set.of(
            "add", "create", "implement", "ensure", "fix", "resolve", "write", "update",
            "make", "support", "return", "handle", "validate", "refactor", "remove",
            "delete", "rename", "move", "extract", "introduce", "configure", "register",
            "expose", "document", "test", "verify", "build", "provide", "extend", "wire",
            "migrate", "replace", "use", "apply", "define", "declare", "modify", "change",
            "correct");

    /**
     * A negation preceded by one of these words sits in a subordinate clause
     * ("returns 404 when the record does not exist") or inside an existing requirement
     * ("must not …", "cannot exceed …") — in both cases the description is already a
     * requirement and is left unchanged.
     */
    private static final Pattern PROTECTING_PREFIX = Pattern.compile(
            "(?i)\\b(?:when|if|where|unless|that|which|while|until|must|should|shall|cannot|can't|can not)\\b");

    /** Main-clause negation shapes whose modal rewrite is grammatically safe. */
    private static final List<Map.Entry<Pattern, String>> REWRITES = List.of(
            Map.entry(Pattern.compile("(?i)\\b(?:does not|do not|doesn't|don't|fails to|fail to)\\b"), "must"),
            Map.entry(Pattern.compile("(?i)\\b(?:is not|isn't|are not|aren't)\\b"), "must be"));

    /**
     * @return the description rewritten into requirement form, or the input unchanged when no
     * safe rewrite applies (also for {@code null}/blank input).
     */
    public static String normalize(String description) {
        if (description == null || description.isBlank()) {
            return description;
        }

        Matcher prefix = BRACKET_PREFIX.matcher(description);
        int bodyStart = prefix.find() ? prefix.end() : 0;
        String body = description.substring(bodyStart);

        String firstWord = body.stripLeading().split("[\\s:,.]+", 2)[0].toLowerCase();
        if (IMPERATIVE_STARTERS.contains(firstWord)) {
            return description;
        }

        Matcher earliest = null;
        String replacement = null;
        for (Map.Entry<Pattern, String> rewrite : REWRITES) {
            Matcher m = rewrite.getKey().matcher(body);
            if (m.find() && (earliest == null || m.start() < earliest.start())) {
                earliest = m;
                replacement = rewrite.getValue();
            }
        }
        if (earliest == null) {
            return description;
        }

        Matcher protecting = PROTECTING_PREFIX.matcher(body.substring(0, earliest.start()));
        if (protecting.find()) {
            return description;
        }

        String rewritten = body.substring(0, earliest.start()) + replacement + body.substring(earliest.end());
        return description.substring(0, bodyStart) + rewritten;
    }
}
