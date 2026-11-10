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
    void aComposedLineMustCiteAKeptClaimAndTheProjectsReadmeIsNotPartOfTheSeed() {
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
        assertThat(composed.text()).startsWith("Books:")
                .contains("- GET /books lists the books [k1]", "- GET /loans answers [k3]")
                .doesNotContain("stored forever", "lends a book", "In its own words", "a small library");
        // Every line of a composed seed is a feature header or rests on a kept claim (T1-GK).
        assertThat(SeedComposer.lines(composed.text(), Set.of("Books", "Loans")))
                .extracting(SeedComposer.Line::kind).containsOnly("header", "cited");
        assertThat(composed.refused()).hasSize(2);
        assertThat(composed.refused().get(0)).contains("cites no claim");
        assertThat(composed.refused().get(1)).contains("k4, not a kept claim");
    }

    /** The same account with a README of three lines of prose. */
    private Account withReadme() {
        return new Account("src", "copy", new ColdRead("r", null, null,
                List.of("# books", "A small library for a reading club: members borrow books.", "Members can reserve a book."),
                List.of(), true, List.of(), List.of(), List.of(), List.of()),
                account.kind(), account.features(), account.capabilities(), account.unknownDeclarations(), account.proposals(),
                account.variants(), account.claims(), account.verdicts(), account.durationMs());
    }

    @Test
    void theReadmeIsGivenToTheComposerNumberedAndItsWordsAreCitedByLine() {
        String prompt = SeedComposer.prompt(withReadme());
        assertThat(prompt).contains("r1: # books", "r2: A small library for a reading club", "r3: Members can reserve a book.")
                .contains("What it is:").contains(SeedComposer.NOT_SHOWN);
        Account noReadme = new Account("src", "copy", new ColdRead("r", null, null, List.of(), List.of(), true, List.of(),
                List.of(), List.of(), List.of()), account.kind(), account.features(), account.capabilities(),
                account.unknownDeclarations(), account.proposals(), account.variants(), account.claims(), account.verdicts(),
                account.durationMs());
        assertThat(SeedComposer.prompt(noReadme)).as("no README, no README instructions").doesNotContain("What it is:");
    }

    @Test
    void whatItIsComesFromTheReadmeAndWhatNoClaimShowsIsOfferedNotWritten() {
        String answer = """
                What it is: A small library for a reading club, where members borrow books [r1, r2]
                Books:
                - GET /books lists the club's books [k1]
                Loans:
                - GET /loans answers [k3]
                Not shown by any claim:
                - Members can reserve a book [r3]
                - Members get reminders [r9]
                - Books are rated
                """;
        var composed = SeedComposer.check(answer, SeedComposer.keptIds(account), withReadme());
        assertThat(composed.text()).startsWith("What it is: A small library for a reading club, where members borrow books [r1, r2]\n\nBooks:")
                .doesNotContain("reserve", "reminders", "rated");
        assertThat(composed.offered()).containsExactly(new SeedComposer.Offered("Members can reserve a book", List.of("r3")));
        assertThat(composed.refused()).anyMatch(r -> r.contains("r9, not a README line"))
                .anyMatch(r -> r.contains("Books are rated") && r.contains("cites no README line"));
    }

    @Test
    void whatItIsIsNeitherACriterionNorOpenWorkAndATickedOfferIsTheOperatorsOwnLine() {
        String confirmed = """
                What it is: A small library for a reading club [r1, r2]

                Books:
                - GET /books lists the books [k1]
                - Members can reserve a book
                """;
        var lines = SeedComposer.lines(confirmed, Set.of("Books", "Loans"));
        assertThat(lines).extracting(SeedComposer.Line::kind).containsExactly("purpose", "header", "cited", "uncited");
        assertThat(lines.get(0).text()).isEqualTo("A small library for a reading club");
        assertThat(lines.get(0).cites()).isEmpty();
        assertThat(ReverseEngineeringConfirmation.seedText(lines))
                .startsWith("What it is: A small library for a reading club\n\nBooks:")
                .doesNotContain("[r1");
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
