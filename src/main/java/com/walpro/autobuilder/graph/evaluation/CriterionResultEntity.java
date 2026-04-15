package com.walpro.autobuilder.graph.evaluation;

/**
 * T3-BB: Represents a single per-criterion pass/fail result from one evaluation cycle,
 * to be persisted as a {@code CriterionResult} node in Neo4j linked from its parent
 * {@code EvaluationAction} via {@code HAS_CRITERION}.
 */
public record CriterionResultEntity(
        String actionId,
        int criterionIndex,
        String criterionText,
        boolean passed,
        String cycleId,
        String subgoalId
) {}
