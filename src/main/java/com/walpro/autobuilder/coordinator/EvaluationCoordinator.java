package com.walpro.autobuilder.coordinator;

import com.walpro.autobuilder.agent.evaluation.EvaluationAgent;
import com.walpro.autobuilder.agent.evaluation.history.ScoreHistoryService;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationInput;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.heuristic.HeuristicFinding;
import com.walpro.autobuilder.heuristic.MutationHeuristicAnalyzer;
import com.walpro.autobuilder.model.CycleContextData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class EvaluationCoordinator {

    private final EvaluationAgent evaluationAgent;
    private final MutationHeuristicAnalyzer heuristicAnalyzer;
    private final ScoreHistoryService scoreHistoryService;

    public CycleContextData evaluate(CycleContextData context) {
        var changed = context.evolutionOutcome().addedOrChangedFiles();
        var deleted = context.evolutionOutcome().deletedFiles();

        Map<String, String> newFiles = changed.stream()
                .collect(Collectors.toMap(f -> f.path(), f -> f.content()));

        Map<String, String> oldFiles = context.previousOutput(); // from last snapshot
        Set<String> deletedPaths = Set.copyOf(deleted);

        // Heuristic + score
        List<HeuristicFinding> findings = heuristicAnalyzer.analyze(oldFiles, newFiles, deletedPaths);
        double score = heuristicAnalyzer.calculateScore(oldFiles, newFiles, deletedPaths, findings);

        // Per-file scoring
        Map<String, Double> perFileScores = newFiles.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> {
                    Map<String, String> old = Map.of(e.getKey(), oldFiles.getOrDefault(e.getKey(), ""));
                    Map<String, String> now = Map.of(e.getKey(), e.getValue());
                    Set<String> del = deletedPaths.contains(e.getKey()) ? Set.of(e.getKey()) : Set.of();
                    return heuristicAnalyzer.calculateScore(old, now, del, findings);
                }));

        scoreHistoryService.recordScores(context.cycleName(), perFileScores);

        Map<String, List<Double>> history = scoreHistoryService.getAllScoreHistories();
        Map<String, Double> deltas = scoreHistoryService.computeRecentDeltas();

        EvaluationInput input = new EvaluationInput(
                score,
                context.evaluationOutcome() != null ? context.evaluationOutcome().score() : null,
                changed.stream().map(f -> f.path()).collect(Collectors.toSet()),
                deletedPaths,
                context.currentGoalDescription(),
                oldFiles,
                findings,
                history,
                deltas
        );



        EvaluationOutcome outcome = evaluationAgent.evaluate(input);

        evaluationAgent.evolveGraph(input, outcome);

        double previous = input.lastScore() != null ? input.lastScore() : -1.0;
        double current = outcome.score();
        double delta = current - previous;

        if (Math.abs(delta) < 0.001 && current >= 0.95) {
            log.warn("⚠️ Score stagnation detected: Last=%.2f → Now=%.2f (Δ=%.2f) — potential overrating".formatted(previous, current, delta));
        } else {
            log.info("📊 Evaluation Score: Last=%.2f → Now=%.2f (Δ=%.2f)".formatted(previous, current, delta));
        }
        context = context.withEvaluationOutcome(outcome);

        return context.withEvaluationFeedback(outcome.feedbackMessage());
    }
}
