package com.walpro.autobuilder.core.build;

import java.util.Collection;
import java.util.List;

/**
 * T4-CQ: the project's own test run, as evidence for a reader judging requirements from source.
 *
 * <p>Run 2026-09-24_18-26-29 ended GOAL_EXHAUSTED on a complete artifact because a model reading
 * the source would not confirm "the whole test suite passes" -- it was asked to PREDICT seventeen
 * test outcomes while the test run had just observed them. This hands the reader what the run saw.
 *
 * <p><b>Bounded by the prompt, not by the suite.</b> A suite of thousands must not become thousands
 * of lines, so the block spends a character budget the caller derives from its own file budget, in
 * order of what a reader needs: the totals (always, one line -- enough on their own to answer
 * whether the suite passes); then each failing test with the row it ran with when it is one
 * invocation of a parameterized test, and its message (all of it, on one line, cropped at 400 characters); then the
 * per-test outcomes of the test classes whose SOURCE is already in the prompt, so a reader looking
 * at a test can see whether it ran and passed. Every other class is covered by the totals. Whatever
 * the budget cuts is counted, never dropped silently.
 */
public final class TestRunPromptBlock {

    private TestRunPromptBlock() {
    }

    public static String render(TestRunReport r, Collection<String> shownPaths, int budgetChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("PROJECT TEST RUN (the project's own suite, run on exactly these files):\n");
        sb.append("  ").append(r.run()).append(" run, ").append(r.failures()).append(" failed, ")
                .append(r.errors()).append(" errors, ").append(r.skipped()).append(" skipped -- the suite ")
                .append(r.passes() ? "PASSES" : "does NOT pass").append(".\n");
        int budget = Math.max(0, budgetChars - sb.length());
        if (!r.failing().isEmpty()) {
            sb.append("  Failing:\n");
            int shown = 0;
            for (TestRunReport.Failure f : r.failing()) {
                String line = "    " + simple(f.className()) + "." + f.method()
                        + (f.input() != null ? " -- ran with: " + crop(f.input()) : "") + " -- "
                        + (f.outcome() == TestRunReport.Outcome.ERROR ? "error: " : "failed: ")
                        + crop(f.message()) + "\n";
                if (line.length() > budget) break;
                sb.append(line);
                budget -= line.length();
                shown++;
            }
            if (shown < r.failing().size()) {
                sb.append("    ... and ").append(r.failing().size() - shown).append(" more failing, not listed\n");
            }
        }
        List<String> shownClasses = r.byClass().keySet().stream()
                .filter(cls -> shownPaths.stream().anyMatch(p -> p.replace('\\', '/')
                        .endsWith(cls.replace('.', '/') + ".java")))
                .toList();
        int omittedClasses = 0;
        for (String cls : shownClasses) {
            StringBuilder line = new StringBuilder("  ").append(simple(cls)).append(": ");
            List<TestRunReport.Case> cases = r.byClass().get(cls);
            for (int i = 0; i < cases.size(); i++) {
                if (i > 0) line.append(", ");
                line.append(cases.get(i).method()).append(' ').append(cases.get(i).outcome().name().toLowerCase());
            }
            line.append('\n');
            if (line.length() > budget) {
                omittedClasses++;
                continue;
            }
            sb.append(line);
            budget -= line.length();
        }
        if (omittedClasses > 0) {
            sb.append("  (per-test outcomes of ").append(omittedClasses)
                    .append(" shown test class(es) omitted for length; the totals above include them)\n");
        }
        return sb.toString();
    }

    private static String simple(String cls) {
        int i = cls.lastIndexOf('.');
        return i < 0 ? cls : cls.substring(i + 1);
    }

    private static String crop(String s) {
        if (s == null) return "";
        return s.length() > 400 ? s.substring(0, 400) + "..." : s;
    }
}
