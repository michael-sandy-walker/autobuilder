package com.walpro.autobuilder.protocol.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationResponseParserTest {

    private final EvaluationResponseParser parser = new EvaluationResponseParser();

    // --- parseCriteriaAssessments ---

    @Test
    void parseCriteriaAssessments_nullContent_returnsEmpty() {
        assertThat(parser.parseCriteriaAssessments(null)).isEmpty();
    }

    @Test
    void parseCriteriaAssessments_blankContent_returnsEmpty() {
        assertThat(parser.parseCriteriaAssessments("   \n  ")).isEmpty();
    }

    @Test
    void parseCriteriaAssessments_noMatchingLines_returnsEmpty() {
        String content = """
                Score: 0.80
                MutationAcceptanceState: ACCEPTED
                GoalProgressState: PROGRESSING
                Message: Looks good.
                """;
        assertThat(parser.parseCriteriaAssessments(content)).isEmpty();
    }

    @Test
    void parseCriteriaAssessments_checkMarkLine_extracted() {
        String content = "[1] \u2713 POST /books returns HTTP 201";
        assertThat(parser.parseCriteriaAssessments(content))
                .containsExactly("[1] \u2713 POST /books returns HTTP 201");
    }

    @Test
    void parseCriteriaAssessments_crossMarkLine_extracted() {
        String content = "[2] \u2717 GET /books returns all books \u2014 endpoint not implemented";
        assertThat(parser.parseCriteriaAssessments(content))
                .containsExactly("[2] \u2717 GET /books returns all books \u2014 endpoint not implemented");
    }

    @Test
    void parseCriteriaAssessments_mixedLines_onlyCriteriaExtracted() {
        String content = """
                Score: 0.54
                MutationAcceptanceState: ACCEPTED
                GoalProgressState: STAGNATING
                CriteriaCheck:
                [1] \u2713 POST /books returns HTTP 201 with book body
                [2] \u2717 GET /books returns all previously created books \u2014 endpoint not implemented yet
                Message: POST /books implemented; GET /books missing.
                """;

        List<String> result = parser.parseCriteriaAssessments(content);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).contains("\u2713").contains("POST /books");
        assertThat(result.get(1)).contains("\u2717").contains("GET /books");
    }

    @Test
    void parseCriteriaAssessments_headerLineNotExtracted() {
        // "CriteriaCheck:" on its own must not match the [N] ✓/✗ pattern
        String content = "CriteriaCheck:\n[1] \u2713 some criterion";
        List<String> result = parser.parseCriteriaAssessments(content);
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).startsWith("[1]");
    }

    @Test
    void parseCriteriaAssessments_leadingWhitespaceOnLine_trimmedBeforeMatch() {
        String content = "   [1] \u2713 criterion with leading spaces";
        assertThat(parser.parseCriteriaAssessments(content))
                .containsExactly("[1] \u2713 criterion with leading spaces");
    }

    @Test
    void parseCriteriaAssessments_multiDigitIndex_extracted() {
        String content = "[12] \u2717 some late criterion \u2014 not done";
        assertThat(parser.parseCriteriaAssessments(content))
                .containsExactly("[12] \u2717 some late criterion \u2014 not done");
    }

    @Test
    void parseCriteriaAssessments_allCheckMarks_allExtracted() {
        String content = """
                [1] \u2713 criterion one
                [2] \u2713 criterion two
                [3] \u2713 criterion three
                """;
        assertThat(parser.parseCriteriaAssessments(content)).hasSize(3);
    }
}
