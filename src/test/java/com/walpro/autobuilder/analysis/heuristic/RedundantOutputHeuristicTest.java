package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import com.walpro.autobuilder.analysis.heuristic.ConfidenceLevel;

import org.junit.jupiter.api.Test;

import java.util.List;

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
    void singleRedundantFile_advisoryOnly_doesNotOverride() {
        CycleContextData context = CycleContextData.builder()
                .findings(List.of(redundantFinding("src/Foo.java")))
                .build();

        HeuristicOpinion opinion = heuristic.evaluate(context, ACCEPTED_OUTCOME);

        assertThat(opinion.confidence()).isEqualTo(0.5);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(opinion.goalProgress()).isEqualTo(GoalProgressState.PROGRESSING);
        assertThat(opinion.suggestedScore()).isEqualTo(ACCEPTED_OUTCOME.score());
    }

    @Test
    void twoRedundantFiles_highConfidence_overridesToRejected() {
        CycleContextData context = CycleContextData.builder()
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
    void threeRedundantFiles_stillOverridesToRejected() {
        CycleContextData context = CycleContextData.builder()
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
    void singleRedundantFile_rationaleDescribesCount() {
        CycleContextData context = CycleContextData.builder()
                .findings(List.of(redundantFinding("src/Foo.java")))
                .build();

        HeuristicOpinion opinion = heuristic.evaluate(context, ACCEPTED_OUTCOME);

        assertThat(opinion.rationale()).contains("1 redundant output file");
    }
}
