package com.walpro.autobuilder.agent.merge.model;

import com.walpro.autobuilder.model.SubgoalMetadata;

import java.util.Set;

public record FileVariant(
        String content,
        Set<SubgoalMetadata> subgoals,
        String originCycleId,
        Double evaluationScore
) {}
