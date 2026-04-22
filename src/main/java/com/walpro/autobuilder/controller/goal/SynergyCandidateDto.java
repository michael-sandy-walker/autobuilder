package com.walpro.autobuilder.controller.goal;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * T4-AK: a cross-goal synergy candidate — a criterion on another subgoal that is
 * semantically similar to a criterion on the source subgoal. Surfaces merge-once
 * opportunities where one implementation artefact may satisfy criteria on multiple
 * sibling or cousin subgoals simultaneously.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SynergyCandidateDto(
        String sourceGoalId,
        int sourceCriterionIndex,
        String sourceCriterionText,
        String targetGoalId,
        String targetGoalDescription,
        String targetGoalStatus,
        int targetCriterionIndex,
        String targetCriterionText,
        double similarity
) {}
