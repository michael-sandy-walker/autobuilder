package com.walpro.autobuilder.protocol.rules;

import java.util.List;

/**
 * T4-EK slice (c): the lines a project's obligations mark {@code rule:} that its specification does not itself
 * contain, handed to the agents that plan and write changes.
 *
 * <p>A {@code rule:} line says HOW work on a project is done. Where the specification is a text someone wrote, its
 * rules are sentences of it and the agents read them there. A seed confirmed from a reverse-engineered account states
 * only WHAT the project does; the rules confirmed with it live in the obligations alone, and this block is how they
 * reach the agents that plan and write changes. Who gets the block is decided where the context is built
 * ({@code CycleContextCoordinator}); a project with no rules to hand over gets none.
 */
public final class WorkRulesBlock {

    /** How much of a prompt the rules may take, in characters; the ones past it are counted, not dropped silently. */
    static final int BUDGET_CHARS = 2400;

    private WorkRulesBlock() {
    }

    /** @return the block, or null when there is no rule to hand over. */
    public static String render(List<String> rules) {
        if (rules == null || rules.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("--- HOW WORK ON THIS PROJECT IS DONE ---\n");
        sb.append("Stated for this project by whoever is responsible for it. These lines are not requirements to build "
                + "and nothing checks them: they say how any change here is to be made. Follow them in what you plan and "
                + "write.\n");
        int spent = 0, omitted = 0;
        for (String r : rules) {
            if (r == null || r.isBlank()) continue;
            String line = "  - " + r.strip() + "\n";
            if (spent > 0 && spent + line.length() > BUDGET_CHARS) {
                omitted++;
                continue;
            }
            sb.append(line);
            spent += line.length();
        }
        if (spent == 0) return null;
        if (omitted > 0) sb.append("  (").append(omitted).append(" further line(s) omitted here)\n");
        return sb.toString();
    }
}
