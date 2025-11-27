package com.walpro.autobuilder.protocol.evaluation;

import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EvaluationResponseParserTest {

    private double parseScore(EvaluationResponseParser parser, String content) throws Exception {
        Method m = EvaluationResponseParser.class
                .getDeclaredMethod("parseScore", String.class);
        m.setAccessible(true);
        return (double) m.invoke(parser, content);
    }

    private MutationAcceptanceState parseMutationState(EvaluationResponseParser parser, String content) throws Exception {
        Method m = EvaluationResponseParser.class
                .getDeclaredMethod("parseMutationState", String.class);
        m.setAccessible(true);
        return (MutationAcceptanceState) m.invoke(parser, content);
    }

    private GoalProgressState parseGoalProgress(EvaluationResponseParser parser, String content) throws Exception {
        Method m = EvaluationResponseParser.class
                .getDeclaredMethod("parseGoalProgress", String.class);
        m.setAccessible(true);
        return (GoalProgressState) m.invoke(parser, content);
    }

    private String parseMessage(EvaluationResponseParser parser, String content) throws Exception {
        Method m = EvaluationResponseParser.class
                .getDeclaredMethod("parseMessage", String.class);
        m.setAccessible(true);
        return (String) m.invoke(parser, content);
    }

    private String parseRecommendation(EvaluationResponseParser parser, String content) throws Exception {
        Method m = EvaluationResponseParser.class
                .getDeclaredMethod("parseRecommendation", String.class);
        m.setAccessible(true);
        return (String) m.invoke(parser, content);
    }

    @Test
    void parseScoreExtractsNumericValue() throws Exception {
        EvaluationResponseParser parser = new EvaluationResponseParser();
        String content = """
                Score: 0.85
                MutationAcceptanceState: ACCEPTED
                """;

        double score = parseScore(parser, content);

        assertEquals(0.85, score, 1e-6);
    }

    @Test
    void parseScoreDefaultsToMinusOneOnMissingOrInvalid() throws Exception {
        EvaluationResponseParser parser = new EvaluationResponseParser();

        assertEquals(Double.NaN, parseScore(parser, "no score here"), 1e-6);
        assertEquals(Double.NaN, parseScore(parser, "Score: not-a-number"), 1e-6);
    }

    @Test
    void parseMutationStateParsesAcceptedAndRejected() throws Exception {
        EvaluationResponseParser parser = new EvaluationResponseParser();

        String accepted = "MutationAcceptanceState: ACCEPTED";
        String rejected = "MutationAcceptanceState: REJECTED";
        String unknown  = "MutationAcceptanceState: SOMETHING_ELSE";

        assertEquals(MutationAcceptanceState.ACCEPTED, parseMutationState(parser, accepted));
        assertEquals(MutationAcceptanceState.REJECTED, parseMutationState(parser, rejected));
        // default/fail-safe is REJECTED
        assertEquals(MutationAcceptanceState.REJECTED, parseMutationState(parser, unknown));
    }

    @Test
    void parseGoalProgressParsesKnownStatesAndDefaults() throws Exception {
        EvaluationResponseParser parser = new EvaluationResponseParser();

        String progressing = "GoalProgressState: PROGRESSING";
        String stagnating  = "GoalProgressState: STAGNATING";
        String notEval     = "GoalProgressState: NOT_EVALUATED";
        String unknown     = "GoalProgressState: SOMETHING_ELSE";

        assertEquals(GoalProgressState.PROGRESSING, parseGoalProgress(parser, progressing));
        assertEquals(GoalProgressState.STAGNATING, parseGoalProgress(parser, stagnating));
        assertEquals(GoalProgressState.NOT_EVALUATED, parseGoalProgress(parser, notEval));
        // default/fail-safe is NOT_EVALUATED
        assertEquals(GoalProgressState.NOT_EVALUATED, parseGoalProgress(parser, unknown));
    }

    @Test
    void parseMessageExtractsMessageLineOrUsesFallback() throws Exception {
        EvaluationResponseParser parser = new EvaluationResponseParser();

        String withMessage = """
                Score: 0.5
                Message: This is a detailed explanation.
                """;
        String withoutMessage = "Score: 0.5";

        assertEquals("This is a detailed explanation.", parseMessage(parser, withMessage));
        assertEquals("No explanation provided.", parseMessage(parser, withoutMessage));
    }

    @Test
    void parseRecommendationExtractsOptionalRecommendation() throws Exception {
        EvaluationResponseParser parser = new EvaluationResponseParser();

        String withRec = """
                Score: 0.5
                Recommendation: Consider refining the evaluation prompt.
                """;
        String withoutRec = "Score: 0.5";

        assertEquals(
                "Consider refining the evaluation prompt.",
                parseRecommendation(parser, withRec)
        );
        assertNull(parseRecommendation(parser, withoutRec));
    }
}
