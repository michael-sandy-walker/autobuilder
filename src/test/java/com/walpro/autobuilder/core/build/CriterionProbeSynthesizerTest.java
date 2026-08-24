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
import static org.mockito.Mockito.never;
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

        Optional<List<CriterionProbeSynthesizer.ProbeRequest>> seq = synthesizer.synthesizeWithModel(SPEC,
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
        assertThat(synthesizer.synthesizeWithModel(SPEC, assertion("GET /books returns HTTP 200")))
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

        assertThat(synthesizer.synthesizeWithModel(SPEC,
                assertion("POST /members with duplicate email returns HTTP 409"))).isEmpty();
    }

    @Test
    void rejectsASequenceThatEndsOnTheRightPathWithTheWrongMethod() {
        modelAnswers("1. GET /members -");
        assertThat(synthesizer.synthesizeWithModel(SPEC,
                assertion("POST /members with duplicate email returns HTTP 409"))).isEmpty();
    }

    // T4-CU slice 2: path-variable criteria. Grounding the id is the synthesizer's job — a
    // literal unused id for an absent-resource expectation, a captured value for one about a
    // resource that must exist.

    @Test
    void aGroundedPathVariableProbeIsAccepted() {
        modelAnswers("1. GET /books/999999 -");
        assertThat(synthesizer.synthesizeWithModel(SPEC,
                assertion("GET /books/{id} returns HTTP 404 for non-existing id"))).isPresent();
    }

    @Test
    void aCaptureReferenceSurvivesToExecution() {
        modelAnswers("""
                1. POST /books {"title":"Dune","isbn":"1234567890123"}
                2. GET /books/$1.id -
                """);
        var seq = synthesizer.synthesizeWithModel(SPEC,
                assertion("GET /books/{id} returns HTTP 200 with book body for existing id"));
        assertThat(seq).isPresent();
        assertThat(seq.get().get(1).path()).isEqualTo("/books/$1.id");
    }

    @Test
    void aGroundedPathOnTheWrongResourceIsStillRejected() {
        // The wildcard must not weaken the drift guard into accepting any two-segment path.
        modelAnswers("1. GET /members/999999 -");
        assertThat(synthesizer.synthesizeWithModel(SPEC,
                assertion("GET /books/{id} returns HTTP 404 for non-existing id"))).isEmpty();
    }

    @Test
    void pathPatternMatchingIsSegmentWise() {
        assertThat(CriterionProbeSynthesizer.pathMatches("/books/{}", "/books/999999")).isTrue();
        assertThat(CriterionProbeSynthesizer.pathMatches("/loans/{}/return", "/loans/7/return")).isTrue();
        assertThat(CriterionProbeSynthesizer.pathMatches("/books/{}", "/members/1")).isFalse();
        assertThat(CriterionProbeSynthesizer.pathMatches("/books/{}", "/books/1/reviews")).isFalse();
        assertThat(CriterionProbeSynthesizer.pathMatches("/books/{}", "/books/")).isFalse();
        assertThat(CriterionProbeSynthesizer.pathMatches("/books", "/books")).isTrue();
    }

    @Test
    void modelFailureIsNoProbe_neverAFailedCriterion() {
        when(llmClient.ask(any())).thenThrow(new RuntimeException("model down"));
        assertThat(synthesizer.synthesizeWithModel(SPEC, assertion("POST /members returns HTTP 409"))).isEmpty();
    }

    @Test
    void unparseableOrEmptyAnswerIsNoProbe() {
        modelAnswers("I would suggest testing this by hand.");
        assertThat(synthesizer.synthesizeWithModel(SPEC, assertion("POST /members returns HTTP 409"))).isEmpty();

        modelAnswers("");
        assertThat(synthesizer.synthesizeWithModel(SPEC, assertion("POST /books returns HTTP 201"))).isEmpty();
    }

    /**
     * T1-FU: this test previously asserted that an over-long answer is TRUNCATED to the cap, and
     * that assertion encoded the defect — a prefix of a sequence is a different experiment, not a
     * shorter version of the same one. Over budget is now an abstention.
     */
    @Test
    void anAnswerLongerThanTheBudgetIsRefusedRatherThanTruncated() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= CriterionProbeSynthesizer.MAX_REQUESTS + 4; i++) {
            sb.append(i).append(". POST /members {\"n\":").append(i).append("}\n");
        }
        modelAnswers(sb.toString());

        assertThat(synthesizer.synthesizeWithModel(SPEC, assertion("POST /members returns HTTP 409")))
                .isEmpty();
    }

    @Test
    void anAnswerThatExactlyFitsTheBudgetIsStillUsed() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= CriterionProbeSynthesizer.MAX_REQUESTS; i++) {
            sb.append(i).append(". POST /members {\"n\":").append(i).append("}\n");
        }
        modelAnswers(sb.toString());

        assertThat(synthesizer.synthesizeWithModel(SPEC, assertion("POST /members returns HTTP 409")))
                .get().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(CriterionProbeSynthesizer.MAX_REQUESTS);
    }

    /**
     * The field answer, verbatim from run 2026-08-24_22-14-05 at 22:45:31. The model got the
     * requirement RIGHT — four DIFFERENT books so the collision rule cannot fire first, one member,
     * four loans — and truncation to six kept the books, the member and ONE loan, so the probe
     * asked a member holding one loan to be refused a second. The application correctly answered
     * 201 and was recorded as contradicting its specification, blocking root closure 9-34 times per
     * run across four runs and three builds.
     */
    @Test
    void theBoundedCountAnswerFromTheFieldAbstainsInsteadOfAccusingCorrectCode() {
        modelAnswers("""
                1. POST /books {"title":"Book A","isbn":"1234567890123"}
                2. POST /books {"title":"Book B","isbn":"1234567890124"}
                3. POST /books {"title":"Book C","isbn":"1234567890125"}
                4. POST /books {"title":"Book D","isbn":"1234567890126"}
                5. POST /members {"name":"Member 1","email":"member1@example.com"}
                6. POST /loans {"bookId":$1.id,"memberId":$5.id}
                7. POST /loans {"bookId":$2.id,"memberId":$5.id}
                8. POST /loans {"bookId":$3.id,"memberId":$5.id}
                9. POST /loans {"bookId":$4.id,"memberId":$5.id}
                """);

        assertThat(synthesizer.synthesizeWithModel(
                SPEC, assertion("POST /loans returns HTTP 422 — if exceeded"))).isEmpty();
    }

    @Test
    void answersAreCachedPerCriterion() {
        modelAnswers("1. GET /books -");
        var a = assertion("GET /books returns HTTP 200");

        synthesizer.synthesizeWithModel(SPEC, a);
        synthesizer.synthesizeWithModel(SPEC, a);

        verify(llmClient, times(1)).ask(any());
    }

    @Test
    void anUnusableAnswerIsCachedToo_soAFruitlessCriterionIsAskedOnce() {
        modelAnswers("1. GET /books -");
        var a = assertion("POST /members with duplicate email returns HTTP 409");

        assertThat(synthesizer.synthesizeWithModel(SPEC, a)).isEmpty();
        assertThat(synthesizer.synthesizeWithModel(SPEC, a)).isEmpty();

        verify(llmClient, times(1)).ask(any());
    }

    // T4-CY: which path a criterion takes. The check this feeds was switched off because synthesis
    // called the model inside a cycle, so "the model is not reached" is the property to pin, not an
    // implementation detail.

    @Test
    void aShapeTheDeterministicPathCoversNeverReachesTheModel() {
        var seq = synthesizer.synthesize(SPEC,
                assertion("GET /members/{id} returns HTTP 404 when the member does not exist"), null);

        assertThat(seq).isPresent();
        assertThat(seq.get()).singleElement().satisfies(r ->
                assertThat(r.path()).isEqualTo("/members/" + DeterministicProbeSynthesizer.UNUSED_ID));
        verify(llmClient, never()).ask(any());
    }

    @Test
    void aShapeItDoesNotCoverAbstainsRatherThanAskingTheModel() {
        // Was "GET /books returns HTTP 200" until T4-DB gave collection reads a shape. A CREATE is
        // still uncovered on purpose — its body would have to be guessed, and a guessed body is
        // refused and read as a contradiction against a correct application.
        assertThat(synthesizer.synthesize(SPEC, assertion("POST /books returns HTTP 201"), null)).isEmpty();
        verify(llmClient, never()).ask(any());
    }

    @Test
    void theModelIsConsultedForTheRemainderOnlyWhenTheFallbackIsEnabled() {
        // Uses a CREATE: T4-DB gave collection reads a deterministic shape, so a GET is no longer
        // "the remainder" and the model would never be reached for it.
        modelAnswers("1. POST /books {\"title\":\"t\",\"isbn\":\"1234567890123\"}");
        synthesizer.setLlmFallbackEnabled(true);

        assertThat(synthesizer.synthesize(SPEC, assertion("POST /books returns HTTP 201"), null)).isPresent();
        verify(llmClient, times(1)).ask(any());
    }

    @Test
    void theFallbackDoesNotOverrideADeterministicAnswer() {
        modelAnswers("1. GET /members/1 -");
        synthesizer.setLlmFallbackEnabled(true);

        var seq = synthesizer.synthesize(SPEC, assertion("GET /members/{id} returns HTTP 404"), null);

        assertThat(seq).isPresent();
        assertThat(seq.get().get(0).path()).isEqualTo("/members/" + DeterministicProbeSynthesizer.UNUSED_ID);
        verify(llmClient, never()).ask(any());
    }
}
