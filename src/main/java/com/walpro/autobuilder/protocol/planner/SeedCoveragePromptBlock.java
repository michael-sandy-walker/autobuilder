package com.walpro.autobuilder.protocol.planner;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.SeedRequirements;

import java.util.ArrayList;
import java.util.List;

/**
 * T4-DA: which of the specification's own obligations no subgoal currently addresses.
 *
 * <p>Nothing ever asked whether the plan, taken together, still solves the seed. Subgoals are
 * added, given up, subsumed and consolidated one at a time, and each of those decisions is locally
 * reasonable — but no step compares the resulting SET against the specification, and the planner
 * is the agent that could act on the answer and was never told. Run 2026-08-28_05-27-49 is the
 * cost: 23 given up, 15 subsumed, and a HUD the seed asks for plainly that no surviving subgoal
 * still covered.
 *
 * <p>Deliberately a readout for the PLANNER specifically, which is the one agent whose output is
 * subgoals — the same relay shape T4-BX uses for rejection reasons. It states the gap and does not
 * prescribe a subgoal: what to add, and whether to add one at all, stays the planner's decision.
 *
 * <p><b>Absent when the plan covers the seed</b>, so a healthy run pays nothing — the lean
 * conditional shape the prompt audit favours over an unconditional payload.
 */
public final class SeedCoveragePromptBlock {

    private SeedCoveragePromptBlock() {}

    public static String render(CycleContextData input) {
        if (input == null || input.currentGoalDescription() == null) return null;

        List<String> planTexts = new ArrayList<>();
        collect(input.remainingGoals(), planTexts);
        collect(input.completedGoals(), planTexts);

        List<String> uncovered = SeedRequirements.notAlreadyCovered(
                input.currentGoalDescription(), planTexts);
        if (uncovered.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("--- SPECIFICATION REQUIREMENTS NO SUBGOAL COVERS ---\n");
        sb.append("These are stated by the goal specification itself. No remaining or completed subgoal\n");
        sb.append("addresses them, so on the current plan they will not be built. Judge each one: it may\n");
        sb.append("need a subgoal, or it may already be satisfied by work described in other words.\n");
        for (String requirement : uncovered) {
            sb.append("  - ").append(requirement).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private static void collect(List<GoalEntity> goals, List<String> into) {
        if (goals == null) return;
        for (GoalEntity g : goals) {
            if (g == null) continue;
            if (g.getDescription() != null) into.add(g.getDescription());
            Object raw = g.getMetadata() != null ? g.getMetadata().get("acceptanceCriteria") : null;
            if (raw instanceof List<?> criteria) {
                for (Object c : criteria) if (c != null) into.add(c.toString());
            }
        }
    }
}
