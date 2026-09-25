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

    /** Where a candidate's own tests fail: which named entry points do THOSE tests call, whatever they assert. */
    @Test
    void theEntryPointsTheNamedTestsCallAreFound_withOnlyThoseTestsAsCallers() {
        var files = tests("""
                @Test void failing() { String s = Gadget.describe(new Part()); assertEquals("x", s); }
                @Test void passing() { assertEquals("wheel", Gadget.describe(new Part())); }
                @Test void other() { assertEquals(1, Gadget.count()); }
                """);

        var called = EntryPointAssertions.calledBy(
                List.of(OBLIGATION, "Gadget.count() is covered by a test", "rule: Gadget.describe(part) keep it"),
                files, java.util.Set.of("GadgetTest.failing"));

        assertThat(called).containsOnlyKeys("Gadget.describe");
        assertThat(called.get("Gadget.describe").callingTests()).containsExactly("GadgetTest.failing");
    }

    /** Null is an exact value: assertNull is assertEquals(null, x). */
    @Test
    void assertingNullIsAssertingAValue() {
        assertThat(EntryPointAssertions.unmet(List.of(OBLIGATION), tests("""
                @Test void nothingForNoPart() { assertNull(Gadget.describe(null)); }
                """))).isEmpty();
    }

    /**
     * participation, the second module adopted: immutable values are tested in chains and through
     * {@code var} locals, and two types can share a method name. A call is the entry point's when the
     * project's own declarations say its receiver IS that type -- and not when it is the other one.
     */
    @Test
    void aCallIsAttributedByTheReceiversDeclaredType_throughChainsAndVarLocals() {
        var files = new java.util.LinkedHashMap<String, String>();
        files.put("src/main/java/shop/Crate.java", """
                package shop;
                public record Crate(java.util.List<Box> boxes) {
                    public static Crate empty() { return new Crate(java.util.List.of()); }
                    public Crate stack(Box b) { return this; }
                    public Crate seal() { return this; }
                    public Box top() { return boxes.get(0); }
                }
                """);
        files.put("src/main/java/shop/Box.java", """
                package shop;
                public record Box(String label, int weight) {
                    public static Box of(String label) { return new Box(label, 1); }
                    public Box seal() { return this; }
                }
                """);
        String stack = "Crate.stack(box) is covered by a test";
        String sealCrate = "Crate.seal() is covered by a test";
        String sealBox = "Box.seal() is covered by a test";

        files.put("src/test/java/shop/CrateTest.java", HEAD + """
                class CrateTest {
                    @Test void chained() { assertEquals(1, Crate.empty().stack(Box.of("a")).boxes().size()); }
                    @Test void viaVar() { var c = Crate.empty().stack(Box.of("a")); var s = c.seal(); assertEquals(c, s); }
                    @Test void boxOnly() { var b = Box.of("a"); assertEquals("a", b.seal().label()); }
                }
                """);
        assertThat(EntryPointAssertions.unmet(List.of(stack, sealCrate, sealBox), files)).isEmpty();

        files.put("src/test/java/shop/CrateTest.java", HEAD + """
                class CrateTest {
                    @Test void boxOnly() { var b = Crate.empty().top(); assertEquals("a", b.seal().label()); }
                }
                """);
        assertThat(EntryPointAssertions.unmet(List.of(sealCrate, sealBox), files)).containsOnlyKeys(sealCrate);
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
