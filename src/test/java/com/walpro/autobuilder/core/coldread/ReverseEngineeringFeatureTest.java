package com.walpro.autobuilder.core.coldread;

import com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery;
import com.walpro.autobuilder.core.build.CriterionProbeSynthesizer.ProbeRequest;
import com.walpro.autobuilder.core.coldread.ClaimProposer.Claim;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.JudgedClaim;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Observation;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Variant;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReverseEngineeringFeatureTest {

    private static final String BOOKS = """
            package p;
            @RestController @RequestMapping("/books")
            public class BookController {
                @PostMapping public Object create() { return null; }
                @GetMapping("/{id}") public Object get() { return null; }
            }
            """;
    private static final String LOANS = """
            package p;
            @RestController
            public class LoanController {
                @PostMapping("/loans") public Object lend() { return null; }
                @PostMapping("/loans/{id}/return") public Object giveBack() { return null; }
                @GetMapping("/loans") public Object list() { return null; }
            }
            """;

    @Test
    void handlersAreReadWithTheirRoutesAndGroupedByResource() {
        var handlers = new EndpointSurfaceQuery().handlersByCode(Map.of(
                "src/main/java/p/BookController.java", BOOKS, "src/main/java/p/LoanController.java", LOANS)).orElseThrow();
        assertThat(handlers).extracting(h -> h.type() + "." + h.method())
                .containsExactlyInAnyOrder("BookController.create", "BookController.get",
                        "LoanController.lend", "LoanController.giveBack", "LoanController.list");
        assertThat(handlers).filteredOn(h -> h.method().equals("get")).first()
                .satisfies(h -> assertThat(h.qualifiedType()).isEqualTo("p.BookController"));

        var features = ReverseEngineeringService.featuresOf(handlers);
        assertThat(features.keySet()).containsExactly("books", "loans");
        assertThat(features.get("loans")).hasSize(3);
    }

    @Test
    void aClaimBelongsToTheFeatureItsLastRequestGoesTo() {
        Set<String> features = Set.of("books", "members", "loans");
        assertThat(ReverseEngineeringService.featureOf(claim("POST", "/books", "GET", "/loans?memberId=$1.id"), features, null))
                .isEqualTo("loans");
        assertThat(ReverseEngineeringService.featureOf(claim("GET", "/api/books/1"), features, "/api")).isEqualTo("books");
        assertThat(ReverseEngineeringService.featureOf(claim("GET", "/nowhere"), features, null)).isNull();
    }

    @Test
    void anchoredByItsFeatureAndDependingOnTheFeaturesItsSetupGoesThrough() {
        Claim k = claim("POST", "/books", "POST", "/members", "POST", "/loans");
        Map<String, Observation> obs = obs(Map.of("as-is", 201, "feature:books", -1, "feature:members", -1,
                "feature:loans", 500));
        var v = ReverseEngineeringService.judgeFeature(k, "loans", obs, served(), Set.of("books", "members", "loans"));
        assertThat(v.verdict()).isEqualTo("anchored");
        assertThat(v.dependsOn()).containsExactlyInAnyOrder("books", "members");
    }

    @Test
    void aClaimTheFrameworkAnswersWithoutTheHandlerIsNotAnchored() {
        Claim k = claim("POST", "/books");   // e.g. a body that never binds: 400 before any handler runs
        Map<String, Observation> obs = obs(Map.of("as-is", 201, "feature:books", 201, "feature:members", 201, "feature:loans", 201));
        assertThat(ReverseEngineeringService.judgeFeature(k, "books", obs, served(), Set.of("books", "members", "loans")).verdict())
                .isEqualTo("not-anchored");
    }

    @Test
    void aClaimReachingNoDeclaredRouteIsUnowned() {
        Claim k = claim("GET", "/actuator/health");
        Map<String, Observation> obs = obs(Map.of("as-is", 201, "feature:books", 201, "feature:members", 201, "feature:loans", 201));
        assertThat(ReverseEngineeringService.judgeFeature(k, null, obs, served(), Set.of("books", "members", "loans")).verdict())
                .isEqualTo("unowned");
    }

    @Test
    void aFeatureSummaryCarriesItsKeptClaimsDependenciesAndTheCodeItIsMadeOf() {
        var handlers = new EndpointSurfaceQuery().handlersByCode(Map.of("src/main/java/p/LoanController.java", LOANS)).orElseThrow();
        Claim kept = new Claim("k1", "c2", "lend", List.of(new ProbeRequest("POST", "/loans", "{}")), 201);
        Claim dropped = new Claim("k2", "c2", "x", List.of(new ProbeRequest("POST", "/loans", "{}")), 400);
        var judged = List.of(
                new JudgedClaim(kept, "anchored", List.of("c1", "c5"), List.of(),
                        new ReverseEngineeringService.FeatureVerdict("loans", "anchored", List.of("books"))),
                new JudgedClaim(dropped, "anchored", List.of("c9"), List.of(),
                        new ReverseEngineeringService.FeatureVerdict("loans", "not-anchored", List.of("members"))));
        var f = ReverseEngineeringService.summarise(ReverseEngineeringService.featuresOf(handlers), judged).get(0);
        assertThat(f.name()).isEqualTo("loans");
        assertThat(f.claims()).containsExactly("k1");
        assertThat(f.dependsOn()).containsExactly("books");
        assertThat(f.madeOf()).containsExactly("c1", "c2", "c5");
        assertThat(f.routes()).hasSize(3);
    }

    private static Claim claim(String... methodPath) {
        List<ProbeRequest> seq = new java.util.ArrayList<>();
        for (int i = 0; i < methodPath.length; i += 2) seq.add(new ProbeRequest(methodPath[i], methodPath[i + 1], null));
        return new Claim("k1", "c1", "t", seq, 201);
    }

    /** status -1 = the sequence could not be completed (a setup request refused). */
    private static Map<String, Observation> obs(Map<String, Integer> statuses) {
        Map<String, Observation> m = new LinkedHashMap<>();
        statuses.forEach((v, s) -> m.put(v, new Observation(v, s < 0 ? null : s, s < 0 ? "SETUP_REFUSED" : null, null)));
        return m;
    }

    private static Map<String, Variant> served() {
        Map<String, Variant> m = new LinkedHashMap<>();
        m.put("as-is", new Variant(null, List.of(), true, true, null));
        for (String f : List.of("books", "members", "loans")) {
            m.put("feature:" + f, new Variant("feature:" + f, List.of("X.m"), true, true, null));
        }
        return m;
    }
}
