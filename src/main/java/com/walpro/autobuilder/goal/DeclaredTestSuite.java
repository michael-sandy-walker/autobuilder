package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.core.service.FileService;
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
    private final ObjectProvider<FileService> fileService;

    public DeclaredTestSuite(ObjectProvider<GoalPlannerService> goalPlanner, ObjectProvider<FileService> fileService) {
        this.goalPlanner = goalPlanner;
        this.fileService = fileService;
    }

    public boolean isDeclared() {
        GoalPlannerService planner = goalPlanner.getIfAvailable();
        FileService files = fileService.getIfAvailable();
        if (planner == null || files == null) return false;
        GoalEntity root = planner.getRootGoal();
        if (root == null) return false;
        try {
            if (!files.exists(SeedObligationsFile.PATH)) return false;
            return !SeedRequirements.answeredByGate(root.getDescription(),
                    files.readFile(SeedObligationsFile.PATH), SeedRequirements.GATE_TESTS).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
