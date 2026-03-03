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
		GoalProgressState rawGoalProgress = outcome.goalProgress() != null
				? outcome.goalProgress()
				: GoalProgressState.NOT_EVALUATED;

		if (outcome.score() == null) {
			log.warn("EvaluationOutcome.score is null — treating evaluation as unsuccessful.");
			return new InterpretationResult(
					Double.NaN,
					lastScore,
					null,
					false,
					outcome.mutationState(),
					fingerprintRepeated,
					rawGoalProgress
			);
		}

	    double current = outcome.score();
	    Double delta = lastScore == null ? null : current - lastScore;

	    GoalProgressState finalGoalProgress = rawGoalProgress;

	    // Detect numerical stagnation even if LLM didn’t say STAGNATING
	    if (rawGoalProgress == GoalProgressState.NOT_EVALUATED
                && delta != null && (Math.abs(delta) < 0.001 && current >= 0.95)) {
	        finalGoalProgress = GoalProgressState.STAGNATING;
	        log.warn("Heuristic STAGNATION: score delta ≈ 0 and high (%.2f → %.2f, Δ=%.5f)"
	                .formatted(lastScore, current, delta));
	    } else {
	        log.info("Evaluation Score: Last=%s → Now=%.2f (Δ=%s)".formatted(lastScore, current, delta));
	    }

	    boolean successful = current >= 0.75 && finalGoalProgress != GoalProgressState.STAGNATING;

	    if (finalGoalProgress == GoalProgressState.STAGNATING) {
	        successful = false;
	        log.warn("GoalProgress is STAGNATING — success = false");
	    }

	    return new InterpretationResult(
	        current,
			lastScore,
	        delta,
	        successful,
	        outcome.mutationState(),
	        fingerprintRepeated,
	        finalGoalProgress
	    );
	}


    public record InterpretationResult(
            double current,
            Double previous,
            Double delta,
            boolean success,
            MutationAcceptanceState mutationState,
            boolean fingerprintRepeated,
            GoalProgressState goalProgress
    ) {}
}
