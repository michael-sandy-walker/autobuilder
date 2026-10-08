package com.walpro.autobuilder.protocol.evolution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T6-T, member-level application: a whole file written for an existing one changes what it wrote and nothing else.
 * Invented coordinates; the shapes are ladder-grind field50's (a partial rewrite of a large class, a member written
 * alone, a skeleton stub replaced, a duplicated annotation).
 */
class MemberMergeTest {

    private static final String INK = """
            package org.ink;

            import java.util.List;

            @Deprecated
            public class Ink {
                private int drops;

                /** Pours. */
                public int pour(int n) { return drops + n; }

                public String colour() { return "blue"; }

                public java.util.List<String> blots() { throw new UnsupportedOperationException("skeleton"); }
            }
            """;

    @Test
    void writtenMembersReplaceOrAreAdded_andLeftOutOnesAreKept_withoutDuplicates() {
        String written = """
                package org.ink;
                import java.util.Map;
                @Deprecated
                @SuppressWarnings("x")
                public class Ink {
                    public int pour(int n) { return drops + n + 1; }
                    public Map<String, Integer> tally() { return Map.of(); }
                }
                """;

        MemberMerge.Merged m = MemberMerge.merge(INK, written).orElseThrow();

        assertThat(m.content()).contains("return drops + n + 1;", "public String colour()", "private int drops;",
                "public Map<String, Integer> tally()", "import java.util.Map;", "@SuppressWarnings(\"x\")", "/** Pours. */")
                .doesNotContain("return drops + n;");
        assertThat(m.content().split("@Deprecated", -1)).as("an annotation it had is not added twice").hasSize(2);
        assertThat(m.replaced()).containsExactly("pour(int)");
        assertThat(m.added()).containsExactly("tally()");
    }

    @Test
    void aSkeletonStubIsReplacedByTheWrittenMemberOfItsNameAndArity_evenWithOtherTypes() {
        String written = "package org.ink;\npublic class Ink {\n  public java.util.ArrayList<String> blots() { return new java.util.ArrayList<>(); }\n}\n";

        MemberMerge.Merged m = MemberMerge.merge(INK, written).orElseThrow();

        assertThat(m.content()).contains("return new java.util.ArrayList<>();").doesNotContain("\"skeleton\"");
    }

    @Test
    void aFragmentOfMembersIsReadAsTheBodyOfTheExistingType_aCopiedClosingBraceTolerated() {
        String fragment = """
                    /** A blot. */
                    record Blot(String where) {
                    }
                }
                """;

        MemberMerge.Merged m = MemberMerge.merge(INK, fragment).orElseThrow();

        assertThat(m.content()).contains("record Blot(String where)", "public String colour()");
        assertThat(m.added()).containsExactly("type Blot");
    }

    @Test
    void whatDoesNotParse_isNotMerged() {
        assertThat(MemberMerge.merge(INK, "public class Ink { int x = ; ")).isEmpty();
        assertThat(MemberMerge.merge("not java", "public class Ink {}")).isEmpty();
    }
}
