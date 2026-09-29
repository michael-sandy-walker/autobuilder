package com.walpro.autobuilder.core.build;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a criterion's WHOLE claim is that tests pass -- and whether a test run proves it.
 *
 * <p>stuck-questions run 2026-09-26_14-44: the completion verifier's prompt carried "PROJECT TEST RUN
 * ... 29 run, 0 failed -- the suite PASSES" with the rule that it is an observation, and the local model
 * still answered "StuckQuestionsSpecTest passes in full" with a cross. The root was refused, the refusal
 * became a work item, and the loop then "fixed" working code until the suite failed. A claim about a
 * test run has an authority -- the run -- and it should not rest on a reader that contradicted it.
 *
 * <p><b>Narrow on purpose, because this can only CONFIRM.</b> Only a criterion whose entire claim is a
 * passing test class or suite matches (a trailing parenthetical and "with no regressions" are allowed);
 * anything compound -- "passes and covers every route" -- is left to the reader. A named class must be
 * in the report with every case passed; the suite claim needs the whole run green. Nothing here ever
 * marks a criterion unmet.
 */
public final class TestRunClaims {

    private static final Pattern SUITE = Pattern.compile(
            "(?:the )?(?:whole |full |entire )?(?:project'?s? )?(?:own )?(?:test )?suite(?: under \\S+)? passes"
                    + "(?: in full)?(?: with no regressions)?|all tests pass(?: under \\S+)?");

    private static final Pattern CLASS = Pattern.compile(
            "(?:(?i:every test in |all tests in |the tests in ))?([A-Z][A-Za-z0-9_]*Tests?) pass(?:es)?(?: in full)?");

    /**
     * A parenthetical that only lists test classes, anywhere in the claim: "The full test suite (ATest,
     * BTest) passes" (receiver-questions run 2026-09-26_21-50-02 -- the loop's own wording, which neither
     * pattern matched, so the reader's wrong cross on a green run stood and the goal never closed). Each
     * class it names must have run green too, so the claim stays one this can only confirm.
     */
    private static final Pattern LISTED_CLASSES = Pattern.compile(
            "\\s*\\(((?:[A-Z][A-Za-z0-9_]*Tests?)(?:\\s*,\\s*(?:and\\s+)?[A-Z][A-Za-z0-9_]*Tests?)*)\\)");

    /**
     * The shape of a claim that tests keep passing, in the words planners write for a subgoal that only
     * guards what exists: "The existing tests in src/test/java continue to pass without modification"
     * (receiver-questions run 2026-09-28_21-17-47). Used to RECOGNISE such a claim ({@link #isClaim}),
     * never to prove one: the "without modification" half is a separate claim this class cannot confirm.
     */
    private static final Pattern CONTINUES = Pattern.compile(
            "(?:all |every |the )?(?:the )?(?:existing |current |project'?s? )?(?:own )?(?:unit )?tests?(?: (?:in|under) \\S+)?"
                    + " (?:continue to |still |keep |all )?pass(?:es|ing)?(?: without (?:modification|changes?|being (?:modified|changed)))?");

    private TestRunClaims() {
    }

    /** A guard's words: the tests are the ones that already exist, and they are to KEEP passing. */
    private static final Pattern GUARD = Pattern.compile("\\b(?:existing|current|continue|still|keep|without)\\b");

    /**
     * Whether {@code criterion} is, as a whole, a GUARD over the tests that exist -- "the existing tests
     * continue to pass without modification" -- a claim a test run answers and no change can satisfy by
     * itself. A bare "every test in QuillSpecTest passes" is NOT one: while those tests fail it is the work
     * itself (receiver-questions' own "every test in ReceiverQuestionsSpecTest passes"). Shape only.
     */
    public static boolean isGuardClaim(String criterion) {
        if (criterion == null || criterion.isBlank()) return false;
        String lower = normalise(criterion).toLowerCase(Locale.ROOT);
        return CONTINUES.matcher(lower).matches() && GUARD.matcher(lower).find();
    }

    private static String normalise(String criterion) {
        return LISTED_CLASSES.matcher(criterion.strip().replaceAll("`", "")).replaceAll("")
                .replaceAll("\\s*\\([^()]*\\)\\s*$", "")
                .replaceAll("[.\\s]+$", "");
    }

    /** True when {@code criterion} claims only that tests pass, and {@code report} shows exactly that. */
    public static boolean provenBy(String criterion, TestRunReport report) {
        if (criterion == null || report == null || !report.passes()) return false;
        String claim = criterion.strip().replaceAll("`", "");
        Matcher listed = LISTED_CLASSES.matcher(claim);
        List<String> named = new java.util.ArrayList<>();
        while (listed.find()) {
            for (String n : listed.group(1).split("\\s*,\\s*(?:and\\s+)?")) named.add(n.strip());
        }
        claim = LISTED_CLASSES.matcher(claim).replaceAll("")
                .replaceAll("\\s*\\([^()]*\\)\\s*$", "")   // "(all test methods green)"
                .replaceAll("[.\\s]+$", "");
        for (String n : named) {
            if (!ranGreen(n, report)) return false;
        }
        if (SUITE.matcher(claim.toLowerCase(Locale.ROOT)).matches()) return true;
        Matcher m = CLASS.matcher(claim);
        if (!m.matches()) return false;
        return ranGreen(m.group(1), report);
    }

    private static boolean ranGreen(String simple, TestRunReport report) {
        for (Map.Entry<String, List<TestRunReport.Case>> e : report.byClass().entrySet()) {
            String cls = e.getKey();
            if (!cls.equals(simple) && !cls.endsWith("." + simple)) continue;
            return !e.getValue().isEmpty()
                    && e.getValue().stream().allMatch(c -> c.outcome() == TestRunReport.Outcome.PASSED);
        }
        return false;
    }
}
