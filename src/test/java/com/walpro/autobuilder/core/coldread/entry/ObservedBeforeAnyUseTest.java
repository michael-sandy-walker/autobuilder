package com.walpro.autobuilder.core.coldread.entry;

import com.walpro.autobuilder.core.build.BootGate.Served.Asked;
import com.walpro.autobuilder.core.build.ProbeSession;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-EO: what a started project answers before any use is told only where it matters to an exercise. */
class ObservedBeforeAnyUseTest {

    @Test
    void redirectsAndPagesWithFormsAreReportedAndPlainAnswersCounted() {
        String said = ProgramAdapter.observed(List.of(
                new Asked("/enter", 200, null, List.of(new ProbeSession.Form("/entered", List.of("_t"), List.of("name", "word")))),
                new Asked("/inside", 302, "/enter", List.of()),
                new Asked("/open", 200, null, List.of()),
                new Asked("/gone", null, null, List.of())));

        assertThat(said).contains("GET /enter -> 200, a page with a form that posts to /entered (fields: name, word)")
                .doesNotContain("_t")
                .contains("GET /inside -> 302, redirects to /enter")
                .contains("(1 other way(s) in answered such a client themselves, with no form)")
                .doesNotContain("/open").doesNotContain("/gone");
    }

    @Test
    void aProjectWhoseAnswersDoNotDependOnWhoAsksReportsNothing() {
        assertThat(ProgramAdapter.observed(List.of(new Asked("/things", 200, null, List.of()),
                new Asked("/others", 404, null, List.of())))).isNull();
    }

    @Test
    void aWayInIsShownAndAskedAsTheCodeDeclaresIt() {
        String controller = """
                package p;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/Admin")
                public class C {
                    @GetMapping("profileDisplay") public String a() { return ""; }
                    @GetMapping("/Things/{thingId}") public String b() { return ""; }
                    @PostMapping("") public String c() { return ""; }
                    public static void main(String[] args) { }
                }
                """;
        List<EntryPoint> points = new ProgramAdapter(null, null, "x", 1).find(
                java.util.Map.of("src/main/java/p/C.java", controller), null);
        assertThat(points).extracting(EntryPoint::label)
                .containsExactlyInAnyOrder("GET /Admin/profileDisplay", "GET /Admin/Things/{thingId}", "POST /Admin");
        assertThat(ProgramAdapter.askable(points)).containsExactly("/Admin/profileDisplay");
    }

    @Test
    void claimsThatChangeLessAreExercisedFirstAndAlikeKeepTheirOrder() {
        var get = new com.walpro.autobuilder.core.build.CriterionProbeSynthesizer.ProbeRequest("GET", "/a", null);
        var post = new com.walpro.autobuilder.core.build.CriterionProbeSynthesizer.ProbeRequest("POST", "/a", "x=1");
        var k1 = new com.walpro.autobuilder.core.coldread.ClaimProposer.Claim("k1", "c", "changes twice", List.of(post, post, get), 200);
        var k2 = new com.walpro.autobuilder.core.coldread.ClaimProposer.Claim("k2", "c", "signs in and reads", List.of(post, get), 200);
        var k3 = new com.walpro.autobuilder.core.coldread.ClaimProposer.Claim("k3", "c", "reads", List.of(get), 200);
        var k4 = new com.walpro.autobuilder.core.coldread.ClaimProposer.Claim("k4", "c", "signs in and reads too", List.of(get, post, get), 200);
        assertThat(ProgramAdapter.readersFirst(List.of(k1, k2, k3, k4)))
                .extracting(com.walpro.autobuilder.core.coldread.ClaimProposer.Claim::id).containsExactly("k3", "k2", "k4", "k1");
        // A kept sequence has pages put in; it must sort where the proposed one did.
        var page = new com.walpro.autobuilder.core.build.CriterionProbeSynthesizer.ProbeRequest("GET", "/page", null);
        var other = new com.walpro.autobuilder.core.build.CriterionProbeSynthesizer.ProbeRequest("POST", "/b", "y=2");
        var order = com.walpro.autobuilder.core.build.CriterionExercises.EXERCISE_ORDER;
        assertThat(order.compare(List.of(page, post, get), List.of(post, get))).isZero();
        assertThat(order.compare(List.of(post), List.of(page, other))).isLessThan(0);
    }

    @Test
    void onlyGetWaysInWithoutAPlaceholderAreAsked() {
        List<EntryPoint> points = List.of(
                new EntryPoint(EntryKind.SERVICE_STYLE, "GET /things", "T", "p.T", "all"),
                new EntryPoint(EntryKind.SERVICE_STYLE, "GET /things/{id}", "T", "p.T", "one"),
                new EntryPoint(EntryKind.SERVICE_STYLE, "POST /things", "T", "p.T", "add"),
                new EntryPoint(EntryKind.COMMAND_STYLE, "program Main", "Main", "p.Main", "main"));
        assertThat(ProgramAdapter.askable(points)).containsExactly("/things");
    }
}
