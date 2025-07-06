package com.walpro.autobuilder.agent.evaluation.history;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class InMemoryScoreHistoryService implements ScoreHistoryService {

    private final Map<String, List<Double>> historyByFile = new HashMap<>();

    @Override
    public void recordScores(String cycleId, Map<String, Double> scores) {
        scores.forEach((path, score) -> {
            historyByFile.computeIfAbsent(path, k -> new ArrayList<>()).add(score);
        });
    }

    @Override
    public List<Double> getScoreHistory(String filePath) {
        return historyByFile.getOrDefault(filePath, List.of());
    }

    @Override
    public Map<String, Double> computeRecentDeltas() {
        Map<String, Double> deltas = new HashMap<>();
        for (Map.Entry<String, List<Double>> entry : historyByFile.entrySet()) {
            List<Double> scores = entry.getValue();
            if (scores.size() >= 2) {
                double delta = scores.get(scores.size() - 1) - scores.get(scores.size() - 2);
                deltas.put(entry.getKey(), delta);
            }
        }
        return deltas;
    }

    @Override
    public Map<String, List<Double>> getAllScoreHistories() {
        return Collections.unmodifiableMap(historyByFile);
    }
}
