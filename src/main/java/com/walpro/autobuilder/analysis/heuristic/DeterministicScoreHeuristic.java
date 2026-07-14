package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringResult;
import com.walpro.autobuilder.analysis.problem.ProblemContext;
import com.walpro.autobuilder.analysis.problem.ProblemSeverity;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;

@Slf4j
@Component
public class DeterministicScoreHeuristic implements EvaluationHeuristic {

    private static final String NAME = "DeterministicScoreHeuristic";
    // T4-J Phase 2: tunable as autobuilder.tuning.score.merge-acceptance-floor.
    private static final double ACCEPTANCE_THRESHOLD_DEFAULT = 0.75;
    private static final double SOFT_BAND_LOWER = 0.70;
    // Secondary fix: per-subgoal dynamic lower boundary = max(0.70, lastAccepted - 0.05)
    private static final double SUBGOAL_FLOOR_MARGIN = 0.05;
    // Condition (c): score must not regress more than 0.02 below lastAcceptedSubgoalScore
    private static final double SCORE_REGRESSION_MARGIN = 0.02;
    // Soft-veto acceptance capped at MEDIUM (< 0.75) so HeuristicNegotiator needs majority to override
    private static final double SOFT_BAND_ACCEPTED_CONFIDENCE_CAP = 0.65;
    // Soft-veto rejection capped at HIGH (< 0.90 CRITICAL) so other heuristics retain real influence
    private static final double SOFT_BAND_REJECTED_CONFIDENCE_CAP = 0.75;
    private static final double K = 0.05;

    /** T4-J Phase 2: optional — tests constructing this class directly keep the code default. */
    @lombok.Setter
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.core.tuning.TuningParameterStore tuningParameterStore;

    private double acceptanceThreshold() {
        return tuningParameterStore != null
                ? tuningParameterStore.get("score.merge-acceptance-floor", ACCEPTANCE_THRESHOLD_DEFAULT)
                : ACCEPTANCE_THRESHOLD_DEFAULT;
    }

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome original) {
        if (input == null) return HeuristicOpinion.none(NAME);

        // Abstain when the LLM explicitly REJECTED with a score below the soft-band floor.
        // The project-level globalScore cannot rebut a per-mutation observation the LLM already graded.
        if (original != null
                && original.mutationState() == MutationAcceptanceState.REJECTED
                && original.score() != null
                && original.score() < SOFT_BAND_LOWER) {
            log.info("DeterministicScoreHeuristic abstaining — LLM REJECTED at score={:.2f} (below soft-band floor {:.2f}); project-level globalScore not relevant"
                    .formatted(original.score(), SOFT_BAND_LOWER));
            return HeuristicOpinion.none(NAME);
        }

        ScoringResult scoring = input.scoringResult();
        if (scoring == null || scoring.globalScore() == null) return HeuristicOpinion.none(NAME);

        double s = scoring.globalScore();
        double rawConfidence = computeConfidence(scoring);

        Double lastAccepted = input.lastAcceptedSubgoalScore();
        double effectiveLowerBand = (lastAccepted != null)
                ? Math.max(SOFT_BAND_LOWER, lastAccepted - SUBGOAL_FLOOR_MARGIN)
                : SOFT_BAND_LOWER;

        if (s >= acceptanceThreshold()) {
            return accepted(s, rawConfidence, original, "Deterministic score from ScoringService.globalScore=%.3f confidence=%.2f".formatted(s, rawConfidence));
        }

        if (s >= effectiveLowerBand) {
            return evaluateSoftBand(input, scoring, s, rawConfidence, effectiveLowerBand, lastAccepted, original);
        }

        // Below effective lower band: REJECTED at raw confidence (can reach CRITICAL)
        return rejected(s, rawConfidence, original, "Deterministic score from ScoringService.globalScore=%.3f confidence=%.2f".formatted(s, rawConfidence));
    }

    private HeuristicOpinion evaluateSoftBand(
            CycleContextData input, ScoringResult scoring,
            double s, double rawConfidence, double effectiveLowerBand,
            Double lastAccepted, EvaluationOutcome original) {

        boolean hasMeaningfulDiff = hasMeaningfulDiff(input);
        boolean noProblemRegression = noProblemRegression(input);
        boolean noScoreRegression = lastAccepted == null || s >= lastAccepted - SCORE_REGRESSION_MARGIN;

        if (hasMeaningfulDiff && noProblemRegression && noScoreRegression) {
            double cappedConfidence = Math.min(rawConfidence, SOFT_BAND_ACCEPTED_CONFIDENCE_CAP);
            String rationale = ("Soft-veto band [%.2f,%.2f): globalScore=%.3f — accepted (meaningful diff, no regression, confidence=%.2f)")
                    .formatted(effectiveLowerBand, acceptanceThreshold(), s, cappedConfidence);
            return accepted(s, cappedConfidence, original, rationale);
        }

        String reason = buildBandRejectionReason(hasMeaningfulDiff, noProblemRegression, noScoreRegression, s, effectiveLowerBand, acceptanceThreshold(), lastAccepted);
        double cappedConfidence = Math.min(rawConfidence, SOFT_BAND_REJECTED_CONFIDENCE_CAP);
        return rejected(s, cappedConfidence, original, reason);
    }

    private static String buildBandRejectionReason(
            boolean hasMeaningfulDiff, boolean noProblemRegression, boolean noScoreRegression,
            double s, double effectiveLowerBand, double acceptanceThreshold, Double lastAccepted) {
        StringBuilder sb = new StringBuilder(
                "Soft-veto band [%.2f,%.2f): globalScore=%.3f — rejected".formatted(effectiveLowerBand, acceptanceThreshold, s));
        if (!hasMeaningfulDiff) sb.append("; no meaningful diff");
        if (!noProblemRegression) sb.append("; blocker count increased");
        if (!noScoreRegression) sb.append("; score regressed >%.2f vs lastAccepted=%.3f".formatted(SCORE_REGRESSION_MARGIN, lastAccepted));
        return sb.toString();
    }

    private static HeuristicOpinion accepted(double s, double confidence, EvaluationOutcome original, String rationale) {
        return HeuristicOpinion.ofWithScore(NAME, confidence, MutationAcceptanceState.ACCEPTED, original.goalProgress(), rationale, s);
    }

    private static HeuristicOpinion rejected(double s, double confidence, EvaluationOutcome original, String rationale) {
        return HeuristicOpinion.ofWithScore(NAME, confidence, MutationAcceptanceState.REJECTED, original.goalProgress(), rationale, s);
    }

    private static boolean hasMeaningfulDiff(CycleContextData input) {
        var diff = input.projectDiff();
        if (diff == null) return false;

        var changedFiles = diff.changedFiles();
        var deletedPaths = diff.deletedPaths();

        boolean noChange = (changedFiles == null || changedFiles.isEmpty())
                && (deletedPaths == null || deletedPaths.isEmpty());

        if (!noChange && (deletedPaths == null || deletedPaths.isEmpty())
                && changedFiles != null && !changedFiles.isEmpty()) {
            Map<String, String> prevOutput = input.previousOutput();
            if (prevOutput != null) {
                boolean allAlreadyPresent = changedFiles.entrySet().stream()
                        .allMatch(e -> e.getValue() != null && e.getValue().equals(prevOutput.get(e.getKey())));
                if (allAlreadyPresent) noChange = true;
            }
        }

        return !noChange;
    }

    private static boolean noProblemRegression(CycleContextData input) {
        ProblemContext current = input.problemContext();
        ProblemContext previous = input.previousProblemContext();
        if (current == null || previous == null) return true;
        long currentBlockers = blockerCount(current);
        long previousBlockers = blockerCount(previous);
        return currentBlockers <= previousBlockers;
    }

    private static long blockerCount(ProblemContext pc) {
        if (pc.open() == null) return 0L;
        return pc.open().stream()
                .filter(e -> e != null && e.severity() == ProblemSeverity.BLOCKER)
                .count();
    }

    private double computeConfidence(ScoringResult scoring) {
        Double lastGlobal = scoring.lastGlobalScore();
        Map<String, Double> perFileScores = scoring.perFileScores();

        double temporalFactor;
        if (lastGlobal == null) {
            temporalFactor = 0.5; // neutral: no prior cycle to compare
        } else {
            double delta = Math.abs(scoring.globalScore() - lastGlobal);
            temporalFactor = 1.0 / (1.0 + delta / K);
        }

        if (lastGlobal == null) {
            return temporalFactor * 0.7;
        }

        double spatialFactor = 1.0; // neutral when < 2 files
        if (perFileScores != null && perFileScores.size() >= 2) {
            double cv = coefficientOfVariation(perFileScores.values());
            spatialFactor = 1.0 / (1.0 + cv);
        }

        return Math.sqrt(temporalFactor * spatialFactor);
    }

    private double coefficientOfVariation(Collection<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        if (mean == 0.0) return 0.0;
        double variance = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0.0);
        return Math.sqrt(variance) / mean;
    }
}
