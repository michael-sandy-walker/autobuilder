package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class EvaluationInterpreter {

    public InterpretationResult interpret(EvaluationOutcome outcome, Double lastScore) {
        double current = outcome.score();
        double previous = lastScore != null ? lastScore : -1.0;
        double delta = current - previous;

        boolean stagnation = Math.abs(delta) < 0.001 && current >= 0.95;
        boolean successful = current >= 0.9;

        if (stagnation) {
            log.warn("⚠️ Score stagnation detected: Last=%.2f → Now=%.2f (Δ=%.2f) — potential overrating"
                    .formatted(previous, current, delta));
            successful = false;
        } else {
            log.info("📊 Evaluation Score: Last=%.2f → Now=%.2f (Δ=%.2f)".formatted(previous, current, delta));
        }

        return new InterpretationResult(current, previous, delta, successful, stagnation);
    }

    public record InterpretationResult(
            double current,
            double previous,
            double delta,
            boolean success,
            boolean stagnation
    ) {}
}
