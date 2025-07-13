package com.walpro.autobuilder.agent.evaluation.heuristic;

import com.walpro.autobuilder.agent.evaluation.heuristic.strategy.score.ScoreAdjustmentStrategy;
import com.walpro.autobuilder.agent.evaluation.heuristic.strategy.score.WeightedAverageScoreAdjustmentStrategy;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationInput;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.config.HeuristicConfigProperties;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Component
public class HeuristicNegotiator {

    private final ScoreAdjustmentStrategy scoreAdjustmentStrategy;

    public HeuristicNegotiator(HeuristicConfigProperties config, ApplicationContext context) {
        this.scoreAdjustmentStrategy = context.getBean(config.getScoreStrategy(), ScoreAdjustmentStrategy.class);
    }

    // Secondary constructor for testing
    public HeuristicNegotiator(ScoreAdjustmentStrategy strategy) {
        this.scoreAdjustmentStrategy = strategy;
    }

    // Fallback constructor
    public HeuristicNegotiator() {
        this.scoreAdjustmentStrategy = new WeightedAverageScoreAdjustmentStrategy();
    }


    public EvaluationOutcome negotiate(EvaluationInput input, EvaluationOutcome original, List<EvaluationHeuristic> heuristics) {
        List<HeuristicOpinion> opinions = heuristics.stream()
                .map(h -> h.evaluate(input, original))
                .filter(o -> o.level() != ConfidenceLevel.NONE)
                .toList();

        if (!opinions.isEmpty()) {
        	log.info("📋 Heuristic Opinions:\n" +
        			opinions.stream()
        	.map(o -> "• %s [%s | %.2f] — %s".formatted(o.name(), o.level(), o.confidence(), o.rationale()))
        	.collect(Collectors.joining("\n")));
        }

        double adjustedScore = scoreAdjustmentStrategy.adjustScore(original, opinions);

        // 1. CRITICAL overrules everything
        Optional<HeuristicOpinion> critical = opinions.stream()
                .filter(o -> o.level() == ConfidenceLevel.CRITICAL)
                .max(Comparator.comparingDouble(HeuristicOpinion::confidence));

        if (critical.isPresent()) {
            HeuristicOpinion o = critical.get();
            return new EvaluationOutcome(
                    adjustedScore,
                    o.mutationState(),
                    o.goalProgress(),
                    "⚠️ CRITICAL: Heuristic '%s' overruled (%.2f): %s".formatted(o.name(), o.confidence(), o.rationale()),
                    original.recommendation()
            );
        }

        // 2. Two or more HIGHs with consistent override
        List<HeuristicOpinion> high = opinions.stream()
                .filter(o -> o.level() == ConfidenceLevel.HIGH)
                .toList();

        if (high.size() >= 2) {
            boolean agree = high.stream()
                    .map(HeuristicOpinion::mutationState)
                    .distinct().count() == 1;

            if (agree) {
                HeuristicOpinion top = high.stream()
                        .max(Comparator.comparingDouble(HeuristicOpinion::confidence))
                        .get();

                return new EvaluationOutcome(
                        adjustedScore,
                        top.mutationState(),
                        top.goalProgress(),
                        "⚠️ Combined HIGH opinions triggered override by '%s': %s".formatted(top.name(), top.rationale()),
                        original.recommendation()
                );
            }
        }
        
        MutationAcceptanceState newMutationState = weightedMajorityVote(
                opinions.stream().map(o -> Map.entry(o.mutationState(), o.confidence())).toList(),
                original.mutationState()
        );

        GoalProgressState newGoalProgress = weightedMajorityVote(
                opinions.stream().map(o -> Map.entry(o.goalProgress(), o.confidence())).toList(),
                original.goalProgress()
        );

        // 3. No override — apply adjusted score, keep original states
        return new EvaluationOutcome(
                adjustedScore,
                newMutationState,
                newGoalProgress,
                original.feedbackMessage(),
                original.recommendation()
        );
    }
    
    private <T> T weightedMajorityVote(List<Map.Entry<T, Double>> entries, T fallback) {
        if (entries.isEmpty()) return fallback;

        // Group entries by option and calculate average confidence per group
        record Stat(double sum, int count) {
            double avg() { return count == 0 ? 0.0 : sum / count; }
        }

        Map<T, Stat> grouped = entries.stream()
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey,
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                list -> {
                                    double sum = list.stream().mapToDouble(Map.Entry::getValue).sum();
                                    return new Stat(sum, list.size());
                                }
                        )
                ));

        // Sort by average confidence
        List<Map.Entry<T, Stat>> sorted = grouped.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue().avg(), a.getValue().avg()))
                .toList();

        if (sorted.size() < 2) {
            double avg = sorted.get(0).getValue().avg();
            return avg >= 0.6 ? sorted.get(0).getKey() : fallback;
        }

        Map.Entry<T, Stat> top = sorted.get(0);
        Map.Entry<T, Stat> second = sorted.get(1);

        double topAvg = top.getValue().avg();
        double secondAvg = second.getValue().avg();

        boolean strongEnough = topAvg >= 0.6;
        boolean clearMargin = (topAvg - secondAvg) >= 0.15;

        return (strongEnough && clearMargin) ? top.getKey() : fallback;
    }

}
