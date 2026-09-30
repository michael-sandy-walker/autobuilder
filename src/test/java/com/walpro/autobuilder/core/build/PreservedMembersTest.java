package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-CQ: a file's adopted declarations survive a writer that rewrote it; its new ones are kept.
 * Invented coordinates; the shapes are participation-roles run 1's rewrite (a merge turned into a
 * replace, a shared constant dropped, null handling removed, a method added beside them).
 */
class PreservedMembersTest {

    private static final String ADOPTED = """
            package zeta;

            import java.util.Map;

            public record Inkwell(Map<String, Integer> wells) {

                private static final Inkwell DRY = new Inkwell(Map.of());

                public Inkwell {
                    wells = wells == null ? Map.of() : Map.copyOf(wells);
                }

                /** Adds to what is there. */
                public Inkwell pour(String well, int drops) {
                    java.util.HashMap<String, Integer> next = new java.util.HashMap<>(wells);
                    next.merge(well, drops, Integer::sum);
                    return new Inkwell(next);
                }

                public record Level(String well, int drops) {
                    public boolean empty() { return drops == 0; }
                }
            }
            """;

    @Test
    void aChangedMemberIsPutBackAsWritten_aRemovedOneReturns_andTheAddedOneStays() {
        String rewrite = """
                package zeta;

                import java.util.Map;

                public record Inkwell(Map<String, Integer> wells) {
                    public Inkwell {
                        wells = Map.copyOf(wells);
                    }
                    public Inkwell pour(String well, int drops) {
                        java.util.HashMap<String, Integer> next = new java.util.HashMap<>(wells);
                        next.put(well, drops);
                        return new Inkwell(next);
                    }
                    public int total() { return wells.values().stream().mapToInt(Integer::intValue).sum(); }
                    public record Level(String well, int drops) {
                        public boolean empty() { return drops <= 0; }
                        public boolean full() { return drops > 9; }
                    }
                }
                """;

        var r = PreservedMembers.restore(ADOPTED, rewrite);

        assertThat(r.content())
                .contains("next.merge(well, drops, Integer::sum);", "wells = wells == null ? Map.of() : Map.copyOf(wells);",
                        "private static final Inkwell DRY = new Inkwell(Map.of());", "public boolean empty() { return drops == 0; }")
                .contains("public int total()", "public boolean full()")
                .doesNotContain("next.put(well, drops);", "drops <= 0");
        assertThat(r.restored()).containsExactlyInAnyOrder("Inkwell.Inkwell{compact} (changed)", "Inkwell.pour(String,int) (changed)",
                "Inkwell.field DRY (removed)", "Inkwell.Level.empty() (changed)");
        // The restored file is the adopted one plus the additions -- restoring it again changes nothing.
        assertThat(PreservedMembers.restore(ADOPTED, r.content()).restored()).isEmpty();
    }

    /**
     * participation-totals run 2026-09-25_20-51-35: the rewrite also dropped a record component and two
     * imports. The result is the ADOPTED file plus what was added -- its header and imports included --
     * so no restored member can refer to something the rewrite took away.
     */
    @Test
    void aRewrittenHeaderAndDroppedImportsAreTheAdoptedOnes_withTheAdditionKept() {
        String rewrite = """
                package zeta;

                public record Inkwell(java.util.Map<String, Integer> wells, int spare) {
                    public Inkwell pour(String well, int drops) { return this; }
                    public int total() { return wells.size(); }
                }
                """;

        var r = PreservedMembers.restore(ADOPTED, rewrite);

        assertThat(r.content()).startsWith(ADOPTED.substring(0, ADOPTED.indexOf("public record")))
                .contains("public record Inkwell(Map<String, Integer> wells) {", "next.merge(well, drops, Integer::sum);",
                        "/** Adds to what is there. */", "public int total() { return wells.size(); }")
                .doesNotContain("int spare");
        assertThat(r.restored()).contains("Inkwell declaration (changed)", "Inkwell.pour(String,int) (changed)",
                "Inkwell.field DRY (removed)");
    }

    /** Comments and layout are the writer's: only a difference in the code puts a member back. */
    @Test
    void aReformattedOrUncommentedMemberIsNotAChange() {
        String reformatted = ADOPTED.replace("/** Adds to what is there. */\n", "")
                .replace("public boolean empty() { return drops == 0; }", "public boolean empty() {\n            return drops == 0;\n        }");

        var r = PreservedMembers.restore(ADOPTED, reformatted);

        assertThat(r.restored()).isEmpty();
        assertThat(r.content()).isSameAs(reformatted);
    }

    @Test
    void aCandidateThatDoesNotParseIsLeftForTheCompilerToReport() {
        String broken = ADOPTED.replace("return new Inkwell(next);", "return new Inkwell(next;");

        assertThat(PreservedMembers.restore(ADOPTED, broken).content()).isSameAs(broken);
    }

    /**
     * An open member (the operator's "members: <glob> except answer"): the writer's change to it lands, in
     * place of the adopted one; a change to any other member is still restored (receiver-questions run
     * 2026-09-26_20-45-54 had its change to answer() undone three times).
     */
    @Test
    void anOpenMembersChangeLandsAndEveryOtherMemberIsStillKept() {
        String adopted = "package org.ink;\n\npublic final class Router {\n\n"
                + "    public static String answer(String s) {\n        return null;\n    }\n\n"
                + "    static int weight() {\n        return 1;\n    }\n}\n";
        String candidate = "package org.ink;\n\npublic final class Router {\n\n"
                + "    public static String answer(String s) {\n        return helper(s);\n    }\n\n"
                + "    static int weight() {\n        return 2;\n    }\n\n"
                + "    static String helper(String s) {\n        return s;\n    }\n}\n";

        PreservedMembers.Result r = PreservedMembers.restore(adopted, candidate, java.util.Set.of("answer"));

        org.assertj.core.api.Assertions.assertThat(r.content()).contains("return helper(s);")
                .contains("static String helper(String s)").contains("return 1;").doesNotContain("return 2;");
        org.assertj.core.api.Assertions.assertThat(r.restored()).containsExactly("Router.weight() (changed)");
        org.assertj.core.api.Assertions.assertThat(PreservedMembers.restore(adopted, candidate).content())
                .as("without the exception answer() is restored as before").contains("return null;");
    }
    /**
     * An open NESTED type is taken whole, its header too: a record that gains a component and a compatibility
     * constructor lands as written. Walked into member by member, the adopted header came back beside the new
     * constructor -- a record that cannot compile (parameterized-rows pilot 2026-09-30, three times).
     */
    @Test
    void anOpenNestedRecordIsTakenWholeHeaderIncluded() {
        String adopted = """
                package org.ink;
                public record Report(int run) {
                    public record Failure(String name, String message) {}
                    public boolean passes() { return run > 0; }
                }
                """;
        String candidate = """
                package org.ink;
                public record Report(int run) {
                    public record Failure(String name, String message, String input) {
                        public Failure(String name, String message) {
                            this(name, message, null);
                        }
                    }
                    public boolean passes() { return run > 1; }
                }
                """;

        PreservedMembers.Result r = PreservedMembers.restore(adopted, candidate, java.util.Set.of("Failure"));

        org.assertj.core.api.Assertions.assertThat(r.content())
                .contains("record Failure(String name, String message, String input)", "this(name, message, null);")
                .as("a member that is not open is still kept as adopted").contains("return run > 0;");
        org.assertj.core.api.Assertions.assertThat(r.restored()).noneMatch(x -> x.contains("Failure"));
    }
}
