package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-CU: the model supplies the request, never the verdict. These tests pin the two properties
 * that make that safe — a sequence is only used when it ends on the endpoint the criterion is
 * about, and every failure mode yields "no probe" rather than "failed criterion".
 */
class CriterionProbeSynthesizerTest {

    private static final String SPEC = "Members: POST /members (email must be unique — 409 on duplicate).";

    private final LLMClient llmClient = mock(LLMClient.class);
    private final CriterionProbeSynthesizer synthesizer = new CriterionProbeSynthesizer(llmClient);

    private static BehaviouralCriterionParser.Assertion assertion(String text) {
        return BehaviouralCriterionParser.parse(text).orElseThrow();
    }

    private void modelAnswers(String body) {
        ChatResponse response = mock(ChatResponse.class);
        when(response.getFirstContent()).thenReturn(body);
        when(llmClient.ask(any())).thenReturn(ResponseEntity.ok(response));
    }

    @Test
    void buildsTheSetupSequenceAStatefulCriterionNeeds() {
        modelAnswers("""
                1. POST /members {"name":"Ann","email":"dup@example.com"}
                2. POST /members {"name":"Bob","email":"dup@example.com"}
                """);

        Optional<List<CriterionProbeSynthesizer.ProbeRequest>> seq = synthesizer.synthesize(SPEC,
                assertion("POST /members with duplicate email returns HTTP 409"));

        assertThat(seq).isPresent();
        assertThat(seq.get()).hasSize(2);
        assertThat(seq.get().get(0).body()).contains("dup@example.com");
        // The asserted request is the LAST one; everything before it only builds state.
        assertThat(seq.get().get(1).method()).isEqualTo("POST");
        assertThat(seq.get().get(1).path()).isEqualTo("/members");
    }

    @Test
    void aBodylessRequestIsCarriedAsNull() {
        modelAnswers("1. GET /books -");
        assertThat(synthesizer.synthesize(SPEC, assertion("GET /books returns HTTP 200")))
                .get().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .singleElement()
                .extracting("body").isNull();
    }

    @Test
    void rejectsASequenceThatDriftsOntoAnotherEndpoint() {
        // The guard that matters: a drifted answer would otherwise produce a confident verdict
        // about behaviour the criterion never claimed.
        modelAnswers("""
                1. POST /members {"name":"Ann","email":"a@example.com"}
                2. GET /books -
                """);

        assertThat(synthesizer.synthesize(SPEC,
                assertion("POST /members with duplicate email returns HTTP 409"))).isEmpty();
    }

    @Test
    void rejectsASequenceThatEndsOnTheRightPathWithTheWrongMethod() {
        modelAnswers("1. GET /members -");
        assertThat(synthesizer.synthesize(SPEC,
                assertion("POST /members with duplicate email returns HTTP 409"))).isEmpty();
    }

    @Test
    void modelFailureIsNoProbe_neverAFailedCriterion() {
        when(llmClient.ask(any())).thenThrow(new RuntimeException("model down"));
        assertThat(synthesizer.synthesize(SPEC, assertion("POST /members returns HTTP 409"))).isEmpty();
    }

    @Test
    void unparseableOrEmptyAnswerIsNoProbe() {
        modelAnswers("I would suggest testing this by hand.");
        assertThat(synthesizer.synthesize(SPEC, assertion("POST /members returns HTTP 409"))).isEmpty();

        modelAnswers("");
        assertThat(synthesizer.synthesize(SPEC, assertion("GET /books returns HTTP 200"))).isEmpty();
    }

    @Test
    void sequenceLengthIsCapped() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= CriterionProbeSynthesizer.MAX_REQUESTS + 4; i++) {
            sb.append(i).append(". POST /members {\"n\":").append(i).append("}\n");
        }
        modelAnswers(sb.toString());

        assertThat(synthesizer.synthesize(SPEC, assertion("POST /members returns HTTP 409")))
                .get().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(CriterionProbeSynthesizer.MAX_REQUESTS);
    }

    @Test
    void answersAreCachedPerCriterion() {
        modelAnswers("1. GET /books -");
        var a = assertion("GET /books returns HTTP 200");

        synthesizer.synthesize(SPEC, a);
        synthesizer.synthesize(SPEC, a);

        verify(llmClient, times(1)).ask(any());
    }

    @Test
    void anUnusableAnswerIsCachedToo_soAFruitlessCriterionIsAskedOnce() {
        modelAnswers("1. GET /books -");
        var a = assertion("POST /members with duplicate email returns HTTP 409");

        assertThat(synthesizer.synthesize(SPEC, a)).isEmpty();
        assertThat(synthesizer.synthesize(SPEC, a)).isEmpty();

        verify(llmClient, times(1)).ask(any());
    }
}
