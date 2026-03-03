package com.walpro.autobuilder.protocol.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

class EvaluationResponseParserScoreValidityContractTest {

    private final EvaluationResponseParser parser = new EvaluationResponseParser();

    @Test
    void parse_whenScoreMissing_setsScoreNull() {
        var out = parser.parse("""
                #Evaluation
                MutationAcceptanceState: ACCEPTED
                GoalProgressState: PROGRESSING
                Message: ok
                """);

        assertThat(out.score()).isNull();
        assertThat(out.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(out.goalProgress()).isEqualTo(GoalProgressState.PROGRESSING);
        assertThat(out.feedbackMessage()).contains("ok");
    }

    @Test
    void parse_whenScoreNotANumber_setsScoreNull() {
        var out = parser.parse("""
                #Evaluation
                Score: not-a-number
                MutationAcceptanceState: ACCEPTED
                GoalProgressState: PROGRESSING
                Message: ok
                """);

        assertThat(out.score()).isNull();
    }

    @Test
    void parse_whenScoreNaNOrInfinity_setsScoreNull() {
        assertThat(parser.parse("Score: NaN").score()).isNull();
        assertThat(parser.parse("Score: Infinity").score()).isNull();
        assertThat(parser.parse("Score: -Infinity").score()).isNull();
    }

    @Test
    void parse_whenScoreOutOfRange_setsScoreNull() {
        assertThat(parser.parse("Score: -0.01").score()).isNull();
        assertThat(parser.parse("Score: 1.0001").score()).isNull();
        assertThat(parser.parse("Score: 2").score()).isNull();
    }

    @Test
    void parse_whenScoreValidInRange_setsScoreValue() {
        assertThat(parser.parse("Score: 0.0").score()).isEqualTo(0.0);
        assertThat(parser.parse("Score: 0.42").score()).isEqualTo(0.42);
        assertThat(parser.parse("Score: 1.0").score()).isEqualTo(1.0);
    }

    @Test
    void parse_prefersFirstValidScoreLine() {
        var out = parser.parse("""
                Score: not-a-number
                Score: 1.1
                Score: 0.77
                Score: 0.66
                """);

        assertThat(out.score()).isEqualTo(0.77);
    }
}