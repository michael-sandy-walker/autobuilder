package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.model.ElementDescriptor;
import com.walpro.autobuilder.analysis.diff.model.ElementType;
import com.walpro.autobuilder.analysis.diff.model.StructuralDiffNode;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SemanticClarityHeuristicTest {

    private final SemanticClarityHeuristic heuristic = new SemanticClarityHeuristic();

    private static ElementDescriptor desc(String id) {
        return new ElementDescriptor(id, ElementType.METHOD, "body", Map.of());
    }

    private static StructuralDiffNode root(List<StructuralDiffNode> children) {
        ElementDescriptor e = desc("root");
        return new StructuralDiffNode(e, e, children);
    }

    private static StructuralDiffNode added(String id) {
        return new StructuralDiffNode(null, desc(id), Collections.emptyList());
    }

    private static StructuralDiffNode deleted(String id) {
        return new StructuralDiffNode(desc(id), null, Collections.emptyList());
    }

    private static StructuralDiffNode modified(String id) {
        return new StructuralDiffNode(
                new ElementDescriptor(id, ElementType.METHOD, "old", Map.of()),
                new ElementDescriptor(id, ElementType.METHOD, "new", Map.of()),
                Collections.emptyList()
        );
    }

    private static EvaluationOutcome acceptedOutcome(double score) {
        return new EvaluationOutcome(score, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "ok", null);
    }

    // --- Pure additions are neutral (no penalty) ---

    @Test
    void pureAdditionToNewFile_returnsNone() {
        CycleContextData ctx = CycleContextData.builder()
                .lastScore(0.75)
                .previousOutput(Map.of())
                .build();

        List<StructuralDiffNode> children = List.of(added("m1"), added("m2"), added("m3"), added("m4"));
        ctx = ctx.withProjectDiff(new ProjectDiffResult(root(children), Map.of("src/New.java", "c"), Set.of()));

        assertThat(heuristic.evaluate(ctx, acceptedOutcome(0.80)).level()).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void pureAdditionToExistingFile_returnsNone() {
        // Adding new methods to an existing file is legitimate growth, not bloat.
        CycleContextData ctx = CycleContextData.builder()
                .lastScore(0.75)
                .previousOutput(Map.of("src/Existing.java", "old"))
                .build();

        List<StructuralDiffNode> children = List.of(added("m1"), added("m2"), added("m3"), added("m4"),
                added("m5"), added("m6"), added("m7"), added("m8"));
        ctx = ctx.withProjectDiff(new ProjectDiffResult(root(children), Map.of("src/Existing.java", "new"), Set.of()));

        assertThat(heuristic.evaluate(ctx, acceptedOutcome(0.80)).level()).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void allModifiedNodes_returnsNone() {
        // Balanced modifications have simplificationBias = 0 - neutral, no opinion.
        CycleContextData ctx = CycleContextData.builder()
                .lastScore(0.75)
                .previousOutput(Map.of("src/Existing.java", "old"))
                .build();

        List<StructuralDiffNode> children = List.of(
                modified("m1"), modified("m2"), modified("m3"), modified("m4"));
        ctx = ctx.withProjectDiff(new ProjectDiffResult(root(children), Map.of("src/Existing.java", "new"), Set.of()));

        assertThat(heuristic.evaluate(ctx, acceptedOutcome(0.80)).level()).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void additionsOutweighDeletions_returnsNone() {
        // More additions than deletions: simplificationBias < 0 -> neutral.
        CycleContextData ctx = CycleContextData.builder()
                .lastScore(0.75)
                .previousOutput(Map.of("src/Existing.java", "old"))
                .build();

        List<StructuralDiffNode> children = List.of(
                added("m1"), added("m2"), added("m3"), added("m4"), deleted("m5"));
        ctx = ctx.withProjectDiff(new ProjectDiffResult(root(children), Map.of("src/Existing.java", "new"), Set.of()));

        assertThat(heuristic.evaluate(ctx, acceptedOutcome(0.80)).level()).isEqualTo(ConfidenceLevel.NONE);
    }

    // --- Simplification is rewarded ---

    @Test
    void deletionHeavyDiff_yieldsPositiveScoreAdjustment() {
        CycleContextData ctx = CycleContextData.builder()
                .lastScore(0.70)
                .previousOutput(Map.of("src/Existing.java", "old"))
                .build();

        List<StructuralDiffNode> children = List.of(
                deleted("m1"), deleted("m2"), deleted("m3"), deleted("m4"), added("m5"));
        ctx = ctx.withProjectDiff(new ProjectDiffResult(root(children), Map.of("src/Existing.java", "new"), Set.of()));

        HeuristicOpinion opinion = heuristic.evaluate(ctx, acceptedOutcome(0.70));

        assertThat(opinion.level()).isNotEqualTo(ConfidenceLevel.NONE);
        assertThat(opinion.suggestedScore()).isGreaterThan(0.70);
    }

    @Test
    void pureDeletion_yieldsMaxPositiveAdjustment() {
        CycleContextData ctx = CycleContextData.builder()
                .lastScore(0.70)
                .previousOutput(Map.of("src/Existing.java", "old"))
                .build();

        List<StructuralDiffNode> children = List.of(
                deleted("m1"), deleted("m2"), deleted("m3"), deleted("m4"), deleted("m5"));
        ctx = ctx.withProjectDiff(new ProjectDiffResult(root(children), Map.of("src/Existing.java", "new"), Set.of()));

        HeuristicOpinion opinion = heuristic.evaluate(ctx, acceptedOutcome(0.70));

        // simplificationBias = 1.0 -> maximum positive nudge
        assertThat(opinion.suggestedScore()).isCloseTo(0.70 + 0.12, within(0.02));
    }

    @Test
    void modificationsWithDeletions_yieldsPositiveBias() {
        // deleted > added (0 added) -> simplificationBias positive -> score nudged up
        CycleContextData ctx = CycleContextData.builder()
                .lastScore(0.70)
                .previousOutput(Map.of("src/Existing.java", "old"))
                .build();

        List<StructuralDiffNode> children = List.of(
                deleted("m1"), deleted("m2"), deleted("m3"),
                modified("m4"), modified("m5"));
        ctx = ctx.withProjectDiff(new ProjectDiffResult(root(children), Map.of("src/Existing.java", "new"), Set.of()));

        HeuristicOpinion opinion = heuristic.evaluate(ctx, acceptedOutcome(0.70));

        assertThat(opinion.suggestedScore()).isGreaterThan(0.70);
    }

    // --- Passthrough - heuristic does not change mutation state ---

    @Test
    void simplificationOpinion_doesNotChangeAcceptanceState() {
        CycleContextData ctx = CycleContextData.builder()
                .lastScore(0.70)
                .previousOutput(Map.of("src/Existing.java", "old"))
                .build();

        List<StructuralDiffNode> children = List.of(
                deleted("m1"), deleted("m2"), deleted("m3"), added("m4"));
        ctx = ctx.withProjectDiff(new ProjectDiffResult(root(children), Map.of("src/Existing.java", "new"), Set.of()));

        HeuristicOpinion opinion = heuristic.evaluate(ctx, acceptedOutcome(0.70));

        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(opinion.goalProgress()).isEqualTo(GoalProgressState.PROGRESSING);
    }

    // --- Guard conditions ---

    @Test
    void returnsNoneWhenNoLastScore() {
        CycleContextData ctx = CycleContextData.builder().build();
        List<StructuralDiffNode> children = List.of(deleted("m1"), deleted("m2"));
        ctx = ctx.withProjectDiff(new ProjectDiffResult(root(children), Map.of("src/X.java", "c"), Set.of()));

        assertThat(heuristic.evaluate(ctx, acceptedOutcome(0.80)).level()).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void returnsNoneWhenNoDiff() {
        CycleContextData ctx = CycleContextData.builder().lastScore(0.75).build();

        assertThat(heuristic.evaluate(ctx, acceptedOutcome(0.80)).level()).isEqualTo(ConfidenceLevel.NONE);
    }
}
