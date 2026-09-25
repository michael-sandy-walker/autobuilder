package com.walpro.autobuilder.core.build;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
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

    /**
     * T1-FU, 2026-09-08: an abstention that cannot say what it dropped is not a readable record.
     * Read back over the post-fix corpus, this was the only refusal in the class that named no
     * criterion, so a log showed fifteen honest abstentions and no way to tell which fifteen
     * criteria went unverified without reconstructing them from the census line.
     */
    @Test
    void theBudgetRefusalNamesTheCriterionItRefused() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= CriterionProbeSynthesizer.MAX_REQUESTS + 4; i++) {
            sb.append(i).append(". POST /members {\"n\":").append(i).append("}\n");
        }
        modelAnswers(sb.toString());

        synthesizer.synthesizeWithModel(SPEC, assertion("POST /members returns HTTP 409"));

        assertThat(messages(appender)).anyMatch(m ->
                m.startsWith("T1-FU:") && m.contains("POST /members") && m.contains("409"));
    }

    /**
     * The abstention used to fall through to the endpoint check, so every over-budget answer was
     * ALSO reported as one that "does not end on the asserted endpoint" — a different failure from
     * the one that happened, and the reason a reader would reach for first.
     */
    @Test
    void theBudgetRefusalIsNotAlsoReportedAsADriftedEndpoint() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= CriterionProbeSynthesizer.MAX_REQUESTS + 4; i++) {
            sb.append(i).append(". POST /members {\"n\":").append(i).append("}\n");
        }
        modelAnswers(sb.toString());

        synthesizer.synthesizeWithModel(SPEC, assertion("POST /members returns HTTP 409"));

        assertThat(messages(appender)).noneMatch(m -> m.contains("does not end on the asserted endpoint"));
    }

    /** An answer carrying no request line at all states THAT, rather than borrowing another reason. */
    @Test
    void anAnswerWithNoRequestLineSaysSoAndNamesTheCriterion() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        modelAnswers("I cannot construct a sequence for this criterion.");

        synthesizer.synthesizeWithModel(SPEC, assertion("POST /members returns HTTP 409"));

        assertThat(messages(appender)).anyMatch(m ->
                m.contains("contained no request line") && m.contains("POST /members"));
        assertThat(messages(appender)).noneMatch(m -> m.contains("does not end on the asserted endpoint"));
    }

    private static List<String> messages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(CriterionProbeSynthesizer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
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

    /**
     * T1-FW, both halves. A requirement naming part of its own input must be exercised WITH it —
     * measured on a booted artifact: bare {@code GET /loans} answers 400 because a required
     * parameter is missing, while {@code GET /loans?memberId=1} answers 200. Probing the first and
     * reporting the second's requirement is what blocked closure 190 times across 12 archived runs.
     */
    @Test
    void aLiterallyStatedInputIsCarriedIntoTheRequest() {
        var a = BehaviouralCriterionParser.parse(
                "GET /loans?memberId=1 returns HTTP 200 with a JSON array of loans").orElseThrow();
        var seq = synthesizer.synthesize(null, a, null);

        assertThat(seq).isPresent();
        assertThat(seq.get().get(seq.get().size() - 1).path()).isEqualTo("/loans?memberId=1");
    }

    /**
     * ⚠ A pattern is not probed even where the requirement asserts the reference resolves to
     * NOTHING, and that restraint was bought by measurement rather than reasoned to. Grounding it
     * with an unused id was built and then withdrawn: of the archive's 37 pattern criteria, 10
     * assert 404 for an absent member and THE SPECIFICATION NEVER SAYS THAT — it states only
     * "list all loans for a member". The artifact answers 200 with an empty array and is right to.
     * Probing those would report a correct artifact as defective on a criterion the loop invented.
     */
    @Test
    void aPatternIsNotProbedEvenWhereItAssertsAnAbsentReference() {
        var a = BehaviouralCriterionParser.parse(
                "GET /loans?memberId={id} returns HTTP 404 when the member does not exist").orElseThrow();
        assertThat(synthesizer.synthesize(null, a, null)).isEmpty();
    }

    /**
     * ⚠ Where the requirement asserts the reference RESOLVES, the value comes from a resource
     * this sequence CREATES — never from an invented id, which would assert 200 against an
     * application entitled to answer 404. Without a specification to name the collection, the
     * abstention is exactly what it was.
     */
    @Test
    void aPatternIsNotProbedWhenNoSpecificationNamesTheCollection() {
        var a = BehaviouralCriterionParser.parse(
                "GET /loans?memberId={id} returns HTTP 200 with a JSON array of loans").orElseThrow();
        assertThat(synthesizer.synthesize(null, a, null)).isEmpty();
    }

    /**
     * T2-FL: with the specification naming the collection and the artifact supplying a body, the
     * reference is grounded by creating the resource and asking about the id that came back.
     * Measured need: 94 distinct query-pattern criteria across 103 archived trees, and the leaf
     * they blocked was the fourth of four on run 2026-09-22_15-59-33 — the one whose survival
     * kept a 23/23 artifact from closing its root.
     */
    @Test
    void aQueryReferenceIsGroundedByCreatingWhatItPointsAt(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        Path model = root.resolve("src/main/java/com/example/Member.java");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "public class Member { private String name; private String email; }");

        var a = BehaviouralCriterionParser.parse(
                "GET /loans?memberId={id} returns HTTP 200 with a JSON array of loans").orElseThrow();
        var seq = synthesizer.synthesize(SPEC_WITH_MEMBERS, a, root);

        assertThat(seq).isPresent();
        assertThat(seq.get()).hasSize(2);
        assertThat(seq.get().get(0).method()).isEqualTo("POST");
        assertThat(seq.get().get(0).path()).isEqualTo("/members");
        assertThat(seq.get().get(1).path()).isEqualTo("/loans?memberId=$1.id");
    }

    /**
     * A criterion about an ABSENT reference is not probed against a resource that was just
     * created — asserted as an OUTCOME, because that is what this test can honestly claim.
     *
     * <p>⚠ <b>Where the refusal comes from today, checked rather than assumed:</b> it is
     * {@code DeterministicProbeSynthesizer}, which abstains for {@code GET /loans -> 404} (no path
     * variable to ground, no body to invalidate) before {@code carryStatedInput} is reached at
     * all. Verified by driving the deterministic synthesizer directly. **So the success-status
     * gate in {@code groundQueryReference} is unreachable defence-in-depth on the current probe
     * shapes, not a live guard** — deleting it fails nothing, which was measured. It is kept
     * because it encodes the invariant the withdrawal was bought with (a setup that CREATES the
     * resource may only serve a criterion whose premise is that it exists), and a future shape
     * that builds a non-2xx sequence would otherwise reintroduce the defect silently.
     */
    @Test
    void aCriterionAboutAnAbsentReferenceIsNotProbedAgainstAResourceThatWasJustCreated(
            @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        Path model = root.resolve("src/main/java/com/example/Member.java");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "public class Member { private String name; }");

        var a = BehaviouralCriterionParser.parse(
                "GET /loans?memberId={id} returns HTTP 404 when the member does not exist").orElseThrow();

        assertThat(synthesizer.synthesize(SPEC_WITH_MEMBERS, a, root)).isEmpty();
    }

    /**
     * T3-CB: grounding follows the SPECIFICATION's statuses for the endpoint. Where it states a
     * success status of its own, a criterion asserting a different one is not probed against a
     * resource created for it; the same criterion under a specification stating none is grounded
     * (the test above).
     */
    @Test
    void aSuccessStatusTheSpecificationDoesNotStateIsNotGrounded(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        Path model = root.resolve("src/main/java/com/example/Member.java");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "public class Member { private String name; private String email; }");

        var a = BehaviouralCriterionParser.parse(
                "GET /loans?memberId={id} returns HTTP 200 with a JSON array of loans").orElseThrow();
        String statesAnother = SPEC_WITH_MEMBERS.replace("(list loans for a member)",
                "(list loans for a member; 206 with a partial list)");

        assertThat(synthesizer.synthesize(statesAnother, a, root)).isEmpty();
        assertThat(synthesizer.synthesize(SPEC_WITH_MEMBERS, a, root)).isPresent();
    }

    /** A parameter whose stem matches no collection the specification declares is not guessed at. */
    @Test
    void aReferenceNamingNoDeclaredCollectionIsNotGrounded(@org.junit.jupiter.api.io.TempDir Path root) {
        var a = BehaviouralCriterionParser.parse(
                "GET /loans?warehouseId={id} returns HTTP 200 with a JSON array of loans").orElseThrow();
        assertThat(synthesizer.synthesize(SPEC_WITH_MEMBERS, a, root)).isEmpty();
    }

    /**
     * T2-FL: a requirement about an input that is ABSENT is exercised by omitting it — one
     * request, no fixture. This shape is the only one available for a GET asserting a client
     * error, which is why 10 such criteria across 103 archived trees went unprobed, and one of
     * them was the second criterion on the leaf that kept run 2026-09-22_15-59-33's 23/23
     * artifact from closing its root.
     */
    @Test
    void anInputStatedAsAbsentIsExercisedByOmittingIt() {
        var a = BehaviouralCriterionParser.parse(
                "GET /loans?memberId={id} returns HTTP 400 with JSON body {\"message\": \"<reason>\"} "
                        + "when memberId is missing").orElseThrow();

        var seq = synthesizer.synthesize(SPEC_WITH_MEMBERS, a, null);

        assertThat(seq).isPresent();
        assertThat(seq.get()).hasSize(1);
        assertThat(seq.get().get(0).method()).isEqualTo("GET");
        assertThat(seq.get().get(0).path()).isEqualTo("/loans");   // the parameter is omitted
    }

    /**
     * ⚠ THE FALSE POSITIVE THE ANCHORING REJECTS, verbatim from the archive. "without" governs
     * the LOAN here, not the parameter, and reading it as an absent-input claim would probe a
     * bare request for a requirement about persistence.
     */
    @Test
    void anAbsenceWordGoverningSomethingOtherThanTheParameterIsNotReadAsAnAbsentInput() {
        String criterion = "The 422 response is produced without the loan being persisted "
                + "(subsequent GET /loans?memberId={id} does not list it)";
        assertThat(BehaviouralCriterionParser.statesAbsentInput(criterion, "memberId")).isFalse();
    }

    /**
     * Omitting a required parameter is a claim about the application REFUSING. Read for a success
     * status it would assert that the endpoint works without its input, which no criterion says.
     */
    @Test
    void anAbsentInputIsNotReadForASuccessStatus() {
        var a = BehaviouralCriterionParser.parse(
                "GET /loans?memberId={id} returns HTTP 200 when memberId is missing").orElseThrow();
        var seq = synthesizer.synthesize(SPEC_WITH_MEMBERS, a, null);

        // Not the bare request: whatever else happens, the omission shape must not claim this one.
        assertThat(seq).satisfiesAnyOf(
                x -> assertThat(x).isEmpty(),
                x -> assertThat(x.orElseThrow()).noneMatch(r -> "/loans".equals(r.path()) && r.body() == null));
    }

    private static final String SPEC_WITH_MEMBERS = """
            Members: POST /members (create), GET /members/{id} (404 if absent).
            Loans: POST /loans (lend a book), GET /loans?memberId={id} (list loans for a member).
            """;
}
