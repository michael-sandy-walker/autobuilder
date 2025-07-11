package com.walpro.autobuilder.agent.evaluation.score;

import com.walpro.autobuilder.agent.evaluation.history.ScoreHistoryService;
import com.walpro.autobuilder.heuristic.HeuristicFinding;
import com.walpro.autobuilder.heuristic.MutationHeuristicAnalyzer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ScoringService {

    private final MutationHeuristicAnalyzer heuristicAnalyzer;
    private final ScoreHistoryService scoreHistoryService;

    public ScoringResult computeScores(
            String cycleName,
            Map<String, String> oldFiles,
            Map<String, String> newFiles,
            Set<String> deletedPaths
    ) {
        List<HeuristicFinding> findings = heuristicAnalyzer.analyze(oldFiles, newFiles, deletedPaths);
        double globalScore = heuristicAnalyzer.calculateScore(oldFiles, newFiles, deletedPaths, findings);

        Map<String, Double> perFileScores = newFiles.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            String path = entry.getKey();
                            Map<String, String> old = Map.of(path, oldFiles.getOrDefault(path, ""));
                            Map<String, String> now = Map.of(path, entry.getValue());
                            Set<String> deleted = deletedPaths.contains(path) ? Set.of(path) : Set.of();
                            return heuristicAnalyzer.calculateScore(old, now, deleted, findings);
                        }
                ));

        scoreHistoryService.recordScores(cycleName, perFileScores);
        scoreHistoryService.recordGlobalScore(cycleName, globalScore);

        Map<String, List<Double>> history = scoreHistoryService.getAllScoreHistories();
        Map<String, Double> deltas = scoreHistoryService.computeRecentDeltas();
        Optional<Double> lastScore = scoreHistoryService.getLastGlobalScore();

        return new ScoringResult(globalScore, lastScore.orElse(null), perFileScores, findings, history, deltas);
    }
}
