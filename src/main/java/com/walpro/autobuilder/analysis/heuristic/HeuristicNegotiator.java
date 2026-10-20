package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.analysis.heuristic.strategy.score.ScoreAdjustmentStrategy;
import com.walpro.autobuilder.analysis.heuristic.strategy.score.WeightedAverageScoreAdjustmentStrategy;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.config.HeuristicConfigProperties;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class HeuristicNegotiator {

    private final ScoreAdjustmentStrategy scoreAdjustmentStrategy;

    /** T5-H: optional behaviour trace logger. */
    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    /** T6-T: a veto recognised as a grind abstains instead of deciding. */
    @Setter
    @Autowired(required = false)
    private VetoGrind vetoGrind;

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

    public NegotiationResult negotiate(CycleContextData input, EvaluationOutcome original, List<EvaluationHeuristic> heuristics) {

        // T6-T: the opinions of heuristics whose veto is a measurement of the candidate -- never silenced by a veto grind.
        Set<HeuristicOpinion> measured = Collections.newSetFromMap(new IdentityHashMap<>());
        List<HeuristicOpinion> opinions = heuristics.stream()
                .map(h -> {
                    HeuristicOpinion o = h.evaluate(input, original);
                    o = o != null ? o : HeuristicOpinion.none(h.getClass().getSimpleName()); // guard if we have a null opinion
                    if (h.measured()) measured.add(o);
                    return o;
                })
                .filter(o -> o.level() != ConfidenceLevel.NONE)
                // T6-T: a veto recognised as a grind no longer decides -- it abstains, and the evaluator's verdict stands
                // unless another heuristic objects. Demoted to a vote it still won alone (rule-extraction run
                // 2026-10-18_19-33-28: the only other opinion was LOW, so a 0.9 REJECTED vote carried every time).
                .map(o -> o.level() == ConfidenceLevel.CRITICAL && vetoGrind != null && !measured.contains(o)
                        && vetoGrind.yielded(VetoGrind.finding(o))
                        ? new HeuristicOpinion(o.name(), o.confidence(), ConfidenceLevel.LOW, MutationAcceptanceState.NOT_EVALUATED,
                                GoalProgressState.NOT_EVALUATED, "(abstains: this veto kept deciding while the work did not move) "
                                + o.rationale(), null, false, o.defect())
                        : o)
                .toList();

        if (!opinions.isEmpty()) {
            log.info("Heuristic Opinions:\n" +
                    opinions.stream()
                            .map(o -> "• %s [%s | %.2f] — %s".formatted(o.name(), o.level(), o.confidence(), o.rationale()))
                            .collect(Collectors.joining("\n")));
        }

        if (behaviourLogger != null) {
            String mutationId = input.lastEvolutionFingerprint();
            String cycleId = input.cycleName();
            opinions.forEach(o -> behaviourLogger.heuristicResult(
                    cycleId,
                    mutationId,
                    o.name(),
                    o.suggestedScore(),
                    o.mutationState() != null ? o.mutationState().name() : null,
                    o.confidence()
            ));
        }

        List<HeuristicOpinion> criticalOpinions = opinions.stream()
                .filter(o -> o.level() == ConfidenceLevel.CRITICAL)
                .toList();

        // IMPORTANT: preserve null score (do not invent / default).
        Double adjustedScore = (original.score() == null)
                ? null
                : scoreAdjustmentStrategy.adjustScore(original, opinions);

        // 1. CRITICAL overrules everything (but never "erase" states via NOT_EVALUATED/null).
        // Tiebreak between competing CRITICALs: a defect finding (deterministic structural
        // defect of the mutation itself) outranks confidence — run 14 (2026-07-04) showed the
        // deterministic score echo crossing the CRITICAL threshold on confidence alone (1.0)
        // and outranking StoreCoherence's correct defect rejection (0.9) twice; both mutations
        // merged runtime-fatal. Plain criticals keep the max-confidence rule, preserving the
        // designed procedural accepts (BuildFixAcceptance's defer-to-post-merge-gate at 1.0).
        Optional<HeuristicOpinion> critical = criticalOpinions.stream()
                .max(Comparator.comparing(HeuristicOpinion::defectFinding)
                        .thenComparingDouble(HeuristicOpinion::confidence));

        if (vetoGrind != null) {
            Optional<HeuristicOpinion> veto = critical.filter(o -> o.mutationState() == MutationAcceptanceState.REJECTED);
            vetoGrind.decided(input == null ? null : input.cycleName(), veto.map(VetoGrind::finding).orElse(null),
                    veto.map(measured::contains).orElse(false));
        }

        if (critical.isPresent()) {
            HeuristicOpinion o = critical.get();

            MutationAcceptanceState ms = coalesceState(o.mutationState(), original.mutationState());
            GoalProgressState gp = coalesceState(o.goalProgress(), original.goalProgress());

            return new NegotiationResult(
                    new EvaluationOutcome(
                            adjustedScore,
                            ms,
                            gp,
                            "CRITICAL: Heuristic '%s' overruled (%.2f): %s".formatted(o.name(), o.confidence(), o.rationale()),
                            original.recommendation()
                    ),
                    criticalOpinions,
                    opinions
            );
        }

        // 2. Two or more HIGHs with consistent override (but never "erase" states)
        List<HeuristicOpinion> high = opinions.stream()
                .filter(o -> o.level() == ConfidenceLevel.HIGH)
                .toList();

        if (high.size() >= 2) {
            boolean agree = high.stream()
                    .map(HeuristicOpinion::mutationState)
                    .filter(ms -> !isAbstain(ms))
                    .distinct().count() == 1;

            if (agree) {
                HeuristicOpinion top = high.stream()
                        .max(Comparator.comparingDouble(HeuristicOpinion::confidence))
                        .get();

                MutationAcceptanceState ms = coalesceState(top.mutationState(), original.mutationState());
                GoalProgressState gp = coalesceState(top.goalProgress(), original.goalProgress());

                return new NegotiationResult(
                        new EvaluationOutcome(
                                adjustedScore,
                                ms,
                                gp,
                                "Combined HIGH opinions triggered override by '%s': %s".formatted(top.name(), top.rationale()),
                                original.recommendation()
                        ),
                        criticalOpinions,
                        opinions
                );
            }
        }

        // 3. Default: weighted votes, where NOT_EVALUATED/null = abstain (so we preserve LLM states)
        MutationAcceptanceState votedMutationState = weightedMajorityVote(
                opinions.stream()
                        .map(o -> Map.entry(o.mutationState(), o.confidence()))
                        .toList(),
                original.mutationState()
        );

        GoalProgressState votedGoalProgress = weightedMajorityVote(
                opinions.stream()
                        .map(o -> Map.entry(o.goalProgress(), o.confidence()))
                        .toList(),
                original.goalProgress()
        );

        MutationAcceptanceState finalMutationState = coalesceState(votedMutationState, original.mutationState());
        GoalProgressState finalGoalProgress = coalesceState(votedGoalProgress, original.goalProgress());

        return new NegotiationResult(
                new EvaluationOutcome(
                        adjustedScore,
                        finalMutationState,
                        finalGoalProgress,
                        original.feedbackMessage(),
                        original.recommendation()
                ),
                criticalOpinions,
                opinions
        );
    }

    private static boolean isAbstain(Object state) {
        if (state == null) return true;
        if (state instanceof MutationAcceptanceState ms) return ms == MutationAcceptanceState.NOT_EVALUATED;
        if (state instanceof GoalProgressState gp) return gp == GoalProgressState.NOT_EVALUATED;
        return false;
    }

    private static MutationAcceptanceState coalesceState(MutationAcceptanceState candidate, MutationAcceptanceState fallback) {
        return (candidate == null || candidate == MutationAcceptanceState.NOT_EVALUATED) ? fallback : candidate;
    }

    private static GoalProgressState coalesceState(GoalProgressState candidate, GoalProgressState fallback) {
        return (candidate == null || candidate == GoalProgressState.NOT_EVALUATED) ? fallback : candidate;
    }

    private <T> T weightedMajorityVote(List<Map.Entry<T, Double>> entries, T fallback) {
        if (entries == null || entries.isEmpty()) return fallback;

        // Filter abstentions (null / NOT_EVALUATED) so they cannot override the original LLM state.
        List<Map.Entry<T, Double>> effective = entries.stream()
                .filter(e -> !isAbstain(e.getKey()))
                .toList();

        if (effective.isEmpty()) return fallback;

        record Stat(double sum, int count) {
            double avg() { return count == 0 ? 0.0 : sum / count; }
        }

        Map<T, Stat> grouped = effective.stream()
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