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

    // ── T4-DX: the condition tests the object it was handed, not a local it unpacked ──────

    /**
     * The shape run {@code 2026-09-22_05-11-32} actually wrote, verbatim. Until 2026-09-22 the
     * pattern CONSTANT was discovered here and neither condition bound to a field, so the map came
     * back empty, the body synthesizer fell back to {@code "value"}, and {@code POST /books} was
     * refused with 400 fourteen times — after which every probe needing a book 404'd on the one
     * that was never created. 46% of that run's criteria went unjudged on a 23/23 artifact.
     */
    @Test
    void aConstraintBindsThroughAnAccessorAndANamedBound() {
        Map<String, FieldConstraintDiscovery.Constraint> found = FieldConstraintDiscovery.fromSource("""
                public class BookService {
                    private static final int MAX_TITLE_LENGTH = 120;
                    private static final Pattern ISBN_PATTERN = Pattern.compile("\\\\d{13}");

                    public Book create(Book book) {
                        if (book.getTitle().length() > MAX_TITLE_LENGTH) {
                            throw new IllegalArgumentException("title must be at most 120 characters");
                        }
                        if (!ISBN_PATTERN.matcher(book.getIsbn()).matches()) {
                            throw new IllegalArgumentException("isbn must be a 13-digit number");
                        }
                        return bookRepository.save(book);
                    }
                }
                """);

        assertThat(found.get("isbn").regex()).isEqualTo("\\d{13}");
        assertThat(found.get("title").maxLength()).isEqualTo(120);
    }

    @Test
    void theInlineFormBindsThroughAnAccessorToo() {
        Map<String, FieldConstraintDiscovery.Constraint> found = FieldConstraintDiscovery.fromSource("""
                if (!member.getEmail().matches("[^@]+@[^@]+")) { throw new IllegalArgumentException("email"); }
                """);

        assertThat(found.get("email").regex()).isEqualTo("[^@]+@[^@]+");
    }

    /**
     * The abstention this class is built on. A constraint recorded against the wrong name shapes
     * the wrong field's value; a constraint not recorded merely leaves the previous behaviour.
     */
    @Test
    void aCallThatIsNotAnAccessorBindsToNothing() {
        Map<String, FieldConstraintDiscovery.Constraint> found = FieldConstraintDiscovery.fromSource("""
                private static final Pattern P = Pattern.compile("\\\\d{13}");
                if (!P.matcher(book.normalise()).matches()) { throw new IllegalArgumentException("x"); }
                if (holder.compute().length() > 5) { throw new IllegalArgumentException("y"); }
                """);

        assertThat(found).isEmpty();
    }

    @Test
    void aNamedBoundThisSourceDoesNotDeclareIsNotGuessedAt() {
        Map<String, FieldConstraintDiscovery.Constraint> found = FieldConstraintDiscovery.fromSource("""
                if (book.getTitle().length() > LIMIT_DEFINED_ELSEWHERE) { throw new IllegalArgumentException("t"); }
                """);

        assertThat(found).isEmpty();
    }

    @Test
    void theBareIdentifierFormStillBinds() {
        Map<String, FieldConstraintDiscovery.Constraint> found = FieldConstraintDiscovery.fromSource("""
                private static final Pattern ISBN = Pattern.compile("\\\\d{13}");
                if (!ISBN.matcher(isbn).matches()) { throw new IllegalArgumentException("isbn"); }
                if (title.length() > 120) { throw new IllegalArgumentException("title"); }
                """);

        assertThat(found.get("isbn").regex()).isEqualTo("\\d{13}");
        assertThat(found.get("title").maxLength()).isEqualTo(120);
    }

    @Test
    void anAccessorAndABareIdentifierForTheSameFieldAgree() {
        assertThat(FieldConstraintDiscovery.fieldNameOf("book.getIsbn()")).isEqualTo("isbn");
        assertThat(FieldConstraintDiscovery.fieldNameOf("getIsbn()")).isEqualTo("isbn");
        assertThat(FieldConstraintDiscovery.fieldNameOf("isbn")).isEqualTo("isbn");
        assertThat(FieldConstraintDiscovery.fieldNameOf("book.normalise()")).isNull();
    }

    @Test
    void aJavaSourceLiteralIsUnescapedToThePatternItDenotes() {
        assertThat(FieldConstraintDiscovery.unescapeJavaLiteral("\\\\d{13}")).isEqualTo("\\d{13}");
    }
}
