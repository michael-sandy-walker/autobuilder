package com.walpro.autobuilder.protocol.meta;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MetaDeliberationProtocolContractTest {

    // ── isValidResponse ──────────────────────────────────────────────────────

    @Test
    void validResponse_withAllFourHeaders_accepted() {
        String response = """
                Stop: false
                Mode: CONTINUE
                Rationale: Evaluation missing.
                Steps:
                - Step: 1
                  Need: NEXT_STEP
                  TargetRole: EVALUATION
                  Request: Evaluate the latest mutation.
                  STOP_IF: MutationAcceptanceState is ACCEPTED
                """;
        assertThat(MetaDeliberationProtocolContract.isValidResponse(response)).isTrue();
    }

    @Test
    void validResponse_stopTrue_stepsOmitted_accepted() {
        // LLM correctly omits Steps: when stopping — must NOT be rejected
        String response = """
                Stop: true
                Mode: NOOP
                Rationale: Nothing to do; system is idle.
                """;
        assertThat(MetaDeliberationProtocolContract.isValidResponse(response)).isTrue();
    }

    @Test
    void validResponse_stopTrue_stepsPresent_accepted() {
        // Steps: still allowed even when stopping
        String response = """
                Stop: true
                Mode: NOOP
                Rationale: Goal fulfilled.
                Steps:
                """;
        assertThat(MetaDeliberationProtocolContract.isValidResponse(response)).isTrue();
    }

    @Test
    void validResponse_stopFalse_stepsMissing_accepted() {
        // Steps: is conditionally expected but not validated here — the parser handles it;
        // the system falls through to signal-driven execution with an empty meta step list.
        String response = """
                Stop: false
                Mode: CONTINUE
                Rationale: Need to run evolution.
                """;
        assertThat(MetaDeliberationProtocolContract.isValidResponse(response)).isTrue();
    }

    @Test
    void validResponse_stopTrue_extraSpaceBeforeTrue_accepted() {
        // Extra whitespace between "stop:" and "true" — parser trims, validator must not reject
        String response = "Stop:   true\nMode: NOOP\nRationale: idle";
        assertThat(MetaDeliberationProtocolContract.isValidResponse(response)).isTrue();
    }

    @Test
    void invalidResponse_missingStop_rejected() {
        String response = """
                Mode: CONTINUE
                Rationale: No stop header.
                Steps:
                - Step: 1
                  Need: NEXT_STEP
                  TargetRole: EVOLUTION
                  Request: Do something.
                  STOP_IF: Never
                """;
        assertThat(MetaDeliberationProtocolContract.isValidResponse(response)).isFalse();
    }

    @Test
    void invalidResponse_missingMode_rejected() {
        String response = """
                Stop: false
                Rationale: No mode header.
                Steps:
                """;
        assertThat(MetaDeliberationProtocolContract.isValidResponse(response)).isFalse();
    }

    @Test
    void invalidResponse_missingRationale_rejected() {
        String response = """
                Stop: false
                Mode: CONTINUE
                Steps:
                """;
        assertThat(MetaDeliberationProtocolContract.isValidResponse(response)).isFalse();
    }

    @Test
    void invalidResponse_null_rejected() {
        assertThat(MetaDeliberationProtocolContract.isValidResponse(null)).isFalse();
    }

    @Test
    void invalidResponse_blank_rejected() {
        assertThat(MetaDeliberationProtocolContract.isValidResponse("   ")).isFalse();
    }

    @Test
    void validResponse_stopTrueNoSpace_accepted() {
        // cover "stop:true" (no space after colon)
        String response = "stop:true\nmode:noop\nrationale:done";
        assertThat(MetaDeliberationProtocolContract.isValidResponse(response)).isTrue();
    }

    // ── parser round-trip when Steps: omitted ───────────────────────────────

    @Test
    void parser_stopTrue_noStepsHeader_producesEmptyStepList() {
        String response = """
                Stop: true
                Mode: NOOP
                Rationale: System is idle; nothing to do this cycle.
                """;
        MetaDeliberationResponseParser parser = new MetaDeliberationResponseParser();
        MetaDeliberationPlan plan = parser.parse(response);

        assertThat(plan.stop()).isTrue();
        assertThat(plan.steps()).isEmpty();
        assertThat(plan.rationale()).isEqualTo("System is idle; nothing to do this cycle.");
    }
}
