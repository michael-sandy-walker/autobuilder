package com.walpro.autobuilder.protocol.evaluation;

import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EvaluationReponseParserTest {

    @Test
    void parseScoreExtractsNumericValue() {
        EvaluationResponseParser parser = new EvaluationResponseParser();
        String content = """
                Score: 0.85
                MutationAcceptanceState: ACCEPTED
                """;

        assertEquals(0.85, parser.parse(content).score(), 1e-6);
    }

    @Test
    void parseScore_missingOrInvalid_isNull() {
        EvaluationResponseParser parser = new EvaluationResponseParser();

        assertNull(parser.parse("no score here").score());
    }

    @Test
    void parseMutationStateParsesAcceptedAndRejected() {
        EvaluationResponseParser parser = new EvaluationResponseParser();

        String accepted = "MutationAcceptanceState: ACCEPTED";
        String rejected = "MutationAcceptanceState: REJECTED";
        String unknown  = "MutationAcceptanceState: SOMETHING_ELSE";

        assertEquals(MutationAcceptanceState.ACCEPTED, parser.parse(accepted).mutationState());
        assertEquals(MutationAcceptanceState.REJECTED, parser.parse(rejected).mutationState());
        // default/fail-safe is REJECTED
        assertEquals(MutationAcceptanceState.REJECTED, parser.parse(unknown).mutationState());
    }

    @Test
    void parseGoalProgressParsesKnownStatesAndDefaults() {
        EvaluationResponseParser parser = new EvaluationResponseParser();

        String progressing = "GoalProgressState: PROGRESSING";
        String stagnating  = "GoalProgressState: STAGNATING";
        String notEval     = "GoalProgressState: NOT_EVALUATED";
        String unknown     = "GoalProgressState: SOMETHING_ELSE";

        assertEquals(GoalProgressState.PROGRESSING,   parser.parse(progressing).goalProgress());
        assertEquals(GoalProgressState.STAGNATING,    parser.parse(stagnating).goalProgress());
        assertEquals(GoalProgressState.NOT_EVALUATED, parser.parse(notEval).goalProgress());
        // default/fail-safe is NOT_EVALUATED
        assertEquals(GoalProgressState.NOT_EVALUATED, parser.parse(unknown).goalProgress());
    }

    @Test
    void parseMessageExtractsMessageLineOrUsesFallback() {
        EvaluationResponseParser parser = new EvaluationResponseParser();

        String withMessage = """
                Score: 0.5
                Message: This is a detailed explanation.
                """;
        String withoutMessage = "Score: 0.5";

        assertEquals("This is a detailed explanation.", parser.parse(withMessage).feedbackMessage());
        assertEquals("No explanation provided.",        parser.parse(withoutMessage).feedbackMessage());
    }

    @Test
    void parseRecommendationExtractsOptionalRecommendation() {
        EvaluationResponseParser parser = new EvaluationResponseParser();

        String withRec = """
                Score: 0.5
                Recommendation: Consider refining the evaluation prompt.
                """;
        String withoutRec = "Score: 0.5";

        assertEquals(
                "Consider refining the evaluation prompt.",
                parser.parse(withRec).recommendation()
        );
        assertNull(parser.parse(withoutRec).recommendation());
    }
}
