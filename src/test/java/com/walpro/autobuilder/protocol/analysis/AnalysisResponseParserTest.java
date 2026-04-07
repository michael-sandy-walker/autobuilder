package com.walpro.autobuilder.protocol.analysis;

import com.walpro.autobuilder.agent.analysis.model.AnalysisOutcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisResponseParserTest {

    private final AnalysisResponseParser parser = new AnalysisResponseParser();

    private static final String FULL_RESPONSE = """
            Findings:
            - [WARNING] MISSING_IMPL :: method not found :: src/main/java/Foo.java
            Hypotheses:
            - The implementation was removed in a prior cycle
            ProposedExecutableSubGoals:
            - Re-implement the missing method in Foo.java
            Message: Analysis complete.
            GoalKind: EXECUTABLE
            PossiblyComplete: false
            """;

    private static final String POSSIBLY_COMPLETE_RESPONSE = """
            Findings:
            - [INFO] GOAL_MET :: all required classes present :: (n/a)
            Hypotheses:
            - The subgoal has already been merged
            ProposedExecutableSubGoals:
            Message: Implementation looks complete.
            GoalKind: EXECUTABLE
            PossiblyComplete: true
            """;

    @Test
    void parse_returnsEmpty_whenNull() {
        AnalysisOutcome out = parser.parse(null);

        assertThat(out.findings()).isEmpty();
        assertThat(out.possiblyComplete()).isFalse();
    }

    @Test
    void parse_parsesFindings_andMessage() {
        AnalysisOutcome out = parser.parse(FULL_RESPONSE);

        assertThat(out.findings()).hasSize(1);
        assertThat(out.findings().get(0).severity()).isEqualTo("WARNING");
        assertThat(out.findings().get(0).type()).isEqualTo("MISSING_IMPL");
        assertThat(out.message()).isEqualTo("Analysis complete.");
        assertThat(out.goalKind()).isEqualTo("EXECUTABLE");
    }

    @Test
    void parse_possiblyComplete_false_whenAbsent() {
        AnalysisOutcome out = parser.parse(FULL_RESPONSE);

        assertThat(out.possiblyComplete()).isFalse();
    }

    @Test
    void parse_possiblyComplete_true_whenSet() {
        AnalysisOutcome out = parser.parse(POSSIBLY_COMPLETE_RESPONSE);

        assertThat(out.possiblyComplete()).isTrue();
        assertThat(out.message()).isEqualTo("Implementation looks complete.");
    }

    @Test
    void parse_possiblyComplete_caseInsensitive() {
        String raw = """
                Findings:
                Hypotheses:
                ProposedExecutableSubGoals:
                Message: ok
                GoalKind: COGNITIVE
                PossiblyComplete: TRUE
                """;
        AnalysisOutcome out = parser.parse(raw);

        assertThat(out.possiblyComplete()).isTrue();
    }

    @Test
    void parse_proposedSubGoals_parsed() {
        AnalysisOutcome out = parser.parse(FULL_RESPONSE);

        assertThat(out.proposedExecutableSubGoals()).containsExactly("Re-implement the missing method in Foo.java");
    }
}
