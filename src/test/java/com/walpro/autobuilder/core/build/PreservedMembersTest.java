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
}
