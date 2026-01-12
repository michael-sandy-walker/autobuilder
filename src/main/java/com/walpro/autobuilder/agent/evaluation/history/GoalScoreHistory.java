package com.walpro.autobuilder.agent.evaluation.history;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Getter
public class GoalScoreHistory {
    private final List<Double> allEvaluatedScores = new ArrayList<>();
    private Double lastAcceptedScore = null;
    private Double lastEvaluatedScore = null;

    public void recordEvaluation(double score) {
        lastEvaluatedScore = score;
        allEvaluatedScores.add(score);
    }

    public void recordAccepted(double score) {
        lastAcceptedScore = score;
    }

    public List<Double> getAllScores() {
        return Collections.unmodifiableList(allEvaluatedScores);
    }
}
