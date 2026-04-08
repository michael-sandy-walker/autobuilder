package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;

import java.util.List;

/**
 * Wraps the structured {@link EvaluationOutcome} together with the per-criterion pass/fail
 * assessments parsed from the {@code CriteriaCheck:} block in the raw LLM response (T3-AZ).
 * The assessments are lines of the form {@code [N] ✓ <criterion>} or {@code [N] ✗ <criterion> — <reason>}.
 */
public record ParsedEvaluation(EvaluationOutcome outcome, List<String> criteriaAssessments) {

    public ParsedEvaluation {
        criteriaAssessments = criteriaAssessments == null ? List.of() : criteriaAssessments;
    }
}
