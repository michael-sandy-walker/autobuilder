package com.walpro.autobuilder.agent.evaluation.score;

import com.walpro.autobuilder.agent.evaluation.history.ScoreHistoryService;
import com.walpro.autobuilder.analysis.heuristic.HeuristicFinding;
import com.walpro.autobuilder.analysis.heuristic.MutationHeuristicAnalyzer;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Getter
public class ScoringService {

    private final MutationHeuristicAnalyzer heuristicAnalyzer;
    private final ScoreHistoryService scoreHistoryService;

    public ScoringResult computeScores(
            String cycleName,
            String goalId,
            Map<String, String> oldFiles,
            Map<String, String> newFiles,
            Set<String> deletedPaths
    ) {
        List<HeuristicFinding> findings = heuristicAnalyzer.analyze(oldFiles, newFiles, deletedPaths);

        Map<String, Double> perFileScores = heuristicAnalyzer.scorePerFile(oldFiles, newFiles, deletedPaths, findings);

        // IMPORTANT: global score must reflect the actual per-file scores
        double avgFromFiles = perFileScores.values().stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.75);

        // IMPORTANT: Usually, this is the DefaultMutationHeuristicAnalyzer instead of the default interface method
        double globalHeuristic = heuristicAnalyzer.scoreGlobal(oldFiles, newFiles, deletedPaths, findings);

        // Weighted blend (tune weights or drop globalHeuristic entirely)
        double globalScore = clamp(0.85 * avgFromFiles + 0.15 * globalHeuristic, 0.0, 1.0);

        scoreHistoryService.recordScores(cycleName, perFileScores);
        scoreHistoryService.recordGlobalScore(cycleName, globalScore);

        // Keep goal events optional here (or move acceptance recording to the acceptance decision point)
        if (goalId != null && !goalId.isBlank()) {
            scoreHistoryService.recordGoalEvaluation(cycleName, goalId, globalScore);
        }

        Map<String, List<Double>> history = scoreHistoryService.getAllScoreHistories();
        Map<String, Double> deltas = scoreHistoryService.computeRecentDeltas();
        Optional<Double> lastScore = scoreHistoryService.getLastGlobalScore();

        return new ScoringResult(globalScore, lastScore.orElse(null), perFileScores, findings, history, deltas, goalId);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
