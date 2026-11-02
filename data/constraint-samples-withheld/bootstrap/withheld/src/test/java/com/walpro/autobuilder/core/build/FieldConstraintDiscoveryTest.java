package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-DB: the shapes here are copied from the validation a real run wrote
 * (`2026-08-23_02-12-34`, `BookService`/`MemberService`), not invented — the ticket's own record
 * is that pinning against real criterion text is what kept slice 1 honest.
 */
class FieldConstraintDiscoveryTest {

    private static final String BOOK_SERVICE = """
            package com.example.library.domain.service;

            import java.util.regex.Pattern;

            public class BookService {
                private static final Pattern ISBN_PATTERN = Pattern.compile("\\\\d{13}");

                public Book create(String title, String isbn) {
                    if (title == null || title.isBlank()) {
                        throw new IllegalArgumentException("title is required");
                    }
                    if (title.length() > 120) {
                        throw new IllegalArgumentException("title must be at most 120 characters");
                    }
                    if (!ISBN_PATTERN.matcher(isbn).matches()) {
                        throw new IllegalArgumentException("isbn must match \\\\d{13}");
                    }
                    return null;
                }
            }
            """;

    @Test
    void aPatternConstantBecomesTheConstraintOfTheFieldItIsMatchedAgainst() {
        Map<String, FieldConstraintDiscovery.Constraint> found =
                FieldConstraintDiscovery.fromSource(BOOK_SERVICE);

        assertThat(found).containsKey("isbn");
        assertThat(found.get("isbn").regex()).isEqualTo("\\d{13}");
    }

    @Test
    void aLengthComparisonBecomesTheGreatestLengthStillAccepted() {
        Map<String, FieldConstraintDiscovery.Constraint> found =
                FieldConstraintDiscovery.fromSource(BOOK_SERVICE);

        // "> 120" rejects 121 and above, so 120 itself is still acceptable.
        assertThat(found.get("title").maxLength()).isEqualTo(120);
    }

    @Test
    void aMatcherBuiltFromAnUnseenPatternIsNotRecordedAsAConstraint() {
        // Recording it with a null regex would read as "unconstrained", which is a stronger claim
        // than "we did not find out".
        Map<String, FieldConstraintDiscovery.Constraint> found = FieldConstraintDiscovery.fromSource("""
                public class Elsewhere {
                    void check(String code) {
                        if (!EXTERNAL_PATTERN.matcher(code).matches()) throw new IllegalArgumentException();
                    }
                }
                """);

        assertThat(found).doesNotContainKey("code");
    }

    @Test
    void anInlineMatchesCallIsDiscoveredToo() {
        Map<String, FieldConstraintDiscovery.Constraint> found = FieldConstraintDiscovery.fromSource("""
                public class Inline {
                    void check(String reference) {
                        if (!reference.matches("[a-z]{4}")) throw new IllegalArgumentException();
                    }
                }
                """);

        assertThat(found.get("reference").regex()).isEqualTo("[a-z]{4}");
    }

    @Test
    void aFieldWithNoDiscoveredConstraintKeepsTheCallersFallbackValue() {
        assertThat(FieldConstraintDiscovery.satisfyingValue(null, "value")).contains("value");
    }

    @Test
    void aSatisfiableConstraintYieldsAConformingValue() {
        Optional<String> value = FieldConstraintDiscovery.satisfyingValue(
                new FieldConstraintDiscovery.Constraint("\\d{13}", null), "value");

        assertThat(value).isPresent();
        assertThat(value.get()).matches("\\d{13}");
    }

    @Test
    void aLengthCeilingTrimsTheFallbackRatherThanAbstaining() {
        Optional<String> value = FieldConstraintDiscovery.satisfyingValue(
                new FieldConstraintDiscovery.Constraint(null, 3), "value");

        assertThat(value).contains("val");
    }

    @Test
    void aConstraintThatCannotBeMetAbstainsInsteadOfGuessing() {
        // The whole safety property: the caller must be able to tell "no value" from "some value".
        Optional<String> value = FieldConstraintDiscovery.satisfyingValue(
                new FieldConstraintDiscovery.Constraint("(alpha|beta)+", null), "value");

        assertThat(value).isEmpty();
    }

    @Test
    void aPatternAndALengthThatDisagreeAbstainRatherThanBreakThePattern() {
        // Trimming a pattern-derived value would break the pattern it was built to satisfy.
        Optional<String> value = FieldConstraintDiscovery.satisfyingValue(
                new FieldConstraintDiscovery.Constraint("\\d{13}", 5), "value");

        assertThat(value).isEmpty();
    }

    @Test
    void twoSourcesConstrainingOneNameAreMergedRatherThanOneWinning() {
        Map<String, FieldConstraintDiscovery.Constraint> found = FieldConstraintDiscovery.fromSource("""
                public class Both {
                    void check(String code) {
                        if (code.length() > 40) throw new IllegalArgumentException();
                        if (code.length() >= 10) throw new IllegalArgumentException();
                    }
                }
                """);

        // ">= 10" rejects 10 itself, so 9 is the greatest accepted, and the tighter bound governs.
        assertThat(found.get("code").maxLength()).isEqualTo(9);
    }

    @Test
    void aJavaSourceLiteralIsUnescapedToThePatternItDenotes() {
        assertThat(FieldConstraintDiscovery.unescapeJavaLiteral("\\\\d{13}")).isEqualTo("\\d{13}");
    }
}
