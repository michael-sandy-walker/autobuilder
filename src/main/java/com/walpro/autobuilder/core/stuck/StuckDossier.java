package com.walpro.autobuilder.core.stuck;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * T4-DY dossier: the facts the loop already holds about a stuck case, gathered in one place for the model
 * to decide the next move from (user decision 2026-09-28: "several facts are gathered and sent to the
 * LLM, which helps with the decision").
 *
 * <p>Assembled deterministically; nothing here is a model's reading. Each fact carries where it comes
 * from, so the model can name the one its move relies on. The yardstick is never among them (it is the
 * operator's referee, not the loop's).
 *
 * @param source      the stuck kind the ledger recorded
 * @param signature   the stuck signature
 * @param why         why no question in the loop was left
 * @param occurrences how often the signature was observed
 * @param asked       the questions asked so far, each with its outcome
 * @param otherStuck  the other open stuck signatures of the run
 * @param health      the run's features outside the healthy runs' envelope so far (T4-CI phase 3)
 * @param subGoal     the current subgoal's description
 * @param criteria    its acceptance criteria, each with whether it is met
 * @param rejections  the reasons the last change was not merged
 * @param failingTests the project's failing tests at the last test run, with the row an invocation ran with
 * @param earlierMoves moves the dossier already chose for this signature, with what followed
 * @param verifyOnly  every criterion the subgoal still has unmet is a claim a CHECK answers (tests pass,
 *                    nothing was changed), so no change can satisfy the subgoal by itself
 */
public record StuckDossier(String source, String signature, String why, int occurrences, List<String> asked,
                           List<String> otherStuck, List<String> health, String subGoal, Map<String, Boolean> criteria,
                           List<String> rejections, List<String> failingTests, List<String> earlierMoves,
                           boolean verifyOnly) {

    /**
     * T4-DY: the subgoal is verify-only when it has unmet criteria and every one of them is a claim the
     * loop's own checks answer -- a guard over the existing tests ({@link com.walpro.autobuilder.core.build.TestRunClaims})
     * or that nothing was changed ({@link com.walpro.autobuilder.core.build.UnchangedClaims}). Such a subgoal
     * gives a change nothing to do: receiver-questions run 2026-09-28_21-17-47 stuck on "the existing tests
     * in src/test/java continue to pass without modification", every attempt was rejected as empty, and the
     * dossier's HINT told the loop to make "a trivial, valid change".
     */
    public static boolean verifyOnly(Map<String, Boolean> criteria) {
        if (criteria == null) return false;
        List<String> unmet = criteria.entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).toList();
        return !unmet.isEmpty() && unmet.stream().allMatch(c ->
                com.walpro.autobuilder.core.build.TestRunClaims.isGuardClaim(c)
                        || com.walpro.autobuilder.core.build.UnchangedClaims.isClaim(c));
    }

    /** The dossier as the model reads it. Empty sections are named as empty, so absence is a fact too. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("THE STUCK CASE\n");
        sb.append("  kind: ").append(source).append('\n');
        sb.append("  signature: ").append(signature).append('\n');
        sb.append("  observed ").append(occurrences).append(" time(s); ").append(why).append('\n');
        section(sb, "QUESTIONS ALREADY ASKED ABOUT IT", asked);
        section(sb, "MOVES ALREADY CHOSEN FOR IT, AND WHAT FOLLOWED", earlierMoves);
        section(sb, "OTHER OPEN STUCK SIGNATURES", otherStuck);
        section(sb, "THIS RUN SO FAR AGAINST HEALTHY RUNS (only what lies on the unhealthy side)", health);
        sb.append("CURRENT SUBGOAL\n  ").append(subGoal == null || subGoal.isBlank() ? "(none)" : subGoal).append('\n');
        List<String> crit = new ArrayList<>();
        if (criteria != null) criteria.forEach((c, met) -> crit.add((met ? "[met] " : "[not met] ") + c));
        section(sb, "ITS ACCEPTANCE CRITERIA", crit);
        if (verifyOnly) {
            sb.append("NOTE ON THIS SUBGOAL\n  Every criterion it still has unmet is a CHECK (tests pass, nothing was changed),"
                    + " not a change to make: no change of the code can satisfy it by itself.\n");
        }
        section(sb, "WHY THE LAST CHANGE WAS NOT MERGED", rejections);
        section(sb, "FAILING TESTS AT THE LAST TEST RUN", failingTests);
        return sb.toString().stripTrailing();
    }

    /** One line per fact, for the behaviour event: which sections carried something. */
    public List<String> factSummary() {
        List<String> out = new ArrayList<>();
        out.add("asked=" + size(asked));
        out.add("earlierMoves=" + size(earlierMoves));
        out.add("otherStuck=" + size(otherStuck));
        out.add("health=" + size(health));
        out.add("criteria=" + (criteria == null ? 0 : criteria.size())
                + " notMet=" + (criteria == null ? 0 : criteria.values().stream().filter(m -> !m).count()));
        out.add("rejections=" + size(rejections));
        out.add("failingTests=" + size(failingTests));
        out.add("verifyOnly=" + verifyOnly);
        return out;
    }

    private static int size(List<?> l) {
        return l == null ? 0 : l.size();
    }

    private static void section(StringBuilder sb, String title, List<String> lines) {
        sb.append(title).append('\n');
        if (lines == null || lines.isEmpty()) {
            sb.append("  (none)\n");
            return;
        }
        for (String l : lines) sb.append("  - ").append(l).append('\n');
    }
}
