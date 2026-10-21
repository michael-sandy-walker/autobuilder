package com.walpro.autobuilder.core.merge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What structure says two projects share: types alike, types perhaps alike, operations of one name, criteria. */
class ProjectMatcherTest {

    private static final String SPAN = """
            package a;
            public record Span(int from, int to) {
                public boolean contains(int x) { return x >= from && x <= to; }
                public int size() { return to - from + 1; }
            }
            """;

    private static final Map<String, String> A = Map.of(
            "src/main/java/a/Span.java", SPAN,
            "src/main/java/a/Price.java", """
                    package a;
                    public record Price(long cents, String currency) {
                        public static Price parse(String s) { return null; }
                        public Price plus(Price o) { return this; }
                        public Price times(int n) { return this; }
                    }
                    """,
            "src/main/java/a/Bill.java", """
                    package a;
                    public class Bill {
                        public Price total() { return null; }
                        public void add(Price p) { }
                    }
                    """);

    private static final Map<String, String> B = Map.of(
            "src/main/java/b/Span.java", SPAN.replace("package a;", "package b;"),
            "src/main/java/b/Sum.java", """
                    package b;
                    public record Sum(long cents) {
                        public static Sum parse(String s) { return null; }
                        public Sum plus(Sum o) { return this; }
                        public Sum negate() { return this; }
                    }
                    """,
            "src/main/java/b/Purse.java", """
                    package b;
                    public class Purse {
                        public Sum total() { return null; }
                        public void put(Sum s) { }
                        public int count() { return 0; }
                    }
                    """);

    @Test
    void theSameTypeWrittenTwiceIsExact_oneConceptUnderTwoNamesIsACandidate_oneOperationNameOnTwoTypesIsPutToAReader() {
        List<ProjectMatcher.Match> found = ProjectMatcher.match(A, B, List.of(), List.of());

        assertThat(found).anySatisfy(m -> {
            assertThat(m.kind()).isEqualTo(ProjectMatcher.Kind.TYPE);
            assertThat(m.a()).isEqualTo("Span");
            assertThat(m.level()).isEqualTo(ProjectMatcher.Level.EXACT);
        });
        assertThat(found).anySatisfy(m -> {
            assertThat(m.a() + "~" + m.b()).isEqualTo("Price~Sum");
            assertThat(m.level()).isEqualTo(ProjectMatcher.Level.CANDIDATE);
            assertThat(m.why()).contains("plus(SELF)", "static parse(String)");
        });
        assertThat(found).anySatisfy(m -> {
            assertThat(m.kind()).isEqualTo(ProjectMatcher.Kind.OPERATION);
            assertThat(m.a() + "~" + m.b()).isEqualTo("Bill.total()~Purse.total()");
            assertThat(m.why()).contains("Price", "Sum");
        });
        assertThat(found).noneSatisfy(m -> assertThat(m.a() + "~" + m.b()).isEqualTo("Bill~Purse"));
    }

    @Test
    void aTypeOfOneNameWithAnotherSurfaceIsNotExact() {
        Map<String, String> b = Map.of("src/main/java/b/Span.java",
                SPAN.replace("package a;", "package b;").replace("public int size()", "public long days()"));
        List<ProjectMatcher.Match> found = ProjectMatcher.match(Map.of("src/main/java/a/Span.java", SPAN), b, List.of(), List.of());
        assertThat(found).singleElement().satisfies(m -> {
            assertThat(m.level()).isEqualTo(ProjectMatcher.Level.SAME_NAME_DIFFERENT_SHAPE);
            assertThat(m.why()).contains("size()", "days()");
        });
    }

    @Test
    void criteriaOfTheSameTextAreExactAndCriteriaSharingMostWordsAreCandidates() {
        List<ProjectMatcher.Match> found = ProjectMatcher.match(Map.of(), Map.of(),
                List.of("A Span runs from its first to its last value inclusive", "A Bill totals its prices"),
                List.of("a span runs from its first to its last value inclusive.", "A Bill totals its prices in order",
                        "Sum.negate changes the sign"));
        assertThat(found).filteredOn(m -> m.level() == ProjectMatcher.Level.EXACT).hasSize(1);
        assertThat(found).filteredOn(m -> m.level() == ProjectMatcher.Level.CANDIDATE)
                .extracting(ProjectMatcher.Match::a).containsOnly("A Bill totals its prices");
    }
}
