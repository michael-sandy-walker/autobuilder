package com.walpro.autobuilder.core.lifecycle;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.walpro.autobuilder.agent.analysis.model.AnalysisOutcome;
import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringResult;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.agent.merge.model.MergeOutcome;
import com.walpro.autobuilder.analysis.problem.ProblemContext;
import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.goal.GoalProgressApplicationResult;
import com.walpro.autobuilder.protocol.meta.MetaControlDecision;
import com.walpro.autobuilder.protocol.meta.MetaIntention;
import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.analysis.heuristic.HeuristicFinding;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MergeTrace;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import lombok.Builder;
import lombok.With;

@Builder(toBuilder = true)
@With
public record CycleContextData(
        String cycleName,
        String outputPath,
        String mutationPath,
        String snapshotPath,
        Map<String, String> previousOutput,
        String projectSummary,
        ProjectDiffResult projectDiff,

        // Goal tracking
        String currentGoalDescription,
        String currentSubGoalDescription,
        String currentGoalId,
        String currentSubGoalId,
        String analysisScopeGoalId,
        String analysisScopeSubGoalId,
        String lastAnalysisScopeGoalId,
        String lastAnalysisScopeSubGoalId,
        List<GoalEntity> remainingGoals,
        List<GoalEntity> completedGoals,
        List<GoalEntity> abandonedGoals,
        List<String> skippedGoals,
        Integer goalDepth,
        String bestMatchSubGoalId,

        // Evaluation
        String rejectionFeedback,
        String recommendation,
        EvolutionOutcome evolutionOutcome,
        EvaluationOutcome evaluationOutcome,
        PlannerOutcome plannerOutcome,
        AnalysisOutcome analysisOutcome,
        String evaluationFeedback,
        InterpretationResult evaluationInterpretation,
        MutationAcceptanceState mutationState,
        GoalProgressState goalProgress,
        CycleResultState cycleResultState,

        // Status flags
        boolean subgoalPromoted,
        boolean merged,
//    boolean recombined,
//    boolean repeatedRecombination,

        // Fingerprints + lineage
        String lastEvolutionFingerprint,
        String lastEvaluatedEvolutionFingerprint,
        String lastRecombinationFingerprint,
        List<String> recentEvolutionFingerprints,
        String previousAcceptedCycleName,
        String parentCycleName,
        String recombinationSourceCycleName,
        EvolutionOutcome lastEvolutionOutcome,
        EvaluationOutcome lastEvaluationOutcome,
        PlannerOutcome lastPlannerOutcome,
        AnalysisOutcome lastAnalysisOutcome,
        String lastAttemptId,
        String parentAttemptId,

        // Merge
        String mergedPath,
        String mergedBaseContent,
        List<FileVariant> mergedVariants,
        MergeTrace mergeTrace,

        // Evaluation context
        List<HeuristicFinding> findings,
        Map<String, List<Double>> scoreHistoryByFile,
        Map<String, Double> recentDeltas,
        double score,
        Double lastScore,
        Double evaluationBias,
        boolean scoringComputed,
        ScoringResult scoringResult,

        // Meta
        Map<SignalType, Signal> signals,
        Map<String, Double> lastFitnessScores,
        DeliberationResult deliberationResult,
        GoalProgressApplicationResult lastGoalProgressApplication,
        MetaControlDecision metaDecision,
        MetaIntention metaIntention,

        // Problems
        ProblemContext problemContext,
        ProblemContext previousProblemContext,

        // Interpretation output (carried into next cycle for agent prompts)
        String interpretationSummary,
        String interpretationDirectives,

        // T2-AA: accumulated stagnation history for the current subgoal across all its activations.
        // Populated from GoalEntity.metadata["cumulativeStagnatingCycles"] each cycle.
        int cumulativeStagnatingCycles


) {
    public CycleContextData {
        if (signals == null) {
            signals = new LinkedHashMap<>();
        }
        if (lastFitnessScores == null) {
            lastFitnessScores = new LinkedHashMap<>();
        }
    }

    public boolean hasSignal(SignalType type) {
        return signals != null && signals.containsKey(type);
    }

    public boolean hasStrongSignal(SignalType type, double minStrength) {
        if (signals() == null || type == null) return false;
        return signals().values().stream().anyMatch(s ->
                s != null
                        && type.equals(s.type())
                        && s.strength() >= minStrength
        );
    }

    public Signal getSignal(SignalType type) {
        return signals != null ? signals.get(type) : null;
    }

    // Replaces the role signal without reinforcing it
    public CycleContextData withSignalReplaceRoleSignals(Signal signal) {
        if (signal == null || signal.type() == null) return this;
        Map<SignalType, Signal> updated = new HashMap<>(signals != null ? signals : Map.of());
        updated.put(signal.type(), signal);
        return this.toBuilder().signals(updated).build();
    }

    public CycleContextData withSignal(Signal signal) {
        if (signal == null || signal.type() == null) {
            return this;
        }

        Map<SignalType, Signal> updated = new HashMap<>(signals != null ? signals : Map.of());

        updated.compute(signal.type(), (k, existing) -> existing == null ? signal : existing.reinforce(signal));

        return this.toBuilder().signals(updated).build();
    }

    public CycleContextData withoutSignal(SignalType signalType) {
        if (signals == null || signals.isEmpty()) return this;
        Map<SignalType, Signal> updated = new HashMap<>(signals);
        updated.remove(signalType);
        return withSignals(updated);
    }

    /** apply decay + cleanup */
    public CycleContextData tickSignals(double threshold) {
        if (signals == null || signals.isEmpty()) return this;
        Map<SignalType, Signal> updated = new HashMap<>();
        for (Signal s : signals.values()) {
            Signal t = s.tick();
            if (t.isAlive(threshold)) updated.put(t.type(), t);
        }
        return withSignals(updated);
    }
}
