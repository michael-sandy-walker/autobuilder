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
    /**
     * T4-DJ: strips an invented FILE PATH from a criterion that is otherwise about a file existing.
     *
     * <p>The seed states behaviour, not layout. When the planner writes
     * {@code "CockpitHUD.java exists at src/main/java/com/flightsim/ui/CockpitHUD.java"} it has
     * invented the path, and the loop can then contradict itself: on run 2026-08-28_05-27-49 an
     * analysis-authored constraint required every file under {@code .../model/}, the analysis agent
     * flagged the conflict itself as HIGH PACKAGE_MISMATCH, and the mutation was scored 0.42 and
     * REJECTED with {@code [1] ✓ Create CockpitHUD.java} / {@code [2] ✗ … File is located at
     * src/main/java/com/flightsim/model/ui/CockpitHUD.java}. **The file was correct and was
     * discarded for being where the loop's other rule required it to be.**
     *
     * <p>Keeping the file and dropping the path resolves the contradiction in the direction the
     * specification supports: it still demands the file exist, and stops asserting a location
     * nothing stated. Conservative — only a path that names the SAME file the criterion is about
     * is removed, so "writes its output to /var/log/app.log" (a real behavioural path) is untouched.
     */
    /**
     * T3-BZ: rewrites references to a file the loop has just RELOCATED.
     *
     * <p>WHY THIS EXISTS. The placement mover relocates a type and repairs the source tree, but
     * the loop's own bookkeeping keeps pointing at where the type used to be. Measured in the
     * field: a banked criterion reading <i>"A Java class `Book` exists at
     * `src/main/java/com/example/library/domain/Book.java`"</i> survived the move that made it
     * false, so the loop was then steered by, and checked against, a statement its own repair had
     * invalidated. Three of three runs in which the mover acted scored below the ceiling; ten of
     * eleven in which it did not were perfect.
     *
     * <p>Rewrites the file path and the fully-qualified type name, both of which the move makes
     * false and neither of which is ambiguous. Nothing else is touched: prose about a package may
     * legitimately describe several types, and guessing there would trade one false statement for
     * another.
     */
    public static String rewriteRelocatedReferences(String text, String oldPath, String newPath) {
        if (text == null || text.isBlank() || oldPath == null || newPath == null) return text;
        if (oldPath.equals(newPath)) return text;
        String out = text.replace(oldPath, newPath);
        String oldFqn = fullyQualifiedName(oldPath);
        String newFqn = fullyQualifiedName(newPath);
        if (oldFqn != null && newFqn != null && !oldFqn.equals(newFqn)) {
            // The trailing boundary matters for the same reason it does in source: without it
            // a.b.Book also matches the front of a.b.BookRepository and renames a type nobody
            // asked about.
            out = out.replaceAll(Pattern.quote(oldFqn) + "(?![\\w$])", Matcher.quoteReplacement(newFqn));
        }
        return out;
    }

    /** {@code src/main/java/a/b/Book.java} -> {@code a.b.Book}; null when the path is not a source file. */
    private static String fullyQualifiedName(String path) {
        if (path == null || !path.endsWith(".java")) return null;
        String p = path.substring(0, path.length() - ".java".length());
        for (String root : java.util.List.of("src/main/java/", "src/test/java/")) {
            int i = p.indexOf(root);
            if (i >= 0) {
                p = p.substring(i + root.length());
                return p.replace('/', '.');
            }
        }
        return null;
    }

    public static String stripInventedFilePath(String criterion) {
        if (criterion == null || criterion.isBlank()) return criterion;
        Matcher m = FILE_AT_PATH.matcher(criterion);
        if (!m.find()) return criterion;
        String file = m.group(1);
        String path = m.group(3);
        // Only when the path names that same file — otherwise it is about something else entirely.
        if (!path.endsWith("/" + file) && !path.equals(file)) return criterion;
        return (criterion.substring(0, m.start()) + file + " " + m.group(2)
                + criterion.substring(m.end())).replaceAll("\\s{2,}", " ").strip();
    }

    /** {@code <File>.java exists|is created|is located at <path>} — the shape the planner writes. */
    private static final Pattern FILE_AT_PATH = Pattern.compile(
            "([A-Za-z_$][\\w$]*\\.java)\\s+(exists|is created|is located|is present)\\s+(?:at|in|under)\\s+"
                    + "([\\w./\\-]+\\.java)");

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
