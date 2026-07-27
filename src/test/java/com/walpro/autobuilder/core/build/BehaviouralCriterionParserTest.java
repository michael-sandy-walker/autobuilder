package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-CU: reading an acceptance criterion as an executable assertion. The corpus case at the bottom
 * is the criteria of run {@code 2026-07-27_16-41-48} verbatim — the run that closed GOAL_FULFILLED
 * at smoke 19/23 with three of its four failures sitting under a criterion the evaluator had
 * marked {@code ✓}.
 */
class BehaviouralCriterionParserTest {

    @Test
    void readsVerbPathAndStatus() {
        Optional<BehaviouralCriterionParser.Assertion> a = BehaviouralCriterionParser.parse(
                "POST /books with invalid input returns HTTP 400 with JSON body {\"message\":\"...\"}");

        assertThat(a).isPresent();
        assertThat(a.get().verb()).isEqualTo("POST");
        assertThat(a.get().path()).isEqualTo("/books");
        assertThat(a.get().expectedStatus()).isEqualTo(400);
        assertThat(a.get().describe()).isEqualTo("POST /books → 400");
    }

    @Test
    void carriesTheCriterionTextVerbatimSoRepairsReadThePlannersOwnWords() {
        String text = "GET /books returns HTTP 200 with a JSON array of all books";
        assertThat(BehaviouralCriterionParser.parse(text))
                .get().extracting(BehaviouralCriterionParser.Assertion::criterionText)
                .isEqualTo(text);
    }

    @Test
    void queryStringIsNormalizedAway() {
        assertThat(BehaviouralCriterionParser.parse(
                "GET /loans?memberId={id} returns HTTP 200 with a JSON array of all loans for the member"))
                .get().extracting(BehaviouralCriterionParser.Assertion::path)
                .isEqualTo("/loans");
    }

    @Test
    void statedLimitIsNotMistakenForAResponseStatus() {
        // "120" and "13" are three-digit numbers in the same sentence as the real status. A bare
        // \d{3} match would mint two codes and either pick the wrong one or abstain by accident.
        Optional<BehaviouralCriterionParser.Assertion> a = BehaviouralCriterionParser.parse(
                "POST /books with a title longer than 120 chars or an isbn not matching \\d{13} "
                        + "returns HTTP 400");

        assertThat(a).isPresent();
        assertThat(a.get().expectedStatus()).isEqualTo(400);
    }

    @Test
    void acceptsStatusAndStatusCodeMarkers() {
        assertThat(BehaviouralCriterionParser.parse("POST /members returns status 409"))
                .get().extracting(BehaviouralCriterionParser.Assertion::expectedStatus).isEqualTo(409);
        assertThat(BehaviouralCriterionParser.parse("POST /members returns status code 409"))
                .get().extracting(BehaviouralCriterionParser.Assertion::expectedStatus).isEqualTo(409);
    }

    @Test
    void aPathVariableCriterionParsesAsAPattern() {
        // Originally this abstained: an id the probe never created cannot be supplied honestly.
        // Measured against the corpus, that exclusion put 4 of the 11 requirements this spec
        // states permanently out of reach — including POST /loans/{id}/return → 404, an observed
        // smoke failure. Grounding the id moved to the synthesized sequence, where the evidence
        // is; a permanently unverifiable requirement reads as "unverified", which in a readout
        // is indistinguishable from "fine".
        var a = BehaviouralCriterionParser.parse("GET /books/{id} returns HTTP 404 for non-existing id");
        assertThat(a).isPresent();
        assertThat(a.get().verb()).isEqualTo("GET");
        assertThat(a.get().path()).isEqualTo("/books/{}");
        assertThat(a.get().expectedStatus()).isEqualTo(404);
    }

    @Test
    void abstainsOnTwoEndpoints_theSubjectIsAmbiguous() {
        assertThat(BehaviouralCriterionParser.parse(
                "POST /books then GET /books both return HTTP 200")).isEmpty();
    }

    @Test
    void abstainsOnTwoStatuses() {
        assertThat(BehaviouralCriterionParser.parse(
                "POST /books returns HTTP 201 or HTTP 400")).isEmpty();
    }

    @Test
    void abstainsWithoutAnEndpointOrWithoutAStatus() {
        assertThat(BehaviouralCriterionParser.parse("Validation rejects a missing title")).isEmpty();
        assertThat(BehaviouralCriterionParser.parse("POST /books creates a book")).isEmpty();
        assertThat(BehaviouralCriterionParser.parse("")).isEmpty();
        assertThat(BehaviouralCriterionParser.parse(null)).isEmpty();
    }

    @Test
    void abstainsOnAStatusWithNoMarker() {
        // Conservative on purpose: "returns 400" is probably a status, but so is "at most 3 active
        // loans". Requiring the marker keeps the false-assertion rate at zero.
        assertThat(BehaviouralCriterionParser.parse("POST /books returns 400")).isEmpty();
    }

    @Test
    void runCorpus_theFalselyPassedCriterionIsProbeable() {
        // The criterion at the centre of the 19/23 run: stamped SPEC, marked ✓ by the evaluator,
        // and false — POST /books answered 201 with no constraint in the artifact.
        assertThat(BehaviouralCriterionParser.parse(
                "POST /books with invalid input returns HTTP 400 with JSON body {\"message\":\"...\"}"))
                .isPresent();
    }

    @Test
    void runCorpus_probeableSubsetIsTheEndpointLevelOnes() {
        List<String> criteria = List.of(
                "POST /books with valid title and isbn creates a book and returns HTTP 201 with book body",
                "POST /books with invalid input returns HTTP 400 with JSON body {\"message\":\"...\"}",
                "GET /books returns HTTP 200 with a JSON array of all books",
                "GET /books/{id} returns HTTP 200 with book body for existing id",
                "GET /books/{id} returns HTTP 404 for non-existing id",
                "POST /members with valid name and email creates a member and returns HTTP 201 with member body",
                "POST /members with duplicate email returns HTTP 409 with JSON body {\"message\":\"...\"}",
                "GET /members/{id} returns HTTP 200 with member body for existing id",
                "GET /members/{id} returns HTTP 404 for non-existing id",
                "POST /loans with valid bookId and memberId creates a loan and returns HTTP 201 with loan body",
                "POST /loans with non-existing bookId or memberId returns HTTP 404 with JSON body {\"message\":\"...\"}",
                "POST /loans with book already on loan returns HTTP 409 with JSON body {\"message\":\"...\"}",
                "POST /loans with member exceeding 3 active loans returns HTTP 422 with JSON body {\"message\":\"...\"}",
                "POST /loans/{id}/return marks a loan as returned and returns HTTP 200",
                "POST /loans/{id}/return with non-existing loan returns HTTP 404 with JSON body {\"message\":\"...\"}",
                "POST /loans/{id}/return with already returned loan returns HTTP 409 with JSON body {\"message\":\"...\"}",
                "GET /loans?memberId={id} returns HTTP 200 with a JSON array of all loans for the member");

        List<String> probeable = criteria.stream()
                .map(BehaviouralCriterionParser::parse)
                .flatMap(Optional::stream)
                .map(BehaviouralCriterionParser.Assertion::describe)
                .toList();

        // Every criterion in the run is now readable, path-variable ones included — those are
        // grounded by the synthesized sequence rather than skipped. Nothing is read as an
        // assertion it did not make: the query string on the last one normalizes away, and each
        // path-variable criterion keeps its pattern.
        assertThat(probeable).containsExactly(
                "POST /books → 201",
                "POST /books → 400",
                "GET /books → 200",
                "GET /books/{} → 200",
                "GET /books/{} → 404",
                "POST /members → 201",
                "POST /members → 409",
                "GET /members/{} → 200",
                "GET /members/{} → 404",
                "POST /loans → 201",
                "POST /loans → 404",
                "POST /loans → 409",
                "POST /loans → 422",
                "POST /loans/{}/return → 200",
                "POST /loans/{}/return → 404",
                "POST /loans/{}/return → 409",
                "GET /loans → 200");
    }
}
