package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.build.CriterionProbeSynthesizer.ProbeRequest;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-CY: the property under test is that every shape this project's specifications state can be
 * probed without a model call, and that everything else abstains rather than approximates. The
 * mechanism was switched off on cost once already; a synthesis path that quietly reaches for the
 * model would put it straight back where it was.
 */
class DeterministicProbeSynthesizerTest {

    @TempDir
    Path artifactRoot;

    private static BehaviouralCriterionParser.Assertion assertion(String text) {
        return BehaviouralCriterionParser.parse(text).orElseThrow();
    }

    private Path withModel(String className, String body) throws IOException {
        Path dir = artifactRoot.resolve("src/main/java/com/example");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(className + ".java"), body);
        return artifactRoot;
    }

    private static List<ProbeRequest> sequence(Optional<List<ProbeRequest>> result) {
        assertThat(result).isPresent();
        return result.get();
    }

    // ---- rejected input -------------------------------------------------------------------

    @Test
    void anInvalidInputCriterionIsProbedWithAnEmptyBody() {
        List<ProbeRequest> seq = sequence(DeterministicProbeSynthesizer.synthesize(
                assertion("POST /books with invalid input returns HTTP 400"), artifactRoot));

        assertThat(seq).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo("POST");
            assertThat(r.path()).isEqualTo("/books");
            // Whatever the required fields are, an empty object satisfies none of them.
            assertThat(r.body()).isEqualTo("{}");
        });
    }

    @Test
    void aBusinessRuleStatusIsNotProbedAsIfItWereInvalidInput() throws IOException {
        // The live specification's only 422 is "a member may hold at most 3 active loans". An empty
        // body would be refused as invalid input long before any limit was reached, and reporting
        // that 400 as a missing 422 is a false contradiction.
        assertThat(DeterministicProbeSynthesizer.synthesize(
                assertion("POST /loans returns HTTP 422 — if exceeded"),
                withModel("Loan", "class Loan { private Long bookId; private Long memberId; }")))
                .isEmpty();
    }

    @Test
    void aParameterizedInvalidInputCriterionCreatesTheResourceFirst() throws IOException {
        // Without the setup an application that resolves the path before validating the body would
        // answer 404 and be reported as contradicting a criterion about validation.
        List<ProbeRequest> seq = sequence(DeterministicProbeSynthesizer.synthesize(
                assertion("PUT /books/{id} with invalid input returns HTTP 400"),
                withModel("Book", "class Book { private String title; private int pages; }")));

        assertThat(seq).hasSize(2);
        assertThat(seq.get(0).method()).isEqualTo("POST");
        assertThat(seq.get(0).path()).isEqualTo("/books");
        assertThat(seq.get(0).body()).contains("\"title\"").contains("\"pages\"");
        assertThat(seq.get(1).path()).isEqualTo("/books/$1.id");
        assertThat(seq.get(1).body()).isEqualTo("{}");
    }

    @Test
    void aParameterizedInvalidInputCriterionAbstainsWhenNothingCanBeCreated() {
        // No model source, so the setup POST would send {} and create nothing — and a probe against
        // a resource that was never created reports the wrong reason.
        assertThat(DeterministicProbeSynthesizer.synthesize(
                assertion("PUT /widgets/{id} with invalid input returns HTTP 400"), artifactRoot))
                .isEmpty();
    }

    @Test
    void aBodylessVerbHasNoInvalidInputToSend() {
        assertThat(DeterministicProbeSynthesizer.synthesize(
                assertion("GET /books with an invalid filter returns HTTP 400"), artifactRoot))
                .isEmpty();
    }

    // ---- absent resource -----------------------------------------------------------------

    @Test
    void anAbsentResourceCriterionAddressesAnIdNothingCreated() {
        List<ProbeRequest> seq = sequence(DeterministicProbeSynthesizer.synthesize(
                assertion("GET /members/{id} returns HTTP 404 when the member does not exist"),
                artifactRoot));

        assertThat(seq).singleElement().satisfies(r -> {
            assertThat(r.path()).isEqualTo("/members/" + DeterministicProbeSynthesizer.UNUSED_ID);
            assertThat(r.body()).isNull();
            assertThat(r.method()).isEqualTo("GET");
        });
    }

    @Test
    void anAbsentSubResourceActionIsReachable() {
        // The requirement measured permanently out of reach before path variables were admitted.
        List<ProbeRequest> seq = sequence(DeterministicProbeSynthesizer.synthesize(
                assertion("POST /loans/{id}/return returns HTTP 404 for an unknown loan"),
                artifactRoot));

        assertThat(seq).singleElement().satisfies(r ->
                assertThat(r.path()).isEqualTo("/loans/" + DeterministicProbeSynthesizer.UNUSED_ID + "/return"));
    }

    @Test
    void aCollectionPathIsNotProbedForAbsence() {
        // "This collection answers 404" is a claim about a route, and the criterion does not say
        // which request would test it.
        assertThat(DeterministicProbeSynthesizer.synthesize(
                assertion("GET /books returns HTTP 404"), artifactRoot)).isEmpty();
    }

    // ---- absent reference carried in the body (T4-DB) --------------------------------------

    /** The real Loan shape: the reference fields are ordinary numeric model fields. */
    private Path withLoanModel() throws IOException {
        return withModel("Loan", """
                package com.example;
                public class Loan {
                    private Long id;
                    private Long bookId;
                    private Long memberId;
                    private boolean returned;
                }
                """);
    }

    @Test
    void anAbsentReferenceNamedByTheCriterionIsPointedAtNothing() throws IOException {
        // Smoke check [14], which failed in all three frozen runs of 2026-07-30 and had no probe:
        // the reference is in the body, so requiring a parameterized path put it out of reach.
        List<ProbeRequest> seq = sequence(DeterministicProbeSynthesizer.synthesize(
                assertion("POST /loans returns HTTP 404 when bookId does not exist"), withLoanModel()));

        assertThat(seq).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo("POST");
            assertThat(r.path()).isEqualTo("/loans");
            assertThat(r.body()).contains("\"bookId\":999999999");
            // The server-assigned identifier is not supplied by a client creating a resource.
            assertThat(r.body()).doesNotContain("\"id\"");
        });
    }

    @Test
    void theCriterionTextARealRunActuallyWroteIsProbed() throws IOException {
        // Verbatim from run 2026-07-30_04-56-38's goal tree, where it sat SUBSUMED while smoke
        // check [14] failed. Invented phrasing would have proved nothing about the field.
        List<ProbeRequest> seq = sequence(DeterministicProbeSynthesizer.synthesize(
                assertion("The endpoint returns HTTP 404 when POST /loans has non-existent bookId"),
                withLoanModel()));

        assertThat(seq).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo("POST");
            assertThat(r.path()).isEqualTo("/loans");
            assertThat(r.body()).contains("\"bookId\":999999999");
        });
    }

    @Test
    void everyReferenceTheCriterionNamesIsPointedAtNothing() throws IOException {
        List<ProbeRequest> seq = sequence(DeterministicProbeSynthesizer.synthesize(
                assertion("POST /loans returns HTTP 404 when bookId or memberId does not exist"),
                withLoanModel()));

        assertThat(seq.get(0).body()).contains("\"bookId\":999999999");
        assertThat(seq.get(0).body()).contains("\"memberId\":999999999");
    }

    @Test
    void aFieldTheCriterionDoesNotNameKeepsItsPlaceholder() throws IOException {
        List<ProbeRequest> seq = sequence(DeterministicProbeSynthesizer.synthesize(
                assertion("POST /loans returns HTTP 404 when bookId does not exist"), withLoanModel()));

        assertThat(seq.get(0).body()).contains("\"memberId\":1");
    }

    @Test
    void aCriterionNamingNoFieldOfTheBodyYieldsNoProbe() throws IOException {
        // There is no reference under test, and choosing one would manufacture a contradiction.
        assertThat(DeterministicProbeSynthesizer.synthesize(
                assertion("POST /loans returns HTTP 404 when the record is missing"), withLoanModel()))
                .isEmpty();
    }

    @Test
    void fieldNamesMatchAsWholeWordsOnly() throws IOException {
        // A criterion mentioning "id" must not claim bookId and memberId along with it.
        assertThat(DeterministicProbeSynthesizer.synthesize(
                assertion("POST /loans returns HTTP 404 when the id is unknown"), withLoanModel()))
                .isEmpty();
    }

    @Test
    void aTextualReferenceKeepsItsDeclaredShape() throws IOException {
        // A string field given a bare number fails to bind, and the endpoint then answers 400 for
        // a reason that has nothing to do with the requirement under test.
        Path root = withModel("Reservation", """
                package com.example;
                public class Reservation {
                    private String reference;
                }
                """);

        List<ProbeRequest> seq = sequence(DeterministicProbeSynthesizer.synthesize(
                assertion("POST /reservations returns HTTP 404 when reference does not exist"), root));

        assertThat(seq.get(0).body()).contains("\"reference\":\"999999999\"");
    }

    @Test
    void withoutADerivableModelThereIsNoProbe() {
        // No model means no field names, so nothing can be identified as the reference.
        assertThat(DeterministicProbeSynthesizer.synthesize(
                assertion("POST /loans returns HTTP 404 when bookId does not exist"), artifactRoot))
                .isEmpty();
    }

    @Test
    void aVerbCarryingNoBodyStillYieldsNoProbeOnACollection() {
        // The original rule survives where it was right: "this collection answers 404" is a claim
        // about a route, and a GET carries no body in which a reference could be placed.
        assertThat(DeterministicProbeSynthesizer.synthesize(
                assertion("GET /books returns HTTP 404"), artifactRoot)).isEmpty();
    }

    // ---- collision -----------------------------------------------------------------------

    @Test
    void aCollisionCriterionSendsTheSameCreatedBodyTwice() throws IOException {
        List<ProbeRequest> seq = sequence(DeterministicProbeSynthesizer.synthesize(
                assertion("POST /members with a duplicate email returns HTTP 409"),
                withModel("Member", "class Member { private String name; private String email; }")));

        assertThat(seq).hasSize(2);
        assertThat(seq.get(0)).isEqualTo(seq.get(1));
        assertThat(seq.get(1).path()).isEqualTo("/members");
        assertThat(seq.get(1).body()).contains("\"email\"");
    }

    @Test
    void aCollisionCriterionAbstainsWithoutADerivableBody() {
        // Two empty objects would be refused as invalid input long before anything could collide,
        // and reporting that 400 as a missing 409 is a false contradiction.
        assertThat(DeterministicProbeSynthesizer.synthesize(
                assertion("POST /members with a duplicate email returns HTTP 409"), artifactRoot))
                .isEmpty();
    }

    @Test
    void aCollisionCriterionOnANonCreatingVerbAbstains() throws IOException {
        assertThat(DeterministicProbeSynthesizer.synthesize(
                assertion("PUT /members/{id} with a duplicate email returns HTTP 409"),
                withModel("Member", "class Member { private String email; }")))
                .isEmpty();
    }

    // ---- what it deliberately will not do ------------------------------------------------

    @Test
    void successStatusesAreNotProbed() throws IOException {
        // A 2xx probe needs values satisfying constraints stated in prose; type-derived
        // placeholders do not, and such a probe reports a correct artifact as broken.
        Path root = withModel("Book", "class Book { private String isbn; }");
        assertThat(DeterministicProbeSynthesizer.synthesize(
                assertion("POST /books returns HTTP 201"), root)).isEmpty();
        assertThat(DeterministicProbeSynthesizer.synthesize(
                assertion("GET /books returns HTTP 200"), root)).isEmpty();
    }

    @Test
    void statusesWithNoDerivableInputAbstain() {
        assertThat(DeterministicProbeSynthesizer.synthesize(
                assertion("GET /admin/books returns HTTP 403 for a non-admin"), artifactRoot)).isEmpty();
        assertThat(DeterministicProbeSynthesizer.synthesize(
                assertion("POST /books returns HTTP 500 on a storage failure"), artifactRoot)).isEmpty();
    }

    @Test
    void aPathWithTwoVariablesAbstains() throws IOException {
        // Two resources in a known order, and the criterion says which collection neither belongs to.
        assertThat(DeterministicProbeSynthesizer.synthesize(
                assertion("GET /members/{mid}/loans/{lid} returns HTTP 404 for an unknown loan"),
                withModel("Member", "class Member { private String name; }")))
                .isEmpty();
    }

    @Test
    void theServerAssignedIdentifierIsNotSuppliedOnCreation() throws IOException {
        List<ProbeRequest> seq = sequence(DeterministicProbeSynthesizer.synthesize(
                assertion("POST /members with a duplicate email returns HTTP 409"),
                withModel("Member", "class Member { private Long id; private String email; }")));

        assertThat(seq.get(0).body()).doesNotContain("\"id\"").contains("\"email\"");
    }

    @Test
    void aBodyOfNothingButTheIdentifierIsLeftAlone() {
        // Removing the only field would leave {}, which creates nothing — better to keep the body
        // and let the setup guard abstain than to send a request that cannot succeed.
        assertThat(DeterministicProbeSynthesizer.withoutAssignedIdentifier("{\"id\":1}"))
                .isEqualTo("{\"id\":1}");
        assertThat(DeterministicProbeSynthesizer.withoutAssignedIdentifier("not json"))
                .isEqualTo("not json");
    }

    @Test
    void aNullAssertionIsNoProbe() {
        assertThat(DeterministicProbeSynthesizer.synthesize(null, artifactRoot)).isEmpty();
    }

    @Test
    void aMissingArtifactRootDoesNotBreakTheShapesThatNeedNoBody() {
        // Bodies come from the tree; the absent-resource shape needs none, so it survives.
        assertThat(DeterministicProbeSynthesizer.synthesize(
                assertion("GET /members/{id} returns HTTP 404"), Path.of("/nonexistent-tree")))
                .isPresent();
    }

    // ---- helpers -------------------------------------------------------------------------

    @Test
    void collectionPathStopsAtTheFirstVariable() {
        assertThat(DeterministicProbeSynthesizer.collectionPath("/loans/{}/return")).isEqualTo("/loans");
        assertThat(DeterministicProbeSynthesizer.collectionPath("/books/{}")).isEqualTo("/books");
        assertThat(DeterministicProbeSynthesizer.collectionPath("/books")).isEqualTo("/books");
    }

    @Test
    void groundingReplacesTheFirstVariableOnly() {
        assertThat(DeterministicProbeSynthesizer.ground("/books/{}", "7")).isEqualTo("/books/7");
        assertThat(DeterministicProbeSynthesizer.ground("/a/{}/b/{}", "7")).isEqualTo("/a/7/b/{}");
    }
}
