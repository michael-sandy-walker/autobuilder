package com.walpro.autobuilder.protocol.interpretation;

import com.walpro.autobuilder.agent.interpretation.model.InterpretationOutcome;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InterpretationResponseParserTest {

    private final InterpretationResponseParser parser = new InterpretationResponseParser();

    @Test
    void parse_nullOrBlank_returnsNoop() {
        assertThat(parser.parse(null).status()).isEqualTo("NOOP");
        assertThat(parser.parse("   ").status()).isEqualTo("NOOP");
    }

    @Test
    void parse_appliedWithSummary_extractsFields() {
        String response = """
                Status: APPLIED
                Summary: Evolution is targeting the same file with diminishing returns.
                Directives: Inject REPLAN_REQUESTED before next cycle.
                """;

        InterpretationOutcome outcome = parser.parse(response);

        assertThat(outcome.status()).isEqualTo("APPLIED");
        assertThat(outcome.summary()).isEqualTo("Evolution is targeting the same file with diminishing returns.");
        assertThat(outcome.directives()).isEqualTo("Inject REPLAN_REQUESTED before next cycle.");
        assertThat(outcome.proposedSignals()).isEmpty();
    }

    @Test
    void parse_noopWithNoDirectives_parsesCleanly() {
        String response = """
                Status: NOOP
                Summary: No notable patterns detected this cycle.
                """;

        InterpretationOutcome outcome = parser.parse(response);

        assertThat(outcome.status()).isEqualTo("NOOP");
        assertThat(outcome.summary()).isEqualTo("No notable patterns detected this cycle.");
        assertThat(outcome.directives()).isBlank();
        assertThat(outcome.proposedSignals()).isEmpty();
    }

    @Test
    void parse_signalReplanRequested_emitsSignal() {
        String response = """
                Status: APPLIED
                Summary: Stagnation pattern detected.
                Signal: REPLAN_REQUESTED
                """;

        InterpretationOutcome outcome = parser.parse(response);

        assertThat(outcome.proposedSignals()).hasSize(1);
        assertThat(outcome.proposedSignals().get(0).type()).isEqualTo(BasicSignals.REPLAN_REQUESTED);
    }

    @Test
    void parse_signalLowConfidence_emitsSignal() {
        String response = """
                Status: APPLIED
                Summary: Scores consistently below threshold.
                Signal: LOW_CONFIDENCE
                """;

        InterpretationOutcome outcome = parser.parse(response);

        assertThat(outcome.proposedSignals()).hasSize(1);
        assertThat(outcome.proposedSignals().get(0).type()).isEqualTo(BasicSignals.LOW_CONFIDENCE);
    }

    @Test
    void parse_signalDivergenceRequest_emitsSignal() {
        String response = """
                Status: APPLIED
                Summary: No progress across multiple cycles.
                Signal: DIVERGENCE_REQUEST
                """;

        InterpretationOutcome outcome = parser.parse(response);

        assertThat(outcome.proposedSignals()).hasSize(1);
        assertThat(outcome.proposedSignals().get(0).type()).isEqualTo(BasicSignals.DIVERGENCE_REQUEST);
    }

    @Test
    void parse_multipleSignals_allEmitted() {
        String response = """
                Status: APPLIED
                Summary: Both stagnation and low confidence detected.
                Signal: REPLAN_REQUESTED
                Signal: LOW_CONFIDENCE
                """;

        InterpretationOutcome outcome = parser.parse(response);

        assertThat(outcome.proposedSignals()).hasSize(2);
        assertThat(outcome.proposedSignals().stream().map(s -> s.type()))
                .containsExactlyInAnyOrder(BasicSignals.REPLAN_REQUESTED, BasicSignals.LOW_CONFIDENCE);
    }

    @Test
    void parse_unknownSignalName_ignored() {
        String response = """
                Status: APPLIED
                Summary: Pattern detected.
                Signal: UNKNOWN_SIGNAL_XYZ
                """;

        InterpretationOutcome outcome = parser.parse(response);

        assertThat(outcome.proposedSignals()).isEmpty();
    }

    @Test
    void parse_missingStatus_defaultsToApplied() {
        String response = "Summary: Something happened.";

        InterpretationOutcome outcome = parser.parse(response);

        assertThat(outcome.status()).isEqualTo("APPLIED");
        assertThat(outcome.summary()).isEqualTo("Something happened.");
    }

    @Test
    void parse_signalStrengthAndDecay_areReasonable() {
        String response = """
                Status: APPLIED
                Summary: Stagnation.
                Signal: REPLAN_REQUESTED
                """;

        var signal = parser.parse(response).proposedSignals().get(0);

        assertThat(signal.strength()).isGreaterThan(0.0).isLessThanOrEqualTo(1.0);
        assertThat(signal.decayPerCycle()).isGreaterThanOrEqualTo(0.0);
        assertThat(signal.source()).isEqualTo("interpretation");
    }
}
