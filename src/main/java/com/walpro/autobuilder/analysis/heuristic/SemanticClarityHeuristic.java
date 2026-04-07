package com.walpro.autobuilder.analysis.heuristic;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.model.StructuralDiffNode;
import com.walpro.autobuilder.analysis.diff.util.StructuralDiffUtils;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import lombok.extern.slf4j.Slf4j;

/**
 * Rewards structural simplification: when deletions outweigh additions the codebase is
 * getting cleaner, and the score is nudged up modestly.
 *
 * Pure additions are neutral - growing the codebase to implement a subgoal is the expected
 * behaviour during development and should not be penalised. Repeated identical re-submissions
 * are caught by {@link NoMeaningfulChangeHeuristic} and {@link RedundantOutputHeuristic}.
 *
 * Only fires when {@code simplificationBias > 0} (net deletions). Returns
 * {@link HeuristicOpinion#none} for additions, balanced changes, and all-modifications.
 */
@Slf4j
@Component
public class SemanticClarityHeuristic implements EvaluationHeuristic {

    private static final String NAME = "SemanticClarityHeuristic";

    // Keep the influence small; rewards simplification, never penalises growth.
    private static final double MAX_SCORE_DELTA = 0.12;

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome outcome) {
        ProjectDiffResult diff = input.projectDiff();
        if (diff == null || diff.structuredDiff() == null) {
            return HeuristicOpinion.none(NAME);
        }

        if (input.lastScore() == null) {
            // No previous score means this is the first occurrence - pure additions are expected,
            // not a sign of bloat. Nothing to say.
            return HeuristicOpinion.none(NAME);
        }

        // New-file creation: all changed files absent from previousOutput -> additions are
        // expected; no simplification signal possible.
        Set<String> changedFilePaths = diff.changedFiles().keySet();
        if (!changedFilePaths.isEmpty() && isAllNewFiles(changedFilePaths, input.previousOutput())) {
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

        // [-1, 1]: +1 = pure deletion (maximum simplification), -1 = pure addition
        double simplificationBias = (deleted - added) / (double) changed;

        // Pure additions and balanced diffs are neutral - only net simplification is rewarded.
        if (simplificationBias <= 0) {
            return HeuristicOpinion.none(NAME);
        }

        Double scoreObj = outcome.score();
        if (scoreObj == null) {
            return HeuristicOpinion.none(NAME);
        }

        double adjustment = simplificationBias * MAX_SCORE_DELTA;
        double suggestedScore = clamp(scoreObj + adjustment, 0.0, 1.0);

        String rationale = String.format(
                "SemanticClarityHeuristic: added=%d, deleted=%d, modified=%d → simplificationBias=%.2f, Δscore=%.3f",
                added, deleted, modified, simplificationBias, adjustment
        );

        double magnitude = simplificationBias; // directional certainty
        double volumeFactor = 1 - Math.exp(-changed / 5.0); // confidence grows with data
        double coherence = 1 - (modified / (double) changed); // purity of the change

        double confidence = magnitude * volumeFactor * coherence;
        confidence = Math.min(1.0, Math.max(0.0, confidence));

        log.debug("SemanticClarityHeuristic: added={}, deleted={}, modified={}, bias={}, confidence={}",
                added, deleted, modified, simplificationBias, confidence);

        return HeuristicOpinion.ofWithScore(
                NAME,
                confidence,
                outcome.mutationState(),
                outcome.goalProgress(),
                rationale,
                suggestedScore
        );
    }

    private static boolean isAllNewFiles(Set<String> changedFilePaths, Map<String, String> previousOutput) {
        if (previousOutput == null || previousOutput.isEmpty()) {
            return true;
        }
        return changedFilePaths.stream().noneMatch(previousOutput::containsKey);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
