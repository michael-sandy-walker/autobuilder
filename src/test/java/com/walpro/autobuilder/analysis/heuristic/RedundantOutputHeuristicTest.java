package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RedundantOutputHeuristicTest {

    private final RedundantOutputHeuristic heuristic = new RedundantOutputHeuristic();

    private static final EvaluationOutcome ACCEPTED_OUTCOME = new EvaluationOutcome(
            0.85,
            MutationAcceptanceState.ACCEPTED,
            GoalProgressState.PROGRESSING,
            "Looks good",
            null
    );

    private static HeuristicFinding redundantFinding(String path) {
        return new HeuristicFinding("File is unchanged from previous cycle", HeuristicFinding.Severity.INFO, "redundant-output", path);
    }

    @Test
    void noFindings_returnsNone() {
        CycleContextData context = CycleContextData.builder().build();

        HeuristicOpinion opinion = heuristic.evaluate(context, ACCEPTED_OUTCOME);

        assertThat(opinion.confidence()).isEqualTo(0.0);
        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void singleRedundantFileInMultiFileProject_advisoryOnly() {
        // 1 redundant out of 3 total files (33%) - below 50% threshold, advisory only.
        CycleContextData context = CycleContextData.builder()
                .previousOutput(Map.of("src/Foo.java", "a", "src/Bar.java", "b", "src/Baz.java", "c"))
                .findings(List.of(redundantFinding("src/Foo.java")))
                .build();

        HeuristicOpinion opinion = heuristic.evaluate(context, ACCEPTED_OUTCOME);

        assertThat(opinion.confidence()).isEqualTo(0.5);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(opinion.goalProgress()).isEqualTo(GoalProgressState.PROGRESSING);
        assertThat(opinion.suggestedScore()).isEqualTo(ACCEPTED_OUTCOME.score());
    }

    @Test
    void singleRedundantFileInSingleFileProject_overridesToRejected() {
        // 1 redundant out of 1 total file (100% redundant) - threshold drops to 1, override fires.
        CycleContextData context = CycleContextData.builder()
                .previousOutput(Map.of("src/Foo.java", "content"))
                .findings(List.of(redundantFinding("src/Foo.java")))
                .build();

        HeuristicOpinion opinion = heuristic.evaluate(context, ACCEPTED_OUTCOME);

        assertThat(opinion.confidence()).isEqualTo(0.75);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.goalProgress()).isEqualTo(GoalProgressState.STAGNATING);
        assertThat(opinion.suggestedScore()).isEqualTo(0.4);
    }

    @Test
    void twoRedundantFiles_highConfidence_overridesToRejected() {
        // 2 redundant out of 2 total files (100%) - overrides.
        CycleContextData context = CycleContextData.builder()
                .previousOutput(Map.of("src/Foo.java", "a", "src/Bar.java", "b"))
                .findings(List.of(
                        redundantFinding("src/Foo.java"),
                        redundantFinding("src/Bar.java")
                ))
                .build();

        HeuristicOpinion opinion = heuristic.evaluate(context, ACCEPTED_OUTCOME);

        assertThat(opinion.confidence()).isEqualTo(0.75);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.goalProgress()).isEqualTo(GoalProgressState.STAGNATING);
        assertThat(opinion.suggestedScore()).isEqualTo(0.4);
    }

    @Test
    void threeRedundantFilesOutOfFour_overridesToRejected() {
        // 3 redundant out of 4 total files (75%) - above 50% threshold, overrides.
        CycleContextData context = CycleContextData.builder()
                .previousOutput(Map.of("src/A.java", "a", "src/B.java", "b", "src/C.java", "c", "src/D.java", "d"))
                .findings(List.of(
                        redundantFinding("src/A.java"),
                        redundantFinding("src/B.java"),
                        redundantFinding("src/C.java")
                ))
                .build();

        HeuristicOpinion opinion = heuristic.evaluate(context, ACCEPTED_OUTCOME);

        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.suggestedScore()).isEqualTo(0.4);
    }

    @Test
    void twoRedundantFilesOutOfFive_advisoryOnly() {
        // 2 redundant out of 5 total files (40%) - below 50% threshold, advisory only.
        CycleContextData context = CycleContextData.builder()
                .previousOutput(Map.of("src/A.java", "a", "src/B.java", "b", "src/C.java", "c",
                        "src/D.java", "d", "src/E.java", "e"))
                .findings(List.of(redundantFinding("src/A.java"), redundantFinding("src/B.java")))
                .build();

        HeuristicOpinion opinion = heuristic.evaluate(context, ACCEPTED_OUTCOME);

        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(opinion.confidence()).isEqualTo(0.5);
    }

    @Test
    void rationaleIncludesCountAndTotal() {
        CycleContextData context = CycleContextData.builder()
                .previousOutput(Map.of("src/Foo.java", "a", "src/Bar.java", "b"))
                .findings(List.of(redundantFinding("src/Foo.java")))
                .build();

        HeuristicOpinion opinion = heuristic.evaluate(context, ACCEPTED_OUTCOME);

        assertThat(opinion.rationale()).contains("1/2 redundant output file");
    }
}
