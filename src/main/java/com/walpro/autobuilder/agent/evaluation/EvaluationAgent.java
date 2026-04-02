package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringService;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.heuristic.EvaluationHeuristic;
import com.walpro.autobuilder.analysis.heuristic.HeuristicNegotiator;
import com.walpro.autobuilder.analysis.heuristic.HeuristicOpinion;
import com.walpro.autobuilder.analysis.heuristic.NegotiationResult;
import com.walpro.autobuilder.analysis.problem.ProblemDetector;
import com.walpro.autobuilder.core.engine.ExecutionAgent;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.graph.evaluation.EvaluationActionRegistrar;
import com.walpro.autobuilder.graph.evaluation.EvaluationGraphFacade;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class EvaluationAgent implements ExecutionAgent {

    private final EvaluationLLMAdapter evaluationAdapter;
    private final ScoringService scoringService;
    private final EvaluationInterpreter evaluationInterpreter;
    private final ProblemDetector problemDetector;

    private final HeuristicNegotiator heuristicNegotiator;
    private final List<EvaluationHeuristic> heuristics;

    private final EvaluationGraphFacade graphFacade;
    private final EvaluationActionRegistrar evaluationActionRegistrar;

    private static final String AGENT_ID = "StaticEvaluationAgent";

    @Override
    public CycleContextData execute(CycleContextData context) {
        ProjectDiffResult diff = context.projectDiff();
        if (diff == null) {
            log.warn("EvaluationExecutionAgent projectDiff is null.");
            return context;
        }

        Map<String, String> changedFiles = diff.changedFiles();
        Set<String> deletedPaths = diff.deletedPaths();
        Map<String, String> oldFiles = context.previousOutput();

        String scoringGoalId = context.currentSubGoalId() != null
                ? context.currentSubGoalId()
                : context.currentGoalId();

        var scoring = scoringService.computeScores(
                context.cycleName(),
                scoringGoalId,
                oldFiles,
                changedFiles,
                deletedPaths
        );

        log.info("Scoring computed for cycle={} globalScore={} lastAcceptedGoalScore={} lastGlobalScore={} perFileCount={}",
                context.cycleName(), scoring.globalScore(), scoring.lastAcceptedGoalScore(), scoring.lastGlobalScore(), scoring.perFileScores().size());

        Double comparisonBaseline =
                scoring.lastAcceptedGoalScore() != null
                        ? scoring.lastAcceptedGoalScore()
                        : scoring.lastGlobalScore();

        context = context
                .withScoringResult(scoring)
                .withLastScore(comparisonBaseline)
                .withScoreHistoryByFile(scoring.scoreHistoryByFile())
                .withRecentDeltas(scoring.scoreDeltas())
                .withFindings(scoring.findings())
                .withScoringComputed(true);

        // 1) LLM outcome — T1-AV: skip LLM when fingerprint is unchanged
        String currentFingerprint = context.lastEvolutionFingerprint();
        String lastEvaluatedFingerprint = context.lastEvaluatedEvolutionFingerprint();
        boolean fingerprintUnchanged = currentFingerprint != null
                && currentFingerprint.equals(lastEvaluatedFingerprint)
                && !context.hasSignal(BasicSignals.EVOLUTION_FINGERPRINT_CHANGED);

        EvaluationOutcome rawOutcome;
        if (fingerprintUnchanged && context.lastEvaluationOutcome() != null) {
            log.info("EvaluationAgent: fingerprint unchanged ({}), reusing prior LLM outcome", currentFingerprint);
            rawOutcome = context.lastEvaluationOutcome();
        } else if (fingerprintUnchanged) {
            log.info("EvaluationAgent: fingerprint unchanged ({}) with no prior outcome, short-circuiting to REJECTED", currentFingerprint);
            rawOutcome = new EvaluationOutcome(null, MutationAcceptanceState.REJECTED, GoalProgressState.NOT_EVALUATED,
                    "Fingerprint unchanged; no new evaluation.", null);
        } else {
            rawOutcome = evaluationAdapter.evaluate(context);
        }

        // 2) capture "before" for delta checks
        context = context.withPreviousProblemContext(context.problemContext());

        // 3) update ProblemContext BEFORE negotiation
        var updatedProblemContext = problemDetector.detectAndUpdate(context, rawOutcome, scoring.findings());
        context = context.withProblemContext(updatedProblemContext);

        // 4) negotiate outcome using heuristics (now has previous + current ProblemContext)
        NegotiationResult negotiationResult = heuristicNegotiator.negotiate(context, rawOutcome, heuristics);
        EvaluationOutcome finalOutcome = negotiationResult.outcome();

        // IMPORTANT: this is now the canonical "context score"
        context = context.withScore(finalOutcome.score());

        // T2-AT: update per-heuristic CRITICAL streak counters; rebuild persistent warnings
        context = updateCriticalHeuristicStreaks(context, negotiationResult.criticalOpinions());

        // Optional: log heuristic opinions against final outcome
        CycleContextData finalContext = context;
        heuristics.forEach(h -> {
            var opinion = h.evaluate(finalContext, finalOutcome);
            if (opinion != null) {
                log.info("Heuristic [{}] mutationState: {}, goalProgress: {}, rationale: {}, score={}",
                        opinion.name(),
                        opinion.mutationState(),
                        opinion.goalProgress(),
                        opinion.rationale(),
                        opinion.suggestedScore());
            }
        });

        // 5) interpret final outcome
        EvaluationInterpreter.InterpretationResult result =
                evaluationInterpreter.interpret(finalOutcome, comparisonBaseline, false);

        // Emit LOW_CONFIDENCE only for score-based failure; STAGNATING has its own signal below.
        // Graduate strength by distance below the 0.75 threshold so near-misses don't accumulate.
        if (!result.success() && result.goalProgress() != GoalProgressState.STAGNATING) {
            double score = finalOutcome.score() != null ? finalOutcome.score() : 0.0;
            double strength = Math.min(1.0, (0.75 - score) / 0.20); // 1.0 at ≤0.55, ~0.25 at 0.70, ~0.05 at 0.74
            context = context.withSignal(
                    Signal.of(BasicSignals.LOW_CONFIDENCE, Math.max(0.05, strength), 0.15, "evaluation")
            );
        }

        context = context
                .withEvaluationOutcome(finalOutcome)
                .withLastEvaluationOutcome(finalOutcome)
                .withEvaluationInterpretation(result)
                .withEvaluationFeedback(finalOutcome.feedbackMessage())
                .withRecommendation(finalOutcome.recommendation())
                .withLastEvaluatedEvolutionFingerprint(context.lastEvolutionFingerprint());

        if (result.goalProgress() == GoalProgressState.STAGNATING) {
            context = context.withSignal(
                    Signal.of(BasicSignals.STAGNATION, 1.0, 0.2, "evaluation")
            );
        }

        // 6) persist/graph after final outcome
        graphFacade.recordEvaluation(context, finalOutcome);
        evaluationActionRegistrar.registerEvaluation(AGENT_ID, context, finalOutcome);

        return context;
    }

    /**
     * T2-AT: Increments the consecutive-CRITICAL streak for each heuristic that fired at CRITICAL
     * confidence this cycle. Heuristics not in {@code criticalOpinions} are implicitly reset
     * (removed from the map). When a streak reaches ≥ 2, a human-readable warning is added to
     * {@code persistentHeuristicWarnings} so the evolution prompt can surface it as a directive.
     */
    private static CycleContextData updateCriticalHeuristicStreaks(CycleContextData context,
                                                                    List<HeuristicOpinion> criticalOpinions) {
        Map<String, Integer> prevStreaks = context.criticalHeuristicStreaks();
        Map<String, Integer> newStreaks = new HashMap<>();

        for (HeuristicOpinion op : criticalOpinions) {
            int prev = prevStreaks != null ? prevStreaks.getOrDefault(op.name(), 0) : 0;
            newStreaks.put(op.name(), prev + 1);
        }

        // Build warnings for heuristics with streak >= 2 (consistently repeating CRITICAL)
        List<String> warnings = new ArrayList<>();
        for (HeuristicOpinion op : criticalOpinions) {
            int streak = newStreaks.get(op.name());
            if (streak >= 2) {
                warnings.add("[%s] (critical for %d consecutive cycles): %s"
                        .formatted(op.name(), streak, op.rationale()));
            }
        }

        if (!warnings.isEmpty()) {
            log.info("T2-AT persistent heuristic warnings ({}):\n{}",
                    warnings.size(),
                    String.join("\n", warnings));
        }

        return context
                .withCriticalHeuristicStreaks(newStreaks)
                .withPersistentHeuristicWarnings(warnings);
    }

    @Override
    public String name() {
        return "evaluation";
    }

    @Override
    public String role() {
        return "evaluation";
    }

    @Override
    public Set<SignalType> getSupportedSignals() {
        return BasicSignals.EVALUATION_SIGNALS;
    }
}