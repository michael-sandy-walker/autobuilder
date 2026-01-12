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
        double globalScore = heuristicAnalyzer.scoreGlobal(oldFiles, newFiles, deletedPaths, findings);

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
}
