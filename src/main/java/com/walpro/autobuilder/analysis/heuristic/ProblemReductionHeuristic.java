package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.problem.ProblemContext;
import com.walpro.autobuilder.analysis.problem.ProblemEntry;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ProblemReductionHeuristic implements EvaluationHeuristic {

    private static final String NAME = "ProblemReductionHeuristic";

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome original) {
        if (input == null || original == null) return HeuristicOpinion.none(NAME);

        if (original.mutationState() != MutationAcceptanceState.ACCEPTED) {
            return HeuristicOpinion.none(NAME);
        }

        // A) score improvement?
        boolean scoreImproved = false;
        if (input.lastScore() != null && original.score() != null) {
            double delta = original.score() - input.lastScore();
            scoreImproved = delta > 0.000; // strict; adjust later if needed
        }

        // B) goal progress?
        GoalProgressState gp = original.goalProgress();
        boolean progressAdvanced =
                gp == GoalProgressState.PROGRESSING
                        || gp == GoalProgressState.FULFILLED;

        // C) problem reduction?
        boolean problemsReduced = reducedOpenProblemsInScope(input);

        if (scoreImproved || progressAdvanced || problemsReduced) {
            return HeuristicOpinion.none(NAME);
        }

        String goalId = input.currentGoalId();
        String subGoalId = input.currentSubGoalId();

        String rationale = "ACCEPTED without justification: no score improvement, no goal progress, and open problems did not decrease"
                + " (scope goal=" + safe(goalId) + " sub=" + safe(subGoalId) + ").";

        return HeuristicOpinion.of(
                NAME,
                0.85,
                MutationAcceptanceState.REJECTED,
                GoalProgressState.STAGNATING,
                rationale
        );
    }

    private static boolean reducedOpenProblemsInScope(CycleContextData ctx) {
        ProblemContext prev = ctx.previousProblemContext();
        ProblemContext now = ctx.problemContext();

        String goalId = ctx.currentGoalId();
        String subGoalId = ctx.currentSubGoalId();

        int before = countOpenInScope(prev, goalId, subGoalId);
        int after = countOpenInScope(now, goalId, subGoalId);

        // If we have no baseline, we cannot claim reduction.
        if (prev == null) return false;

        return after < before;
    }

    private static int countOpenInScope(ProblemContext pc, String goalId, String subGoalId) {
        if (pc == null || pc.open() == null) return 0;

        List<ProblemEntry> open = pc.open();
        int c = 0;
        for (ProblemEntry p : open) {
            if (p == null) continue;
            if (p.matchesScope(goalId, subGoalId)) c++;
        }
        return c;
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "(none)" : s.trim();
    }
}