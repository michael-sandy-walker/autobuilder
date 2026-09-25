package com.walpro.autobuilder.analysis.structure;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2-EV: reading a specification's stated per-endpoint response behaviours.
 *
 * <p>The corpus case is the root goal of runs 4, 5 and {@code 20-33-22} verbatim — the document
 * whose {@code POST /members} behaviour went unstated in every plan, so smoke check {@code [10]}
 * failed three times out of three with a different wrong status each time.
 */
class SpecEndpointBehaviourQueryTest {

    private final SpecEndpointBehaviourQuery query = new SpecEndpointBehaviourQuery();

    /** The production root goal, whitespace-for-whitespace. */
    private static final String SPEC = """
              Evolve the Java 21 Spring Boot application into a library book-lending REST API with three resource types.

              Books: POST /books (create; title required, max 120 chars; isbn required, must match \\d{13}), GET /books (list all),
               GET /books/{id} (single; 404 if absent).

              Members: POST /members (create; name required; email required, must be unique — 409 on duplicate), GET /members/{id}
               (404 if absent).

              Loans: POST /loans (lend a book; body contains bookId and memberId; 404 if either not found; 409 if book is already
              on loan; a member may hold at most 3 active loans — 422 if exceeded), POST /loans/{id}/return (mark returned; 404 if
               loan absent; 409 if already returned), GET /loans?memberId={id} (list all loans for a member, active and returned).

              All data stored in in-memory collections (no database). Invalid input returns HTTP 400 with JSON body {"message":
              "<reason>"}. Business-rule violations use the codes above with the same JSON shape.
            """;

    private List<Integer> statusesFor(String verb, String path) {
        Map<Endpoint, List<SpecEndpointBehaviourQuery.StatedBehaviour>> all =
                query.statedBehaviours(SPEC);
        List<SpecEndpointBehaviourQuery.StatedBehaviour> b =
                all.get(new Endpoint(verb, path));
        return b == null ? List.of()
                : b.stream().map(SpecEndpointBehaviourQuery.StatedBehaviour::status).sorted().toList();
    }

    @Test
    void bindsTheCrossCuttingRuleToMembers_theCheckThatFailedThreeRunsRunning() {
        // [10] POST /members with a missing name wants 400. The spec states that once, globally,
        // and no plan ever bound it to this endpoint.
        assertThat(statusesFor("POST", "/members")).containsExactly(400, 409);
    }

    @Test
    void readsClauseLocalStatuses() {
        assertThat(statusesFor("GET", "/books/{}")).containsExactly(404);
        assertThat(statusesFor("GET", "/members/{}")).containsExactly(404);
        assertThat(statusesFor("POST", "/loans")).containsExactly(400, 404, 409, 422);
    }

    @Test
    void aStatedQuantityIsNotAStatus() {
        // "max 120 chars" and "must match \\d{13}" sit in the POST /books clause. 120 is not a
        // response status, so membership alone rejects it — no prose heuristics needed.
        assertThat(statusesFor("POST", "/books")).containsExactly(400);
    }

    @Test
    void abodylessEndpointIsNotHandedTheInputRule() {
        // POST /loans/{id}/return states no input constraint, so the cross-cutting 400 must not
        // reach it. Minting a requirement the artifact can never satisfy is how a run gets walled.
        assertThat(statusesFor("POST", "/loans/{}/return")).containsExactly(404, 409);
    }

    @Test
    void aReadEndpointStatingNothingGetsNothing() {
        assertThat(statusesFor("GET", "/books")).isEmpty();
        assertThat(statusesFor("GET", "/loans")).isEmpty();
    }

    @Test
    void theTrailingParagraphDoesNotLeakIntoTheLastEndpointsClause() {
        // Without a paragraph bound the final endpoint mention swallows the rest of the document
        // and inherits codes belonging to no endpoint — GET /loans picked up the 400 this way.
        assertThat(statusesFor("GET", "/loans")).doesNotContain(400);
    }

    @Test
    void criteriaAreReadableAndCarryTheSpecsOwnCondition() {
        Map<Endpoint, List<SpecEndpointBehaviourQuery.StatedBehaviour>> all =
                query.statedBehaviours(SPEC);
        Endpoint members = new Endpoint("POST", "/members");
        List<String> criteria = query.criteriaFor(members, all.get(members));

        assertThat(criteria).anySatisfy(c -> assertThat(c).contains("POST /members returns HTTP 409")
                .contains("duplicate"));
        assertThat(criteria).anySatisfy(c -> assertThat(c).contains("POST /members returns HTTP 400")
                .contains("Invalid input"));
    }

    @Test
    void everyRenderedCriterionIsExecutable() {
        // The criteria must be readable back by the executable-criterion parser, or the
        // behavioural verification shipped alongside this has nothing to probe.
        Map<Endpoint, List<SpecEndpointBehaviourQuery.StatedBehaviour>> all =
                query.statedBehaviours(SPEC);
        Endpoint members = new Endpoint("POST", "/members");

        for (String criterion : query.criteriaFor(members, all.get(members))) {
            var parsed = com.walpro.autobuilder.core.build.BehaviouralCriterionParser.parse(criterion);
            assertThat(parsed).as("parseable: %s", criterion).isPresent();
            assertThat(parsed.get().verb()).isEqualTo("POST");
            assertThat(parsed.get().path()).isEqualTo("/members");
        }
    }

    @Test
    void abstainsOnASpecThatNamesNothing() {
        assertThat(query.statedBehaviours("Build a string formatting library.")).isEmpty();
        assertThat(query.statedBehaviours("")).isEmpty();
        assertThat(query.statedBehaviours(null)).isEmpty();
    }
}
