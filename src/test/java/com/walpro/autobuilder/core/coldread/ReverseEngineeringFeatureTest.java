package com.walpro.autobuilder.core.coldread;

import com.walpro.autobuilder.core.build.CriterionProbeSynthesizer.ProbeRequest;
import com.walpro.autobuilder.core.coldread.ClaimProposer.Claim;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.FeatureVerdict;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.JudgedClaim;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Observation;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Variant;
import com.walpro.autobuilder.core.coldread.entry.EntryKind;
import com.walpro.autobuilder.core.coldread.entry.EntryPoint;
import com.walpro.autobuilder.core.coldread.entry.ProgramAdapter;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReverseEngineeringFeatureTest {

    private static final String APP = """
            package p;
            public class App { public static void main(String[] args) { } }
            """;
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
    private final ProgramAdapter program = new ProgramAdapter(null, null, "package", 1000);

    @Test
    void aStartedTreeWithRoutesIsUsedByThemAndAFeatureIsTheTypeThatDeclaresThem() {
        List<EntryPoint> points = program.find(Map.of("src/main/java/p/App.java", APP,
                "src/main/java/p/BookController.java", BOOKS, "src/main/java/p/LoanController.java", LOANS), null);
        assertThat(points).allSatisfy(e -> assertThat(e.kind()).isEqualTo(EntryKind.SERVICE_STYLE));
        assertThat(points).extracting(EntryPoint::label)
                .contains("POST /books", "GET /books/{}", "POST /loans", "POST /loans/{}/return", "GET /loans");
        var features = ReverseEngineeringService.featuresOf(points);
        assertThat(features.keySet()).containsExactly("BookController", "LoanController");
    }

    @Test
    void aStartedTreeWithoutRoutesIsUsedByItsProgramEntryAndATreeWithoutOneIsNotStarted() {
        assertThat(program.find(Map.of("src/main/java/p/App.java", APP), null)).extracting(EntryPoint::label)
                .containsExactly("program App");
        assertThat(program.find(Map.of("src/main/java/p/BookController.java", BOOKS), null)).isEmpty();
    }

    @Test
    void aClaimsOwnWayInIsTheMostSpecificOfThoseWhoseRemovalBreaksIt() {
        // The labelmaker shape that read a type-level rule backwards: next uses Zone.of; Labeler also has checkChar and
        // isValid, so the TYPE Labeler breaks more claims than the type Zone -- but per way in, Zone.of breaks its own
        // claims AND next's, so next is the more specific way in for a claim of next.
        Map<String, String> typeOf = Map.of("Zone.of", "Zone", "Labeler.next", "Labeler", "Labeler.checkChar", "Labeler",
                "Labeler.isValid", "Labeler");
        Map<String, Map<String, Observation>> seen = new LinkedHashMap<>();
        seen.put("z1", obs(Map.of("as-is", true, "entry:Zone.of", false, "entry:Labeler.next", true,
                "entry:Labeler.checkChar", true, "entry:Labeler.isValid", true)));
        seen.put("z2", obs(Map.of("as-is", true, "entry:Zone.of", false, "entry:Labeler.next", true,
                "entry:Labeler.checkChar", true, "entry:Labeler.isValid", true)));
        seen.put("n1", obs(Map.of("as-is", true, "entry:Zone.of", false, "entry:Labeler.next", false,
                "entry:Labeler.checkChar", false, "entry:Labeler.isValid", true)));
        seen.put("c1", obs(Map.of("as-is", true, "entry:Zone.of", true, "entry:Labeler.next", true,
                "entry:Labeler.checkChar", false, "entry:Labeler.isValid", true)));
        seen.put("v1", obs(Map.of("as-is", true, "entry:Zone.of", true, "entry:Labeler.next", true,
                "entry:Labeler.checkChar", false, "entry:Labeler.isValid", false)));
        var v = ReverseEngineeringService.attribute(List.of(claim("z1"), claim("z2"), claim("n1"), claim("c1"), claim("v1")),
                seen, ran(typeOf.keySet()), typeOf);

        assertThat(v.get("n1").entry()).isEqualTo("Labeler.next");
        assertThat(v.get("n1").feature()).isEqualTo("Labeler");
        assertThat(v.get("n1").dependsOn()).containsExactly("Zone");      // checkChar is Labeler's own: not a dependency
        assertThat(v.get("z1").feature()).isEqualTo("Zone");
        assertThat(v.get("z1").dependsOn()).isEmpty();
        assertThat(v.get("v1").entry()).isEqualTo("Labeler.isValid");
        assertThat(v.get("v1").dependsOn()).isEmpty();
    }

    @Test
    void aClaimNoWayInsRemovalBreaksIsNotAnchoredAndOneTheTreeRefutesIsNotHeld() {
        Map<String, String> typeOf = Map.of("A.a", "A", "B.b", "B");
        Map<String, Map<String, Observation>> seen = new LinkedHashMap<>();
        seen.put("k1", obs(Map.of("as-is", true, "entry:A.a", true, "entry:B.b", true)));
        seen.put("k2", obs(Map.of("as-is", false, "entry:A.a", false, "entry:B.b", false)));
        var v = ReverseEngineeringService.attribute(List.of(claim("k1"), claim("k2")), seen, ran(typeOf.keySet()), typeOf);
        assertThat(v.get("k1").verdict()).isEqualTo("not-anchored");
        assertThat(v.get("k2").verdict()).isEqualTo("not-held");
    }

    @Test
    void aWayInVariantThatDidNotRunJudgesNothing() {
        Map<String, String> typeOf = Map.of("A.a", "A", "B.b", "B");
        Map<String, Variant> variants = new LinkedHashMap<>(ran(typeOf.keySet()));
        variants.put("entry:A.a", new Variant("entry:A.a", List.of("A.a"), false, false, "did not build"));
        Map<String, Map<String, Observation>> seen = new LinkedHashMap<>();
        seen.put("k1", obs(Map.of("as-is", true, "entry:B.b", true)));
        var v = ReverseEngineeringService.attribute(List.of(claim("k1")), seen, variants, typeOf);
        assertThat(v.get("k1").verdict()).isEqualTo("not-anchored");
        assertThat(v.get("k1").feature()).isNull();
    }

    @Test
    void aFeatureSummaryCarriesItsKindKeptClaimsDependenciesAndTheCodeItIsMadeOf() {
        var byFeature = ReverseEngineeringService.featuresOf(program.find(Map.of("src/main/java/p/App.java", APP,
                "src/main/java/p/LoanController.java", LOANS), null));
        Claim kept = new Claim("k1", "c2", "lend", List.of(new ProbeRequest("POST", "/loans", "{}")), 201);
        Claim dropped = new Claim("k2", "c2", "x", List.of(new ProbeRequest("POST", "/loans", "{}")), 400);
        var judged = List.of(
                new JudgedClaim(kept, "anchored", List.of("c1", "c5"), List.of(),
                        new FeatureVerdict("LoanController", "anchored", List.of("BookController"))),
                new JudgedClaim(dropped, "anchored", List.of("c9"), List.of(),
                        new FeatureVerdict(null, "not-anchored", List.of())));
        var f = ReverseEngineeringService.summarise(byFeature, judged).get(0);
        assertThat(f.name()).isEqualTo("LoanController");
        assertThat(f.kind()).isEqualTo("service-style");
        assertThat(f.claims()).containsExactly("k1");
        assertThat(f.dependsOn()).containsExactly("BookController");
        assertThat(f.madeOf()).containsExactly("c1", "c2", "c5");
        assertThat(f.entryPoints()).hasSize(3);
    }

    private static Claim claim(String id) {
        return new Claim(id, "c1", "t", List.of(new ProbeRequest("GET", "/x", null)), 200);
    }

    private static Map<String, Observation> obs(Map<String, Boolean> held) {
        Map<String, Observation> m = new LinkedHashMap<>();
        held.forEach((v, h) -> m.put(v, new Observation(v, h ? 200 : 500, null, null, h)));
        return m;
    }

    private static Map<String, Variant> ran(Set<String> entries) {
        Map<String, Variant> m = new LinkedHashMap<>();
        m.put("as-is", new Variant(null, List.of(), true, true, null));
        for (String e : entries) m.put("entry:" + e, new Variant("entry:" + e, List.of(e), true, true, null));
        return m;
    }
}
