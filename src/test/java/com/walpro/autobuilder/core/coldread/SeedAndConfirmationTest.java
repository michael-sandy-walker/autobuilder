package com.walpro.autobuilder.core.coldread;

import com.walpro.autobuilder.core.build.CriterionExercises;
import com.walpro.autobuilder.core.build.CriterionProbeSynthesizer.ProbeRequest;
import com.walpro.autobuilder.core.coldread.ClaimProposer.Claim;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Account;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Feature;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.FeatureVerdict;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.JudgedClaim;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SeedAndConfirmationTest {

    private static Claim service(String id, String path, int status) {
        return new Claim(id, "c1", "GET " + path + " returns " + status, List.of(new ProbeRequest("GET", path, null)), status);
    }

    private static JudgedClaim kept(Claim k, String feature, String... dependsOn) {
        return new JudgedClaim(k, "anchored", List.of(), List.of(), new FeatureVerdict(feature, "anchored", List.of(dependsOn)));
    }

    private static JudgedClaim dropped(Claim k) {
        return new JudgedClaim(k, "not-held", List.of(), List.of(), new FeatureVerdict(null, "not-held", List.of()));
    }

    private final Account account = new Account("src", "copy",
            new ColdRead("r", null, null, List.of("books -- a small library"), List.of(), true, List.of(), List.of(), List.of(), List.of()),
            "service-style",
            List.of(new Feature("Loans", "service-style", List.of("POST /loans"), List.of(), List.of("Books"), List.of(), List.of("k3")),
                    new Feature("Books", "service-style", List.of("GET /books"), List.of(), List.of(), List.of(), List.of("k1", "k2"))),
            List.of(), List.of(), List.of(), List.of(),
            List.of(kept(service("k1", "/books", 200), "Books"), kept(service("k2", "/books/1000002", 404), "Books"),
                    kept(service("k3", "/loans", 200), "Loans", "Books"), dropped(service("k4", "/x", 500)),
                    kept(service("k5", "/books/1000005", 404), "Books")),
            Map.of(), 0);

    @Test
    void featuresComeInObservedDependencyOrder() {
        assertThat(SeedComposer.ordered(account.features())).extracting(Feature::name).containsExactly("Books", "Loans");
    }

    @Test
    void aComposedLineMustCiteAKeptClaimAndTheProjectsProseIsQuotedNotComposed() {
        String answer = """
                Books:
                - GET /books lists the books [k1]
                - GET /books/{id} answers 404 for one that is not there [k2, k5]
                - Books are stored forever
                Loans:
                - POST /loans lends a book [k3, k4]
                - GET /loans answers [k3]
                """;
        var composed = SeedComposer.check(answer, SeedComposer.keptIds(account), account);
        assertThat(composed.text()).startsWith("In its own words:\n> books -- a small library")
                .contains("- GET /books lists the books [k1]", "- GET /loans answers [k3]")
                .doesNotContain("stored forever", "lends a book");
        assertThat(composed.refused()).hasSize(2);
        assertThat(composed.refused().get(0)).contains("cites no claim");
        assertThat(composed.refused().get(1)).contains("k4, not a kept claim");
    }

    @Test
    void aConfirmedSeedIsReadBackAsHeadersQuotesCitedLinesAndTheOperatorsOwnLines() {
        String confirmed = """
                In its own words:
                > books -- a small library

                Books:
                - GET /books lists the books [k1]
                - A refused request uses nothing up
                Loans:
                - GET /loans answers [k3]
                """;
        var lines = SeedComposer.lines(confirmed, Set.of("Books", "Loans"));
        assertThat(lines).extracting(SeedComposer.Line::kind).containsExactly("quote", "quote", "header", "cited", "uncited",
                "header", "cited");
        assertThat(lines.get(3).feature()).isEqualTo("Books");
        assertThat(lines.get(3).cites()).containsExactly("k1");
        assertThat(lines.get(4).text()).isEqualTo("A refused request uses nothing up");
        assertThat(ReverseEngineeringConfirmation.seedText(lines))
                .contains("Books:\n- GET /books lists the books\n- A refused request uses nothing up")
                .doesNotContain("[k1]");
    }

    @Test
    void criteriaAreTheKeptClaimsByFeatureWithoutRepeatsOfOneExercise() {
        var criteria = ReverseEngineeringConfirmation.criteria(account);
        // k2 and k5 are one exercise apart from the value made unique to each claim.
        assertThat(criteria.get("Books")).extracting(Claim::id).containsExactly("k1", "k2");
        assertThat(criteria.get("Loans")).extracting(Claim::id).containsExactly("k3");
        assertThat(criteria).doesNotContainKey(null);
    }

    @Test
    void storedExercisesAreReadBackByTheirCriterionsText(@TempDir Path base) throws Exception {
        CriterionExercises store = new CriterionExercises(base.toString());
        assertThat(store.of("GET /books returns 200")).isEmpty();
        store.write(List.of(new CriterionExercises.Stored("GET /books returns 200", "service-style",
                List.of(new ProbeRequest("GET", "/books", null)), 200, Map.of(), List.of(), null)));
        var s = store.of("GET /books returns 200").orElseThrow();
        assertThat(s.requests()).extracting(ProbeRequest::path).containsExactly("/books");
        assertThat(s.expected()).isEqualTo(200);
        assertThat(store.of("something else")).isEmpty();
    }

    @Test
    void thePromptShowsHowEachClaimWasExercisedSoTheSeedNamesItThatWay() {
        Claim byMember = new Claim("k3", "c1", "GET /loans lists a member's loans", List.of(
                new ProbeRequest("POST", "/members", "{}"), new ProbeRequest("GET", "/loans?memberId=$1.id", null)), 200);
        assertThat(SeedComposer.exercised(byMember)).isEqualTo("GET /loans?memberId={id} -> 200");
        assertThat(SeedComposer.prompt(account)).contains("exercised as: GET /books -> 200", "GET /loans?memberId={id}");
    }

    @Test
    void aHeaderCarryingTheKindItWasShownIsStillAHeader() {
        var lines = SeedComposer.lines("Main (command-style):\n- count prints rows [k1]\n", Set.of("Main"));
        assertThat(lines).extracting(SeedComposer.Line::kind).containsExactly("header", "cited");
        assertThat(lines.get(1).feature()).isEqualTo("Main");
    }
}
