package com.walpro.autobuilder.lifecycle;

import com.walpro.autobuilder.persistence.CycleContext;
import com.walpro.autobuilder.model.SubgoalMetadata;

public class CycleContextMapper {

    public static CycleContext from(CycleContextData data) {
        double score = data.evaluationOutcome() != null ? data.evaluationOutcome().score() : -1.0;
        String comment = data.evaluationFeedback() != null ? data.evaluationFeedback() : "(no feedback)";
        SubgoalMetadata meta = data.currentSubGoalId() != null
                ? SubgoalMetadata.of(data.currentSubGoalId(), data.currentSubGoalDescription())
                : SubgoalMetadata.of(null, data.currentGoalDescription());

        return CycleContext.of(
                data.cycleName(),
                score,
                comment,
                data.merged(),
                meta
        );
    }

}
