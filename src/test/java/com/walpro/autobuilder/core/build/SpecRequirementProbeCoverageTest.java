package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.analysis.structure.Endpoint;

import com.walpro.autobuilder.analysis.structure.SpecEndpointBehaviourQuery;
import com.walpro.autobuilder.core.build.CriterionProbeSynthesizer.ProbeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-CY: end-to-end over the REAL pipeline and the REAL specification — spec text →
 * {@link SpecEndpointBehaviourQuery} → {@link BehaviouralCriterionParser} →
 * {@link DeterministicProbeSynthesizer} — with no model anywhere in it.
 *
 * <p><b>Why a test at this altitude.</b> The mechanism this feeds was switched off because the
 * per-requirement cost was a model call, and the fix is only worth anything if the requirements the
 * specification actually states come out the other end as executable requests. Unit tests on each
 * stage would all pass while the composition covered nothing — the failure mode that put the
 * criterion probes at {@code probed=0} for fourteen runs. This asserts coverage of the composition,
 * against the spec text the field runs use.
 */
class SpecRequirementProbeCoverageTest {

    /** The live seed specification, verbatim (`firestarter/bootstrap/seed.txt`). */
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

    private final SpecEndpointBehaviourQuery behaviourQuery = new SpecEndpointBehaviourQuery();

    @TempDir
    Path artifactRoot;

    /** The model sources a converging run has written by the time the self-check first looks. */
    @BeforeEach
    void writeModelSources() throws IOException {
        Path dir = artifactRoot.resolve("src/main/java/com/example/model");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Book.java"),
                "package com.example.model; public class Book { private Long id; private String title; private String isbn; }");
        Files.writeString(dir.resolve("Member.java"),
                "package com.example.model; public class Member { private Long id; private String name; private String email; }");
        Files.writeString(dir.resolve("Loan.java"),
                "package com.example.model; public class Loan { private Long id; private Long bookId; private Long memberId; }");
    }

    /** The requirement list the self-check is handed, exactly as {@code specDerivedRequirements} builds it. */
    private List<String> specDerivedRequirements() {
        List<String> out = new ArrayList<>();
        var stated = behaviourQuery.statedBehaviours(SPEC);
        for (var entry : stated.entrySet()) {
            out.addAll(behaviourQuery.criteriaFor(entry.getKey(), entry.getValue()));
        }
        return out;
    }

    private Map<String, Optional<List<ProbeRequest>>> probesByRequirement() {
        Map<String, Optional<List<ProbeRequest>>> out = new LinkedHashMap<>();
        for (String requirement : specDerivedRequirements()) {
            out.put(requirement, BehaviouralCriterionParser.parse(requirement)
                    .flatMap(a -> DeterministicProbeSynthesizer.synthesize(a, artifactRoot)));
        }
        return out;
    }

    @Test
    void theSpecificationStatesRequirementsAndMostOfThemProbeWithoutAModel() {
        Map<String, Optional<List<ProbeRequest>>> probes = probesByRequirement();
        long probeable = probes.values().stream().filter(Optional::isPresent).count();

        // The list is the one the field runs carry: ~11 spec-stated behaviours, not the 73/77 that
        // made the first observation cost 29.5 minutes.
        assertThat(probes).hasSizeGreaterThanOrEqualTo(8).hasSizeLessThanOrEqualTo(BehaviouralSelfCheck.MAX_REQUIREMENTS);
        // The claim being pinned: a clear majority of stated requirements are executable with no
        // model call at all. Before this, every one of them cost a synthesis call.
        assertThat(probeable)
                .as("probeable without a model, of %d stated requirements: %s", probes.size(), probes)
                .isGreaterThanOrEqualTo(probes.size() / 2 + 1);
    }

    @Test
    void theInputAndResourceStatusesAreReachableAndTheBusinessRuleOneIsDeliberatelyNot() {
        // 400/404/409/422 are the only statuses this specification states. Three are reachable; the
        // fourth is the "at most 3 active loans" rule, whose precondition needs four valid loans and
        // a limit stated only in prose — abstaining there is the decision, not an oversight.
        Map<Integer, Long> reachablePerStatus = new LinkedHashMap<>();
        for (var entry : probesByRequirement().entrySet()) {
            BehaviouralCriterionParser.parse(entry.getKey()).ifPresent(a ->
                    reachablePerStatus.merge(a.expectedStatus(), entry.getValue().isPresent() ? 1L : 0L, Long::sum));
        }
        assertThat(reachablePerStatus).containsKeys(400, 404, 409, 422);
        assertThat(reachablePerStatus.get(400)).isPositive();
        assertThat(reachablePerStatus.get(404)).isPositive();
        assertThat(reachablePerStatus.get(409)).isPositive();
        assertThat(reachablePerStatus.get(422)).isZero();
    }

    @Test
    void theTwoDefectsTheFieldObservationFoundAreBothProbeable() {
        // Run 2026-07-28_23-16-15 found POST /books answering 500 where the spec wants 400, and
        // GET /members/{} answering 500 where it wants 404. Those two are why the mechanism is
        // worth re-enabling, so they are the two the deterministic path must not lose.
        assertThat(probeFor("POST", "/books", 400)).isPresent();
        assertThat(probeFor("GET", "/members/{}", 404)).isPresent();
    }

    @Test
    void noProbeSequenceCarriesAnUnresolvedPathVariable() {
        // A literal {} in a request path would be sent to the application as-is and answer
        // something meaningless, which the executor would then report as a contradiction.
        for (var entry : probesByRequirement().entrySet()) {
            entry.getValue().ifPresent(sequence -> assertThat(sequence)
                    .as("requirement: %s", entry.getKey())
                    .noneMatch(r -> r.path().contains("{")));
        }
    }

    @Test
    void everyProbeEndsOnTheEndpointItsRequirementIsAbout() {
        // The drift guard the model path needs explicitly; the deterministic path must satisfy it
        // by construction, and this is what proves it does.
        for (var entry : probesByRequirement().entrySet()) {
            BehaviouralCriterionParser.Assertion a = BehaviouralCriterionParser.parse(entry.getKey()).orElseThrow();
            entry.getValue().ifPresent(sequence -> {
                ProbeRequest last = sequence.get(sequence.size() - 1);
                assertThat(last.method()).as("requirement: %s", entry.getKey()).isEqualTo(a.verb());
                assertThat(CriterionProbeSynthesizer.pathMatches(a.path(), last.path()))
                        .as("%s should end on %s, ended on %s", entry.getKey(), a.path(), last.path())
                        .isTrue();
            });
        }
    }

    private Optional<List<ProbeRequest>> probeFor(String verb, String path, int status) {
        var endpoint = new Endpoint(verb, path);
        for (var entry : behaviourQuery.statedBehaviours(SPEC).entrySet()) {
            if (!entry.getKey().equals(endpoint)) continue;
            for (String requirement : behaviourQuery.criteriaFor(entry.getKey(), entry.getValue())) {
                Optional<BehaviouralCriterionParser.Assertion> a = BehaviouralCriterionParser.parse(requirement);
                if (a.isPresent() && a.get().expectedStatus() == status) {
                    return DeterministicProbeSynthesizer.synthesize(a.get(), artifactRoot);
                }
            }
        }
        return Optional.empty();
    }
}
