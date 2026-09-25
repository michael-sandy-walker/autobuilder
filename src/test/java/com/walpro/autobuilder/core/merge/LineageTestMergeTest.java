package com.walpro.autobuilder.core.merge;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-CS: two lineages' test layers over identical library code. Invented coordinates; the shapes are
 * participation and participation-b's (one test class written by both, different seeds).
 */
class LineageTestMergeTest {

    private static final String PATH = "shop/CrateTest.java";

    private static String crateTest(String members) {
        return "package shop;\nimport org.junit.jupiter.api.Test;\nimport static org.junit.jupiter.api.Assertions.*;\n"
                + "class CrateTest {\n" + members + "\n}\n";
    }

    @Test
    void aFileOnlyOneParentHasIsTakenWhole_andAnIdenticalOneOnce() {
        String same = crateTest("@Test void stacks() { assertEquals(1, 1); }");
        var r = LineageTestMerge.merge(
                Map.of(PATH, same, "shop/BoxTest.java", "package shop;\nclass BoxTest { @Test void a() {} }\n"),
                Map.of(PATH, same, "shop/LidTest.java", "package shop;\nclass LidTest { @Test void b() {} }\n"), "A", "B");

        assertThat(r.files()).containsOnlyKeys(PATH, "shop/BoxTest.java", "shop/LidTest.java");
        assertThat(r.files().get(PATH)).isEqualTo(same);
        assertThat(r.provenance()).contains(new LineageTestMerge.Origin(PATH, "method stacks()", "A+B"),
                new LineageTestMerge.Origin("shop/LidTest.java", "method b()", "B"));
        assertThat(r.conflicts()).isEmpty();
    }

    @Test
    void aClassBothWroteKeepsEveryTest_sharedOnesOnce_andUnionsImports() {
        var r = LineageTestMerge.merge(
                Map.of(PATH, crateTest("@Test void stacks() { assertEquals(1, 1); }\n@Test void seals() { assertTrue(true); }")),
                Map.of(PATH, "package shop;\nimport java.util.List;\n" + crateTest("@Test void stacks() { assertEquals(1, 1); }\n"
                        + "@Test void empties() { assertEquals(List.of(), List.of()); }").substring("package shop;\n".length())),
                "A", "B");

        String merged = r.files().get(PATH);
        assertThat(merged).contains("void stacks()", "void seals()", "void empties()", "import java.util.List;");
        assertThat(merged.split("void stacks\\(\\)", -1)).hasSize(2);
        assertThat(r.provenance()).contains(new LineageTestMerge.Origin(PATH, "method stacks()", "A+B"),
                new LineageTestMerge.Origin(PATH, "method seals()", "A"), new LineageTestMerge.Origin(PATH, "method empties()", "B"));
    }

    /** Same signature, different text: both kept, and the second parent's tests call ITS helper and read ITS field. */
    @Test
    void aDifferingHelperAndFieldAreRenamedTogetherWithTheSecondParentsReferencesToThem() {
        var r = LineageTestMerge.merge(
                Map.of(PATH, crateTest("private final int size = 1;\nprivate Crate crate() { return new Crate(size); }\n"
                        + "@Test void stacks() { assertEquals(1, crate().count()); }")),
                Map.of(PATH, crateTest("private final int size = 2;\nprivate Crate crate() { return Crate.of(size); }\n"
                        + "@Test void counts() { assertEquals(2, this.crate().count()); int size = 5; assertEquals(5, size); }")),
                "A", "B");

        String merged = r.files().get(PATH);
        assertThat(merged).contains("private final int size = 1;", "private final int size_B = 2;",
                "return new Crate(size);", "return Crate.of(size_B);", "private Crate crate_B()",
                "assertEquals(1, crate().count())", "assertEquals(2, this.crate_B().count())",
                "int size = 5;", "assertEquals(5, size)");   // a local that shadows the field is left alone
        assertThat(r.conflicts()).isEmpty();
    }

    @Test
    void aTestWrittenDifferentlyUnderTheSameNameIsKeptTwice() {
        var r = LineageTestMerge.merge(Map.of(PATH, crateTest("@Test void stacks() { assertEquals(1, 1); }")),
                Map.of(PATH, crateTest("@Test void stacks() { assertEquals(2, 2); }")), "lineage-a", "lineage-b");

        assertThat(r.files().get(PATH)).contains("void stacks()", "void stacks_lineageb()", "assertEquals(2, 2)");
    }

    /** What it cannot merge member by member it does not guess at: the first parent's is kept, and it says so. */
    @Test
    void aDifferingConstructorIsReportedNotMerged() {
        var r = LineageTestMerge.merge(Map.of(PATH, crateTest("CrateTest() { }\n@Test void a() {}")),
                Map.of(PATH, crateTest("CrateTest() { System.out.println(); }\n@Test void b() {}")), "A", "B");

        assertThat(r.files().get(PATH)).contains("void a()", "void b()").doesNotContain("System.out");
        assertThat(r.conflicts()).singleElement().asString().contains("constructor()").contains("A's kept");
    }
}
