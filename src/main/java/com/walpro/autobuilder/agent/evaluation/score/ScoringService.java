package com.walpro.autobuilder.agent.evaluation.score;

import com.walpro.autobuilder.agent.evaluation.history.ScoreHistoryService;
import com.walpro.autobuilder.diff.DiffReport;
import com.walpro.autobuilder.diff.DiffService;
import com.walpro.autobuilder.diff.DiffType;
import com.walpro.autobuilder.heuristic.HeuristicFinding;
import com.walpro.autobuilder.heuristic.MutationHeuristicAnalyzer;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Getter
public class ScoringService {

    private final MutationHeuristicAnalyzer heuristicAnalyzer;
    private final ScoreHistoryService scoreHistoryService;
    private final DiffService diffService;


    public ScoringResult computeScores(
            String cycleName,
            String goalId,
            Map<String, String> oldFiles,
            Map<String, String> newFiles,
            Set<String> deletedPaths
    ) {
        List<HeuristicFinding> findings = heuristicAnalyzer.analyze(oldFiles, newFiles, deletedPaths);
        DiffReport diffReport = diffService.compareInMemory(oldFiles, newFiles, deletedPaths);

        Map<String, Double> perFileScores = newFiles.keySet().stream()
            .collect(Collectors.toMap(
                path -> path,
                path -> calculateFileScore(path, findings, diffReport)
            ));

        double globalScore = perFileScores.values().stream()
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.75);

        scoreHistoryService.recordScores(cycleName, perFileScores);
        scoreHistoryService.recordGlobalScore(cycleName, globalScore);

        Map<String, List<Double>> history = scoreHistoryService.getAllScoreHistories();
        Map<String, Double> deltas = scoreHistoryService.computeRecentDeltas();
        Optional<Double> lastScore = scoreHistoryService.getLastGlobalScore();

        return new ScoringResult(globalScore, lastScore.orElse(null), perFileScores, findings, history, deltas, goalId);
    }

    private double calculateFileScore(String path, List<HeuristicFinding> findings, DiffReport diffReport) {
        double score = 0.75; // neutral baseline

        // Apply heuristic penalties/rewards
        for (HeuristicFinding finding : findings) {
            if (path.equals(finding.path())) {
                score += heuristicImpact(finding);
            }
        }

        // Adjust based on diff magnitude (lines changed)
        int linesAdded = diffReport.getLinesAdded(path);
        int linesDeleted = diffReport.getLinesDeleted(path);
        int totalChanged = linesAdded + linesDeleted;

        if (totalChanged > 50) {
            score -= 0.15; // Major change, higher risk
        } else if (totalChanged > 20) {
            score -= 0.05; // Moderate change
        } else if (totalChanged <= 5) {
            score += 0.05; // Minor or trivial change reward
        }

        // File change type impact
        DiffType fileChangeType = diffReport.getDiffType(path);
        switch (fileChangeType) {
            case ADDED -> score += 0.05;       // small bonus for adding new functionality
            case DELETED -> score -= 0.15;     // significant penalty for deletion (potential risk)
            case MODIFIED -> score += 0.0;     // neutral
            default -> {}
        }

        return clamp(score, 0.0, 1.0);
    }

    private double heuristicImpact(HeuristicFinding finding) {
        return switch (finding.severity()) {
            case CRITICAL -> -0.25;
            case WARNING -> -0.10;
            case INFO -> -0.02;
        };
    }

    private double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

}
