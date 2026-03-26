package com.walpro.autobuilder.analysis.heuristic;

import java.util.List;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.model.StructuralDiffNode;
import com.walpro.autobuilder.analysis.diff.util.StructuralDiffUtils;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import lombok.extern.slf4j.Slf4j;

/**
 * Very early, lightweight proxy for "semantic clarity".
 *
 * Intuition:
 * - More deletions than additions &rarr; likely simplification / consolidation.
 * - More additions than deletions &rarr; likely bloat / increased complexity.
 * <p>
 * This DOES NOT try to understand domain meaning yet. It just nudges the
 * score a little bit based on how the structural diff "smells".
 */
@Slf4j
@Component
public class SemanticClarityHeuristic implements EvaluationHeuristic {

    private static final String NAME = "SemanticClarityHeuristic";

    // Keep the influence small at first; we can crank this up later.
    private static final double MAX_SCORE_DELTA = 0.12;

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome outcome) {
        ProjectDiffResult diff = input.projectDiff();
        if (diff == null || diff.structuredDiff() == null) {
            // No structural diff → no opinion
            return HeuristicOpinion.none(NAME);
        }

        if (input.lastScore() == null) {
            // No previous score means this is the first occurrence — pure additions are expected,
            // not a sign of bloat. Nothing to say.
            return HeuristicOpinion.none(NAME);
        }

        StructuralDiffNode root = diff.structuredDiff();
        List<StructuralDiffNode> nodes = StructuralDiffUtils.flatten(root);

        long added    = nodes.stream().filter(StructuralDiffNode::isAdded).count();
        long deleted  = nodes.stream().filter(StructuralDiffNode::isDeleted).count();
        long modified = nodes.stream().filter(StructuralDiffNode::isModified).count();

        long changed = added + deleted + modified;
        if (changed == 0) {
            // Nothing structural changed → nothing to say
            return HeuristicOpinion.none(NAME);
        }

        // [-1, 1]: +1 = pure deletion, -1 = pure addition, 0 = balanced
        double simplificationBias = (deleted - added) / (double) changed;

        // Bias slightly towards deletion (simplification) as "clarity"
        double adjustment = simplificationBias * MAX_SCORE_DELTA;
        Double scoreObj = outcome.score();
        if (scoreObj == null) {
            // No numeric score => cannot judge "hallucinated improvement".
            return HeuristicOpinion.none(NAME);
        }
        double originalScore = scoreObj;
        double suggestedScore = clamp(originalScore + adjustment, 0.0, 1.0);

        MutationAcceptanceState mutationState = outcome.mutationState();
        GoalProgressState goalProgress = outcome.goalProgress();

        String rationale = String.format(
                "SemanticClarityHeuristic: added=%d, deleted=%d, modified=%d → simplificationBias=%.2f, Δscore=%.3f",
                added, deleted, modified, simplificationBias, adjustment
        );

        log.debug("[SemanticClarityHeuristic] {}", rationale);

        double magnitude = Math.abs(simplificationBias);     // directional certainty
        double volumeFactor = 1 - Math.exp(-changed / 5.0);  // how much data we have
        double coherence = 1 - (modified / (double) changed); // how pure the change is

        double confidence = magnitude * volumeFactor * coherence;
        confidence = Math.min(1.0, Math.max(0.0, confidence));

        log.debug("SemanticClarityHeuristic: added={}, deleted={}, modified={}, bias={}, confidence={}",
                added, deleted, modified, simplificationBias, confidence);

        return HeuristicOpinion.ofWithScore(
                NAME,
                confidence,
                mutationState,
                goalProgress,
                rationale,
                suggestedScore
        );
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
