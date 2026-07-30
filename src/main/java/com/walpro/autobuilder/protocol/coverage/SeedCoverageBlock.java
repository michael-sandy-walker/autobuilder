package com.walpro.autobuilder.protocol.coverage;

import com.walpro.autobuilder.analysis.coverage.SeedCoverageReadout;

import java.util.List;

/**
 * T4-DA: renders the seed-coverage readout into the {@code --- SPECIFICATION COVERAGE ---} block.
 * <p>
 * Shared by planner, evolution and analysis so all three see the same statement of how far the
 * plan as a whole still resembles the specification. This block is the deliverable of T4-DA: the
 * comparator exists so the agents are told, not so a gate can fire.
 * <p>
 * Enumerated, name-anchored lines — the clause verbatim, the absent terms beside it — rather than
 * a payload dump, per the T4-AP prompt audit's finding on what earns its tokens.
 */
public final class SeedCoverageBlock {

    public enum Audience { PLANNER, EVOLUTION, ANALYSIS }

    /**
     * How much of the prompt the findings may occupy, in characters. The budget lives here and
     * not in the analyzer: the analyzer reports what it found, and the surface that has to pay
     * for it decides how much it can afford. Findings arrive ordered by how far the plan already
     * engages with each clause, so truncation drops the least-engaged first.
     * <p>
     * Sized against the real specification: its sixteen clauses have never produced more than
     * thirteen findings, which render inside this budget whole.
     */
    private static final int FINDING_BUDGET_CHARS = 1600;

    private SeedCoverageBlock() {}

    /** @return the rendered block, or {@code null} when there is nothing to say. */
    public static String render(SeedCoverageReadout readout, Audience audience) {
        if (readout == null || readout.isEmpty()) return null;

        StringBuilder sb = new StringBuilder("--- SPECIFICATION COVERAGE ---\n");
        sb.append("How far the CURRENT PLAN, taken as a whole, still resembles the specification. ")
          .append("Derived by comparing the specification's own wording against every work item and ")
          .append("acceptance criterion, whatever its status.\n")
          .append("This describes the PLAN, not the code: a requirement implemented inside a broadly ")
          .append("worded work item still appears here, because nothing in the plan names it. Treat a ")
          .append("line as a prompt to check, not as proof of a defect.\n");

        sb.append(switch (audience) {
            case PLANNER -> "Use this before decomposing further: a specification clause no work item "
                    + "articulates is the strongest candidate for the next one.\n";
            case EVOLUTION -> "Use this to widen a change that would otherwise satisfy its work item "
                    + "while leaving a stated requirement unwritten.\n";
            case ANALYSIS -> "Use this to judge whether the plan has drifted from the specification, "
                    + "as distinct from whether individual work items are progressing.\n";
        });

        int spent = appendSection(sb, "NOT ARTICULATED BY ANY WORK ITEM",
                readout.unclaimed(), FINDING_BUDGET_CHARS, true);
        appendSection(sb, "ARTICULATED BUT NO ACCEPTANCE CRITERION WOULD PROVE IT",
                readout.unprovable(), FINDING_BUDGET_CHARS - spent, spent == 0);
        return sb.toString();
    }

    /**
     * @param mayOverflowForOneLine whether a single finding larger than the whole budget should
     *                              still be rendered. True only while the block is still empty:
     *                              truncating to nothing would make "the check found something"
     *                              read exactly like "the check never ran". Once the first
     *                              section has spent the budget, the second must stay silent
     *                              rather than overrun it.
     * @return characters spent on findings, so both sections share one budget.
     */
    private static int appendSection(StringBuilder sb, String heading,
                                     List<SeedCoverageReadout.Finding> findings, int budget,
                                     boolean mayOverflowForOneLine) {
        if (findings == null || findings.isEmpty() || budget <= 0) return 0;

        StringBuilder section = new StringBuilder();
        int spent = 0;
        int omitted = 0;
        for (SeedCoverageReadout.Finding f : findings) {
            String line = "  - \"" + f.clause().trim() + "\""
                    + (f.missingTerms().isEmpty() ? ""
                            : "\n      absent from the plan: " + String.join(", ", f.missingTerms()))
                    + "\n";
            boolean firstAndAllowedToOverflow = spent == 0 && mayOverflowForOneLine;
            if (spent + line.length() > budget && !firstAndAllowedToOverflow) {
                omitted++;
                continue;
            }
            section.append(line);
            spent += line.length();
        }
        if (section.isEmpty()) return 0;

        sb.append("\n").append(heading).append(":\n").append(section);
        if (omitted > 0) {
            // Say so rather than trailing off — a reader must be able to tell a complete list from
            // a truncated one, which is the distinction the abstention logging exists to preserve.
            sb.append("  (").append(omitted)
              .append(" further clause(s) omitted here, least-engaged first)\n");
        }
        return spent;
    }
}
