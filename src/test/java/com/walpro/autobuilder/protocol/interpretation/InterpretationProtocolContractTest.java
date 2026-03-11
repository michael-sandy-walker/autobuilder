package com.walpro.autobuilder.protocol.interpretation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InterpretationProtocolContractTest {

    // ── NOOP responses ──────────────────────────────────────────────────────────

    @Test
    void isValidResponse_noop_withSummary_isValid() {
        String response = """
                Status: NOOP
                Summary: No notable patterns detected this cycle.
                Directives: NONE
                """;
        assertThat(InterpretationProtocolContract.isValidResponse(response)).isTrue();
    }

    @Test
    void isValidResponse_noop_withoutDirectives_isStillValid() {
        // NOOP only requires status + summary
        String response = """
                Status: NOOP
                Summary: No notable patterns detected.
                """;
        assertThat(InterpretationProtocolContract.isValidResponse(response)).isTrue();
    }

    @Test
    void isValidResponse_noop_missingSummary_isInvalid() {
        String response = "Status: NOOP\n";
        assertThat(InterpretationProtocolContract.isValidResponse(response)).isFalse();
    }

    // ── APPLIED responses ────────────────────────────────────────────────────────

    @Test
    void isValidResponse_applied_withSignal_isValid() {
        String response = """
                Status: APPLIED
                Summary: Stagnation detected.
                Signal: REPLAN_REQUESTED
                """;
        assertThat(InterpretationProtocolContract.isValidResponse(response)).isTrue();
    }

    @Test
    void isValidResponse_applied_withConcreteDirectives_isValid() {
        String response = """
                Status: APPLIED
                Summary: Evolution targeting same file repeatedly.
                Directives: Inject REPLAN_REQUESTED before next evolution.
                """;
        assertThat(InterpretationProtocolContract.isValidResponse(response)).isTrue();
    }

    @Test
    void isValidResponse_applied_withDirectivesNone_isInvalid() {
        // APPLIED + Directives: NONE is a contradiction — rejects as filler
        String response = """
                Status: APPLIED
                Summary: Stagnation detected.
                Directives: NONE
                """;
        assertThat(InterpretationProtocolContract.isValidResponse(response)).isFalse();
    }

    @Test
    void isValidResponse_applied_noSignalNoDirectives_isInvalid() {
        // APPLIED with no actionable content is boilerplate
        String response = """
                Status: APPLIED
                Summary: The system is making steady progress with high confidence.
                """;
        assertThat(InterpretationProtocolContract.isValidResponse(response)).isFalse();
    }

    @Test
    void isValidResponse_applied_blankDirectives_isInvalid() {
        String response = """
                Status: APPLIED
                Summary: Repetition detected.
                Directives:
                """;
        assertThat(InterpretationProtocolContract.isValidResponse(response)).isFalse();
    }

    // ── Edge cases ────────────────────────────────────────────────────────────────

    @Test
    void isValidResponse_null_isInvalid() {
        assertThat(InterpretationProtocolContract.isValidResponse(null)).isFalse();
    }

    @Test
    void isValidResponse_blank_isInvalid() {
        assertThat(InterpretationProtocolContract.isValidResponse("   ")).isFalse();
    }

    @Test
    void isValidResponse_noStatus_isInvalid() {
        String response = "Summary: Something happened.\nDirectives: Do something.";
        assertThat(InterpretationProtocolContract.isValidResponse(response)).isFalse();
    }
}
