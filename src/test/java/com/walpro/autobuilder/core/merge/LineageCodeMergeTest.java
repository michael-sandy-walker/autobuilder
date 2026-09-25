package com.walpro.autobuilder.core.merge;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-CS: two lineages grew one adopted library in different directions; the child is merged against
 * the tree both started from, one declaration at a time. Invented coordinates; the shape is
 * participation-roles and participation-totals (each added methods to the same class).
 */
class LineageCodeMergeTest {

    private static final String PATH = "zeta/Inkwell.java";

    private static final String BASE = """
            package zeta;

            import java.util.Map;

            public record Inkwell(Map<String, Integer> wells) {
                public int level(String well) { return wells.getOrDefault(well, 0); }
            }
            """;

    private static String with(String imports, String members) {
        return "package zeta;\n\nimport java.util.Map;\n" + imports + "\npublic record Inkwell(Map<String, Integer> wells) {\n"
                + "    public int level(String well) { return wells.getOrDefault(well, 0); }\n" + members + "}\n";
    }

    @Test
    void eachParentsAdditionToTheSameClassIsKept_andTheirImportsUnioned() {
        String a = with("import java.util.List;\n", "    public List<String> names() { return List.copyOf(wells.keySet()); }\n");
        String b = with("import java.util.Optional;\n", "    public Optional<String> first() { return wells.keySet().stream().findFirst(); }\n");

        var r = LineageCodeMerge.merge(Map.of(PATH, BASE), Map.of(PATH, a), Map.of(PATH, b), "roles", "totals");

        assertThat(r.files().get(PATH)).contains("public List<String> names()", "public Optional<String> first()",
                "import java.util.List;", "import java.util.Optional;", "public int level(String well)");
        assertThat(r.files().get(PATH).split("public int level", -1)).hasSize(2);
        assertThat(r.conflicts()).isEmpty();
        assertThat(r.provenance()).contains(new LineageTestMerge.Origin(PATH, "Inkwell.first()", "totals"),
                new LineageTestMerge.Origin(PATH, "Inkwell.names()", "roles"));
    }

    @Test
    void aDeclarationOnlyOneParentChangedTakesThatChange_andAnIdenticalAdditionIsTakenOnce() {
        String a = with("", "    public int total() { return 0; }\n");
        String b = BASE.replace("wells.getOrDefault(well, 0)", "wells.getOrDefault(well, -1)")
                .replace("}\n}", "}\n    public int total() { return 0; }\n}");

        var r = LineageCodeMerge.merge(Map.of(PATH, BASE), Map.of(PATH, a), Map.of(PATH, b), "A", "B");

        assertThat(r.files().get(PATH)).contains("wells.getOrDefault(well, -1)").doesNotContain("getOrDefault(well, 0)");
        assertThat(r.files().get(PATH).split("public int total", -1)).hasSize(2);
        assertThat(r.conflicts()).isEmpty();
    }

    /** The same declaration made two ways is not decided here: the first is kept and it is reported. */
    @Test
    void aDeclarationBothParentsMadeDifferentlyIsAReportedConflict() {
        String a = with("", "    public int total() { return wells.size(); }\n");
        String b = with("", "    public int total() { return wells.values().stream().mapToInt(Integer::intValue).sum(); }\n");

        var r = LineageCodeMerge.merge(Map.of(PATH, BASE), Map.of(PATH, a), Map.of(PATH, b), "A", "B");

        assertThat(r.files().get(PATH)).contains("return wells.size();").doesNotContain("mapToInt");
        assertThat(r.conflicts()).singleElement().asString().contains("Inkwell.total()", "made differently by both parents", "A's kept");
    }

    @Test
    void aFileOnlyOneParentChangedIsTakenFromIt() {
        String other = "package zeta;\npublic class Nib { }\n";
        String changed = "package zeta;\npublic class Nib { public int width() { return 1; } }\n";

        var r = LineageCodeMerge.merge(Map.of(PATH, BASE, "zeta/Nib.java", other),
                Map.of(PATH, BASE, "zeta/Nib.java", other), Map.of(PATH, BASE, "zeta/Nib.java", changed), "A", "B");

        assertThat(r.files()).containsEntry("zeta/Nib.java", changed).containsEntry(PATH, BASE);
    }
}
