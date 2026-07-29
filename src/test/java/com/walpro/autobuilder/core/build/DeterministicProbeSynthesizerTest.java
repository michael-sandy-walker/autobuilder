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
