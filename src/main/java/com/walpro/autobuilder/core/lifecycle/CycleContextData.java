package com.walpro.autobuilder.core.lifecycle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.agent.plan.ActionScope;
import com.walpro.autobuilder.protocol.meta.MetaNegotiationRecord;
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
        Map<String, String> previousOutput,
        String projectSummary,
        ProjectDiffResult projectDiff,

        // Goal tracking
        String currentGoalDescription,
        String currentSubGoalDescription,
        String currentGoalId,
        String currentSubGoalId,
        List<GoalEntity> remainingGoals,
        List<GoalEntity> completedGoals,
        Integer goalDepth,

        // Evaluation
        String rejectionFeedback,
        String recommendation,
        EvolutionOutcome evolutionOutcome,
        EvaluationOutcome evaluationOutcome,
        PlannerOutcome plannerOutcome,
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
        String lastRecombinationFingerprint,
        List<String> recentEvolutionFingerprints,
        String previousAcceptedCycleName,
        String parentCycleName,
        String recombinationSourceCycleName,
        EvolutionOutcome lastEvolutionOutcome,
        EvaluationOutcome lastEvaluationOutcome,
        PlannerOutcome lastPlannerOutcome,

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

        // Meta
        MetaNegotiationRecord lastMetaProposal,
        Map<SignalType, Signal> signals,
        Map<String, Double> lastFitnessScores,
        Set<ActionScope> reservedScopes
) {
    public CycleContextData {
        if (reservedScopes == null) {
            reservedScopes = new HashSet<>();
        }
    }

    public boolean hasSignal(SignalType type) {
        return signals != null && signals.containsKey(type);
    }

    public Signal getSignal(SignalType type) {
        return signals != null ? signals.get(type) : null;
    }

    public CycleContextData withSignal(Signal signal) {
        Map<SignalType, Signal> updated = new HashMap<>(signals != null ? signals : Map.of());
        updated.put(signal.type(), signal);
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

    public void reserveScopes(Set<ActionScope> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return;
        }
        reservedScopes.addAll(scopes);
    }

    public boolean isScopeReserved(ActionScope scope) {
        return scope != null && reservedScopes.contains(scope);
    }
}
