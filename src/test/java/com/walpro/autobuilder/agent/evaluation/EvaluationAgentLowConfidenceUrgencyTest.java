package com.walpro.autobuilder.agent.evaluation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2-FJ: tests for {@link EvaluationAgent#lowConfidenceUrgency} — the banded LOW_CONFIDENCE
 * emission that replaced {@code min(1.0, (0.75 - score) / 0.20)}.
 *
 * <p>The readers' thresholds are restated here because they have no shared home; each is that
 * reader's own private constant, and the coupling is documented on the production method. If one
 * of them moves, {@link #eachLevelSitsWhereItsReadersCanActOnIt()} is the test that should be made
 * to fail — see the residual noted on the ticket.
 */
class EvaluationAgentLowConfidenceUrgencyTest {

    /** GoalExhaustionDetector counts LOW_CONFIDENCE toward exhaustion at this urgency. */
    private static final double EXHAUSTION_READS_AT = 0.80;
    /** DefaultProblemDetector raises a problem entry at this urgency. */
    private static final double PROBLEM_READS_AT = 0.70;
    /** DefaultInterpretationAgent / GraphQueryService elevate at this urgency. */
    private static final double ELEVATION_READS_AT = 0.20;

    // --- the three bands ---

    @Test
    void severeFailure_asksStronglyEnoughForEveryReader() {
        assertThat(EvaluationAgent.lowConfidenceUrgency(0.0)).isEqualTo(EvaluationAgent.SEVERE_URGENCY);
        assertThat(EvaluationAgent.lowConfidenceUrgency(0.55)).isEqualTo(EvaluationAgent.SEVERE_URGENCY);
        assertThat(EvaluationAgent.lowConfidenceUrgency(0.59)).isEqualTo(EvaluationAgent.SEVERE_URGENCY);
    }

    @Test
    void moderateFailure_elevatesWithoutRaisingAProblem() {
        assertThat(EvaluationAgent.lowConfidenceUrgency(0.60)).isEqualTo(EvaluationAgent.MODERATE_URGENCY);
        assertThat(EvaluationAgent.lowConfidenceUrgency(0.65)).isEqualTo(EvaluationAgent.MODERATE_URGENCY);
        assertThat(EvaluationAgent.lowConfidenceUrgency(0.71)).isEqualTo(EvaluationAgent.MODERATE_URGENCY);
    }

    /**
     * The near-miss band. The old formula emitted 0.05-0.20 here — below every reader, but ADDITIVE
     * on re-issue, so repeated near-misses accumulated toward the 3.0 cap. Its own comment said the
     * graduation existed "so near-misses don't accumulate"; declining to emit is what achieves that.
     */
    @Test
    void nearMiss_asksForNothing_sinceNoReaderCouldActOnIt() {
        assertThat(EvaluationAgent.lowConfidenceUrgency(0.72)).isNull();
        assertThat(EvaluationAgent.lowConfidenceUrgency(0.74)).isNull();
        assertThat(EvaluationAgent.lowConfidenceUrgency(0.7499)).isNull();
    }

    // --- the coupling the levels were chosen against ---

    @Test
    void eachLevelSitsWhereItsReadersCanActOnIt() {
        assertThat(EvaluationAgent.SEVERE_URGENCY)
                .as("severe must reach the exhaustion reader")
                .isGreaterThanOrEqualTo(EXHAUSTION_READS_AT);

        assertThat(EvaluationAgent.MODERATE_URGENCY)
                .as("moderate must elevate")
                .isGreaterThanOrEqualTo(ELEVATION_READS_AT);
        assertThat(EvaluationAgent.MODERATE_URGENCY)
                .as("moderate must NOT raise a problem entry — that is the severe band's job")
                .isLessThan(PROBLEM_READS_AT);
    }

    /**
     * Decay headroom is the reason the levels are not set AT their reader's threshold: a request
     * emitted exactly at the boundary falls under it on the first decay tick and goes inert.
     */
    @Test
    void neitherLevelSitsOnTheBoundaryItMustClear() {
        assertThat(EvaluationAgent.SEVERE_URGENCY).isGreaterThan(EXHAUSTION_READS_AT);
        assertThat(EvaluationAgent.MODERATE_URGENCY).isGreaterThan(ELEVATION_READS_AT);
    }

    /**
     * The band edges are the crossings the replaced arithmetic already drew, so the bands carrying
     * 98.7% of archived emissions keep their meaning: {@code (0.75 - score) / 0.20} reached 0.80 at
     * score 0.59 and 0.20 at score 0.71.
     */
    @Test
    void bandEdgesMatchTheCrossingsOfTheFormulaTheyReplaced() {
        assertThat((0.75 - EvaluationAgent.SEVERE_FAILURE_SCORE_CEILING) / 0.20)
                .isGreaterThanOrEqualTo(EXHAUSTION_READS_AT);
        assertThat((0.75 - EvaluationAgent.MODERATE_FAILURE_SCORE_CEILING) / 0.20)
                .isGreaterThanOrEqualTo(ELEVATION_READS_AT);
        // and one hundredth past each edge, the old formula fell below that reader
        assertThat((0.75 - (EvaluationAgent.SEVERE_FAILURE_SCORE_CEILING + 0.01)) / 0.20)
                .isLessThan(EXHAUSTION_READS_AT);
        assertThat((0.75 - (EvaluationAgent.MODERATE_FAILURE_SCORE_CEILING + 0.01)) / 0.20)
                .isLessThan(ELEVATION_READS_AT);
    }
}
