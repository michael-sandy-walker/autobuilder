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
 */
public record StuckDossier(String source, String signature, String why, int occurrences, List<String> asked,
                           List<String> otherStuck, List<String> health, String subGoal, Map<String, Boolean> criteria,
                           List<String> rejections, List<String> failingTests, List<String> earlierMoves) {

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
