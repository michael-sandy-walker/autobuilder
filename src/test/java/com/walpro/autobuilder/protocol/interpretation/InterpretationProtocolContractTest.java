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

    // ── T2-EP: merge_payload file-body elision in the artifact history ──────────────

    @Test
    void mergePayloadContent_elidesFileBodyButKeepsIdentityFields() {
        String mergePayload = """
                {
                  "attemptId" : "cycle-42",
                  "cycleId" : "cycle-42",
                  "goalId" : "goal-root",
                  "subGoalId" : "goal-book-model",
                  "merged" : true,
                  "reasoning" : null,
                  "mergedContent" : "package com.example;\\n\\npublic class Book {\\n    private Long id;\\n}"
                }""";

        String compact = InterpretationProtocolContract.compactArtifactContent(mergePayload);

        assertThat(compact).contains("\"cycleId\" : \"cycle-42\"");
        assertThat(compact).contains("\"subGoalId\" : \"goal-book-model\"");
        assertThat(compact).contains("\"merged\" : true");
        assertThat(compact).doesNotContain("package com.example");
        assertThat(compact).doesNotContain("private Long id");
        assertThat(compact).contains("<file body elided");
        assertThat(compact).contains("5 lines>");
    }

    @Test
    void nonMergeArtifactContent_isLeftByteIdentical() {
        String evalPayload = """
                {
                  "goalProgress" : "STAGNATING",
                  "mutationState" : "REJECTED",
                  "scoreNow" : 0.35,
                  "message" : "No functional change to the repository."
                }""";

        assertThat(InterpretationProtocolContract.compactArtifactContent(evalPayload)).isEqualTo(evalPayload);
    }

    @Test
    void multipleMergePayloadsInOneString_allElided() {
        String twoMerges = """
                { "cycleId" : "c1", "mergedContent" : "line1\\nline2" }
                { "cycleId" : "c2", "mergedContent" : "only-one-line" }""";

        String compact = InterpretationProtocolContract.compactArtifactContent(twoMerges);

        assertThat(compact).doesNotContain("line1");
        assertThat(compact).doesNotContain("only-one-line");
        assertThat(compact).contains("<file body elided — 2 lines>");
        assertThat(compact).contains("<file body elided — 1 lines>");
        assertThat(compact).contains("\"cycleId\" : \"c1\"");
        assertThat(compact).contains("\"cycleId\" : \"c2\"");
    }

    @Test
    void contentWithEscapedQuotesInBody_doesNotOverrun() {
        String payload = "{ \"mergedContent\" : \"say \\\"hi\\\" then\\nreturn\", \"cycleId\" : \"c9\" }";

        String compact = InterpretationProtocolContract.compactArtifactContent(payload);

        assertThat(compact).contains("\"cycleId\" : \"c9\"");
        assertThat(compact).doesNotContain("say ");
        assertThat(compact).contains("<file body elided — 2 lines>");
    }

    @Test
    void nullOrEmptyContent_returnedUnchanged() {
        assertThat(InterpretationProtocolContract.compactArtifactContent(null)).isNull();
        assertThat(InterpretationProtocolContract.compactArtifactContent("")).isEmpty();
    }
}
