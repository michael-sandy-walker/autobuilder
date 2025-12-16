package com.walpro.autobuilder.core.engine;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import com.walpro.autobuilder.core.coordinator.AgentCoordinator;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SequentialCoordinationStrategy implements CoordinationStrategy {

    /** Internal bias to promote evaluative runs after low scores or stagnation. */
    private double evaluationBias = 0.0;

    @Override
    public List<ExecutionAgent> determineExecutionOrder(List<ExecutionAgent> executionAgents,
                                                          CycleContextData context) {

        // Adjusted ordering based on current priorities (and adaptive bias)
        return executionAgents.stream()
                .sorted(Comparator.comparingDouble(agent -> adjustedPriority(agent, context)))
                .collect(Collectors.toList());
    }

    private double adjustedPriority(ExecutionAgent agent, CycleContextData context) {
        double base = agent.coordinator().priority(context);

        // Apply an adaptive bias — only to evaluation coordinator
        if ("evaluation".equalsIgnoreCase(agent.coordinator().role())) {
            base -= evaluationBias;
        }
        return base;
    }

    @Override
    public CycleContextData adaptAfterCycle(CycleContextData context) {
        if (context.evaluationOutcome() == null) return context;

        double currentScore = context.evaluationOutcome().score();
        double lastScore = context.lastScore() != null ? context.lastScore() : currentScore;
        double delta = currentScore - lastScore;

        // If the score dropped or failed to improve, raise bias (evaluation runs earlier next time)
        if (delta < 0 || currentScore < 0.5) {
            evaluationBias = Math.min(evaluationBias + 0.1, 1.0);
            log.debug("Evaluation underperformed (score={}, Δ={}) → ↑bias={}", currentScore, delta, evaluationBias);
        }
        // If the system improves steadily, lower the bias again
        else if (delta > 0.05 && currentScore > 0.6) {
            evaluationBias = Math.max(evaluationBias - 0.05, 0.0);
            log.debug("Evaluation improved (score={}, Δ={}) → ↓bias={}", currentScore, delta, evaluationBias);
        }

        return context.withEvaluationBias(evaluationBias);
    }
}