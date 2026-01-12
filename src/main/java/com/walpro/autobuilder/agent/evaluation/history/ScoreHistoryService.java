package com.walpro.autobuilder.agent.evaluation.history;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

public interface ScoreHistoryService {
    void recordScores(String cycleId, Map<String, Double> scores);
    List<Double> getScoreHistory(String filePath);
    Map<String, Double> computeRecentDeltas();
    Map<String, List<Double>> getAllScoreHistories();
    void recordGlobalScore(String cycleName, double score);
    Optional<Double> getLastGlobalScore();
    void recordGoalEvaluation(String cycleId, String goalId, double score);
    void recordGoalAcceptance(String cycleId, String goalId, double score);
    OptionalDouble getLastGoalEvaluation(String goalId);
    OptionalDouble getLastAcceptedGoalScore(String goalId);
}
