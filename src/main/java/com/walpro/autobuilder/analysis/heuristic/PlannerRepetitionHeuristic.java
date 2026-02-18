package com.walpro.autobuilder.analysis.heuristic;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.planner.PlannerActionSnapshot;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PlannerRepetitionHeuristic implements EvaluationHeuristic {

    private static final String NAME = "PlannerRepetitionHeuristic";
    private static final int LOOKBACK = 12;

    private final AgentGraphService agentGraphService;

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome original) {
        if (input == null || original == null) return HeuristicOpinion.none(NAME);

        String scopeGoalId = (input.currentSubGoalId() != null && !input.currentSubGoalId().isBlank())
                ? input.currentSubGoalId()
                : input.currentGoalId();

        if (scopeGoalId == null || scopeGoalId.isBlank()) return HeuristicOpinion.none(NAME);

        List<PlannerActionSnapshot> recent = agentGraphService.findRecentPlannerActions(scopeGoalId, LOOKBACK);
        if (recent == null || recent.size() < 3) return HeuristicOpinion.none(NAME);

        List<String> fps = recent.stream()
                .map(PlannerActionSnapshot::fingerprint)
                .filter(Objects::nonNull)
                .toList();

        if (fps.size() < 3) return HeuristicOpinion.none(NAME);

        int total = fps.size();
        int unique = (int) fps.stream().distinct().count();

        double repetitionDensity = 1.0 - ((double) unique / (double) total);
        int maxRun = maxConsecutiveRun(fps);

        double runFactor = (total <= 1) ? 0.0 : (double) (Math.max(0, maxRun - 1)) / (double) (total - 1);
        double severity = clamp01(0.65 * repetitionDensity + 0.35 * runFactor);

        if (severity < 0.35) return HeuristicOpinion.none(NAME);

        // Prevent this heuristic from overriding a strong "PROGRESSING" claim on its own.
        double confidence = severity;
        if (original.goalProgress() == GoalProgressState.STAGNATING) {
            confidence = Math.max(confidence, 0.70);
        } else {
            confidence = Math.min(confidence, 0.55);
        }

        String rationale = "Planner fingerprints repeat in scope '%s' (recent=%d, unique=%d, repetition=%.2f, maxRun=%d)."
                .formatted(scopeGoalId, total, unique, repetitionDensity, maxRun);

        // We do not reject mutations because the planner repeats; we only flag stagnation risk.
        MutationAcceptanceState mutationState = original.mutationState() != null
                ? original.mutationState()
                : MutationAcceptanceState.ACCEPTED;

        return HeuristicOpinion.of(
                NAME,
                confidence,
                mutationState,
                GoalProgressState.STAGNATING,
                rationale
        );
    }

    private static int maxConsecutiveRun(List<String> fps) {
        int best = 1;
        int run = 1;

        for (int i = 1; i < fps.size(); i++) {
            if (Objects.equals(fps.get(i), fps.get(i - 1))) {
                run++;
                if (run > best) best = run;
            } else {
                run = 1;
            }
        }

        return best;
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}