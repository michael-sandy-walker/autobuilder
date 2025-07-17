package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class EvaluationInterpreter {
	
	public InterpretationResult interpret(EvaluationOutcome outcome, Double lastScore, boolean fingerprintRepeated) {
	    double current = outcome.score();
	    double previous = lastScore != null ? lastScore : -1.0;
	    double delta = current - previous;

	    GoalProgressState rawGoalProgress = outcome.goalProgress() != null
	        ? outcome.goalProgress()
	        : GoalProgressState.NOT_EVALUATED;

	    GoalProgressState finalGoalProgress = rawGoalProgress;

	    // Detect numerical stagnation even if LLM didn’t say STAGNATING
	    if (rawGoalProgress == GoalProgressState.NOT_EVALUATED
	            && Math.abs(delta) < 0.001 && current >= 0.95) {
	        finalGoalProgress = GoalProgressState.STAGNATING;
	        log.warn("⚠️ Heuristic STAGNATION: score delta ≈ 0 and high (%.2f → %.2f, Δ=%.5f)"
	                .formatted(previous, current, delta));
	    } else {
	        log.info("📊 Evaluation Score: Last=%.2f → Now=%.2f (Δ=%.2f)".formatted(previous, current, delta));
	    }

	    boolean successful = current >= 0.75 && finalGoalProgress != GoalProgressState.STAGNATING;

	    if (finalGoalProgress == GoalProgressState.STAGNATING) {
	        successful = false;
	        log.warn("⚠️ GoalProgress is STAGNATING — success = false");
	    }

	    return new InterpretationResult(
	        current,
	        previous,
	        delta,
	        successful,
	        outcome.mutationState(),
	        fingerprintRepeated,
	        finalGoalProgress
	    );
	}


    public record InterpretationResult(
            double current,
            double previous,
            double delta,
            boolean success,
            MutationAcceptanceState mutationState,
            boolean fingerprintRepeated,
            GoalProgressState goalProgress
    ) {}
}
