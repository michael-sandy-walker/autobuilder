package com.walpro.autobuilder.goal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Whether the operator declared the project's own test suite an authority for this goal -- a
 * {@code gate: tests} line in {@code bootstrap/seed.obligations.txt} belonging to the root's seed.
 *
 * <p>One home for the question: the staged-test heuristic asks it before judging a mutation by its
 * own failing tests, and the evaluator asks it before being shown the test run. Which gate answers
 * which line is the operator's declaration, never inferred from wording.
 */
@Component
public class DeclaredTestSuite {

    private final ObjectProvider<GoalPlannerService> goalPlanner;

    public DeclaredTestSuite(ObjectProvider<GoalPlannerService> goalPlanner) {
        this.goalPlanner = goalPlanner;
    }

    public boolean isDeclared() {
        GoalPlannerService planner = goalPlanner.getIfAvailable();
        if (planner == null) return false;
        GoalEntity root = planner.getRootGoal();
        if (root == null) return false;
        try {
            return !planner.requirements().answeredByGate(SeedRequirements.GATE_TESTS).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
