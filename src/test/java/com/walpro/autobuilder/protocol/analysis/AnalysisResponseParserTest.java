package com.walpro.autobuilder.protocol.analysis;

import com.walpro.autobuilder.agent.analysis.model.AnalysisOutcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisResponseParserTest {

    private final AnalysisResponseParser parser = new AnalysisResponseParser();


    /**
     * T1-FT: the contract prints its own format, and a model that echoes it back had the
     * placeholder admitted to the goal tree — in run 2026-08-22_06-19-55 as a node that became the
     * current subgoal 11 times and was then marked COMPLETED.
     */
    @org.junit.jupiter.api.Test
    void theContractsOwnFormatPlaceholderIsNotAProposal() {
        String echoedFormat = """
                ProposedExecutableSubGoals:
                - <subgoal description — MUST be directly executable as code changes>
                Criteria:
                * <acceptance criterion 1>
                - <next subgoal description>
                Message: echoing the format
                GoalKind: EXECUTABLE
                """;

        var parsed = parser.parse(echoedFormat);

        org.assertj.core.api.Assertions.assertThat(parsed.proposedExecutableSubGoals()).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void aRealDescriptionCarryingAngleBracketsStillParses() {
        String response = """
                ProposedExecutableSubGoals:
                - Change BookController.getBook to return ResponseEntity<Book> rather than a raw Map
                Message: ok
                GoalKind: EXECUTABLE
                """;

        var parsed = parser.parse(response);

        org.assertj.core.api.Assertions.assertThat(parsed.proposedExecutableSubGoals())
                .containsExactly("Change BookController.getBook to return ResponseEntity<Book> rather than a raw Map");
    }

    @org.junit.jupiter.api.Test
    void placeholderRecognitionIsAnchoredToTheWholeLine() {
        org.assertj.core.api.Assertions.assertThat(
                AnalysisResponseParser.isFormatPlaceholder("<next subgoal description>")).isTrue();
        org.assertj.core.api.Assertions.assertThat(
                AnalysisResponseParser.isFormatPlaceholder("Return ResponseEntity<Book> from getBook")).isFalse();
        org.assertj.core.api.Assertions.assertThat(
                AnalysisResponseParser.isFormatPlaceholder("<A> and then <B>")).isFalse();
    }
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

    @Test
    void parse_goalCriteria_null_whenNoCriteriaBlocks() {
        AnalysisOutcome out = parser.parse(FULL_RESPONSE);

        assertThat(out.goalCriteria()).isNull();
    }

    @Test
    void parse_goalCriteria_parsedWhenPresent() {
        String raw = """
                Findings:
                Hypotheses:
                ProposedExecutableSubGoals:
                - Add title validation to POST /books
                Criteria:
                * POST /books rejects title > 120 chars with HTTP 400
                * POST /books accepts title <= 120 chars with HTTP 201
                - Add ISBN validation to POST /books
                Criteria:
                * POST /books rejects non-13-digit ISBN with HTTP 400
                Message: Validation needed.
                GoalKind: EXECUTABLE
                PossiblyComplete: false
                """;
        AnalysisOutcome out = parser.parse(raw);

        assertThat(out.proposedExecutableSubGoals()).hasSize(2);
        assertThat(out.goalCriteria()).isNotNull();
        assertThat(out.goalCriteria().get("Add title validation to POST /books"))
                .containsExactly(
                        "POST /books rejects title > 120 chars with HTTP 400",
                        "POST /books accepts title <= 120 chars with HTTP 201");
        assertThat(out.goalCriteria().get("Add ISBN validation to POST /books"))
                .containsExactly("POST /books rejects non-13-digit ISBN with HTTP 400");
    }

    @Test
    void parse_goalCriteria_mixedSubGoals_onlyCriteriaHoldersPopulated() {
        // Second subgoal has no Criteria: block — should not appear in map
        String raw = """
                Findings:
                Hypotheses:
                ProposedExecutableSubGoals:
                - Add title validation to POST /books
                Criteria:
                * POST /books rejects title > 120 chars
                - Add ISBN validation to POST /books
                Message: Done.
                GoalKind: EXECUTABLE
                PossiblyComplete: false
                """;
        AnalysisOutcome out = parser.parse(raw);

        assertThat(out.proposedExecutableSubGoals()).hasSize(2);
        assertThat(out.goalCriteria()).isNotNull();
        assertThat(out.goalCriteria()).containsKey("Add title validation to POST /books");
        assertThat(out.goalCriteria()).doesNotContainKey("Add ISBN validation to POST /books");
    }

    @Test
    void parse_backwardsCompatible_noCriteriaSection_criteriaNull() {
        // Pre-existing responses without any Criteria: blocks must still parse correctly
        AnalysisOutcome out = parser.parse(FULL_RESPONSE);

        assertThat(out.findings()).hasSize(1);
        assertThat(out.proposedExecutableSubGoals()).hasSize(1);
        assertThat(out.goalCriteria()).isNull();
        assertThat(out.possiblyComplete()).isFalse();
    }

    // T2-BW: #InjectionThrottle parsing

    @Test
    void parse_injectionThrottle_falseWhenAbsent() {
        AnalysisOutcome out = parser.parse(FULL_RESPONSE);

        assertThat(out.injectionThrottle()).isFalse();
    }

    @Test
    void parse_injectionThrottle_trueWhenPresent() {
        String raw = """
                Findings:
                Hypotheses:
                ProposedExecutableSubGoals:
                - Add something
                Message: Throttled.
                GoalKind: EXECUTABLE
                PossiblyComplete: false
                #InjectionThrottle: REPETITION_DETECTED signal at high strength — further injection is counterproductive
                """;
        AnalysisOutcome out = parser.parse(raw);

        assertThat(out.injectionThrottle()).isTrue();
        assertThat(out.proposedExecutableSubGoals()).containsExactly("Add something");
    }
}
