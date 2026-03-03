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
import java.util.OptionalDouble;
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

        OptionalDouble avgFromFilesOpt = perFileScores.values().stream()
                .filter(v -> v != null && Double.isFinite(v))
                .mapToDouble(Double::doubleValue)
                .average();

        Double avgFromFiles = avgFromFilesOpt.isPresent() ? clamp(avgFromFilesOpt.getAsDouble(), 0.0, 1.0) : null;

        // global heuristic is only meaningful if we have any file-level basis
        Double globalHeuristic = null;
        if (avgFromFiles != null) {
            double gh = heuristicAnalyzer.scoreGlobal(oldFiles, newFiles, deletedPaths, findings);
            globalHeuristic = Double.isFinite(gh) ? clamp(gh, 0.0, 1.0) : null;
        }

        // Weighted blend only if we have a basis
        Double globalScore = null;
        if (avgFromFiles != null) {
            double base = avgFromFiles;
            if (globalHeuristic != null) {
                base = clamp(0.85 * avgFromFiles + 0.15 * globalHeuristic, 0.0, 1.0);
            }
            globalScore = base;
        }

        // Persist score history only if we actually have a score
        scoreHistoryService.recordScores(cycleName, perFileScores);
        if (globalScore != null) {
            scoreHistoryService.recordGlobalScore(cycleName, globalScore);
        }

        // Graph-derived history/deltas (we can later narrow scope if needed)
        Map<String, List<Double>> history = scoreHistoryService.getAllScoreHistories();
        Map<String, Double> deltas = scoreHistoryService.computeRecentDeltas();
        Optional<Double> lastGlobalScore = scoreHistoryService.getLastGlobalScore();

        // Goal-aware “previous” scores (do NOT record events here; CycleScoreRecorder does that once acceptance is known)
        Double lastAcceptedGoalScore = null;
        Double lastGoalEvaluationScore = null;

        if (goalId != null && !goalId.isBlank()) {
            OptionalDouble accepted = scoreHistoryService.getLastAcceptedGoalScore(goalId);
            if (accepted.isPresent()) lastAcceptedGoalScore = accepted.getAsDouble();

            OptionalDouble eval = scoreHistoryService.getLastGoalEvaluation(goalId);
            if (eval.isPresent()) lastGoalEvaluationScore = eval.getAsDouble();
        }

        return new ScoringResult(
                globalScore,
                lastGlobalScore.orElse(null),
                lastAcceptedGoalScore,
                lastGoalEvaluationScore,
                perFileScores,
                findings,
                history,
                deltas,
                goalId
        );
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}