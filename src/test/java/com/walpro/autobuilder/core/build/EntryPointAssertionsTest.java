package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-CQ: an obligation naming an entry point is met only by a test that calls it and asserts a value
 * it returns. Invented coordinates; the shapes are structdiff run 8's tests.
 */
class EntryPointAssertionsTest {

    private static final String HEAD = "package shop;\nimport org.junit.jupiter.api.Test;\n"
            + "import static org.junit.jupiter.api.Assertions.*;\n";

    private static Map<String, String> tests(String body) {
        return Map.of("src/test/java/shop/GadgetTest.java", HEAD + "class GadgetTest {\n" + body + "\n}\n");
    }

    private static final String OBLIGATION = "Gadget.describe(part) is covered by a test in the project's own test suite";

    @Test
    void aTestAssertingOnlyThatSomethingCameBackDoesNotMeetIt() {
        var unmet = EntryPointAssertions.unmet(List.of(OBLIGATION), tests("""
                @Test void describeReturnsNonEmptyString() {
                    String s = Gadget.describe(part());
                    assertNotNull(s);
                    assertFalse(s.isEmpty());
                }
                private Part part() { return new Part(); }
                """));

        assertThat(unmet).containsOnlyKeys(OBLIGATION);
        assertThat(unmet.get(OBLIGATION).describe())
                .contains("GadgetTest.describeReturnsNonEmptyString")
                .contains("assert only assertNotNull, assertFalse")
                .contains("none asserts a value it returns");
    }

    @Test
    void aValueAssertionMeetsIt_staticOrInstanceOrThroughAHelper() {
        assertThat(EntryPointAssertions.unmet(List.of(OBLIGATION), tests("""
                @Test void describes() { assertEquals("wheel", Gadget.describe(new Part())); }
                """))).isEmpty();
        assertThat(EntryPointAssertions.unmet(List.of(OBLIGATION), tests("""
                private final Gadget gadget = new Gadget();
                @Test void describes() { String s = gadget.describe(new Part()); assertThat(s).isEqualTo("wheel"); }
                """))).isEmpty();
        assertThat(EntryPointAssertions.unmet(List.of(OBLIGATION), tests("""
                private String run() { return Gadget.describe(new Part()); }
                @Test void describes() { assertEquals("wheel", run()); }
                """))).isEmpty();
    }

    /** For a boolean entry point the truth assertion around its own call IS the exact value. */
    @Test
    void aTruthAssertionCountsOnlyAroundTheEntryPointsOwnCall() {
        String obligation = "Gadget.isWorn(part) is covered by a test";
        assertThat(EntryPointAssertions.unmet(List.of(obligation), tests("""
                @Test void worn() { assertTrue(Gadget.isWorn(new Part())); }
                """))).isEmpty();
        assertThat(EntryPointAssertions.unmet(List.of(obligation), tests("""
                @Test void worn() { boolean w = Gadget.isWorn(new Part()); assertTrue(w || !w); }
                """))).containsOnlyKeys(obligation);
    }

    @Test
    void anEntryPointNoTestCallsIsReportedAsSuch() {
        var unmet = EntryPointAssertions.unmet(List.of(OBLIGATION), tests("@Test void other() { assertEquals(1, 1); }"));

        assertThat(unmet.get(OBLIGATION).describe()).isEqualTo("Gadget.describe is called by no test");
    }

    /** No opinion on a project whose tests import none of the known ecosystems. */
    @Test
    void sourcesWrittenAgainstNoKnownEcosystemAreNotJudged() {
        var files = Map.of("src/test/java/shop/GadgetTest.java",
                "package shop;\nclass GadgetTest { void t() { Gadget.describe(null); } }\n");

        assertThat(EntryPointAssertions.unmet(List.of(OBLIGATION), files)).isEmpty();
    }

    @Test
    void anObligationNamingNoEntryPointOrNoTestIsNotJudged() {
        assertThat(EntryPointAssertions.unmet(List.of("The whole suite passes", "Gadget.describe(part) returns text"),
                tests("@Test void t() {}"))).isEmpty();
    }
}
