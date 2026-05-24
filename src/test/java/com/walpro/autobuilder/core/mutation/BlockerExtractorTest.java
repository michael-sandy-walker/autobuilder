package com.walpro.autobuilder.core.mutation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-BE / T4-BF: extractor unit tests. The first six tests cover the ticket's
 * extractor-side cases (banner extension × 2, co-mingled, pure-author, unknown shape,
 * clean deferrable); the remainder are existing T4-BE invariants preserved as
 * backward-compatibility coverage.
 */
class BlockerExtractorTest {

    // -----------------------------------------------------------------------------------
    // T4-BF — ticket tests 1-6
    // -----------------------------------------------------------------------------------

    /** Test 1 — Banner extension: Maven footer ([Help 1] http://...) no longer aborts. */
    @Test
    void mavenHelpFooter_doesNotAbortExtraction() {
        String stderr = """
                [ERROR] /src/main/java/com/example/demo/LoanService.java:[47,13] cannot find symbol
                [ERROR]   symbol:   method setReturned(boolean)
                [ERROR]   location: class com.example.demo.Loan
                [ERROR] -> [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
                """;

        BlockerExtractor.Outcome outcome = BlockerExtractor.extract(stderr);

        assertThat(outcome.result()).isEqualTo(BlockerExtractor.Outcome.Result.DEFERRED);
        assertThat(outcome.blockers()).containsExactly(
                MutationBlocker.missingMember("Loan", "setReturned", "boolean")
        );
    }

    /** Test 2 — Banner extension: "For more information" / "please read" footers. */
    @Test
    void forMoreInformationFooter_doesNotAbortExtraction() {
        String stderr = """
                [ERROR] /src/A.java:[10,1] cannot find symbol
                [ERROR]   symbol:   class LoanNotFoundException
                [ERROR]\s
                [ERROR] For more information about the errors and possible solutions, please read the following articles:
                [ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
                """;

        BlockerExtractor.Outcome outcome = BlockerExtractor.extract(stderr);

        assertThat(outcome.result()).isEqualTo(BlockerExtractor.Outcome.Result.DEFERRED);
        assertThat(outcome.blockers()).containsExactly(
                MutationBlocker.missingType("LoanNotFoundException")
        );
    }

    /** Test 3 — Co-mingled deferrable + author-error → REFUSED_AUTHOR_ERROR with both
     *  surfaces populated. */
    @Test
    void coMingled_authorErrorAndDeferrable_refusesAndPreservesBoth() {
        String stderr = """
                [ERROR] /A.java:[10,1] cannot find symbol
                [ERROR]   symbol:   method setReturned(boolean)
                [ERROR]   location: class Loan
                [ERROR] /B.java:[20,5] non-static method getBookById(Long) cannot be referenced from a static context
                """;

        BlockerExtractor.Outcome outcome = BlockerExtractor.extract(stderr);

        assertThat(outcome.result()).isEqualTo(BlockerExtractor.Outcome.Result.REFUSED_AUTHOR_ERROR);
        assertThat(outcome.blockers()).containsExactly(
                MutationBlocker.missingMember("Loan", "setReturned", "boolean")
        );
        assertThat(outcome.authorErrors()).hasSize(1);
        assertThat(outcome.authorErrors().get(0).file()).isEqualTo("/B.java");
        assertThat(outcome.authorErrors().get(0).line()).isEqualTo(20);
        assertThat(outcome.authorErrors().get(0).column()).isEqualTo(5);
        assertThat(outcome.authorErrors().get(0).message())
                .contains("non-static method getBookById(Long) cannot be referenced from a static context");
    }

    /** Test 4 — Pure author-error. */
    @Test
    void pureAuthorError_refusesWithEmptyBlockers() {
        String stderr = """
                [ERROR] /X.java:[42,18] incompatible types: int cannot be converted to String
                """;

        BlockerExtractor.Outcome outcome = BlockerExtractor.extract(stderr);

        assertThat(outcome.result()).isEqualTo(BlockerExtractor.Outcome.Result.REFUSED_AUTHOR_ERROR);
        assertThat(outcome.blockers()).isEmpty();
        assertThat(outcome.authorErrors()).hasSize(1);
        assertThat(outcome.authorErrors().get(0).message()).contains("incompatible types: int cannot be converted to String");
    }

    /** Test 5 — Unknown shape mixed with a clean deferrable → REFUSED_UNKNOWN, capture
     *  the first unmatched line. */
    @Test
    void unknownShape_refusesAndCapturesFirstLine() {
        String stderr = """
                [ERROR] /A.java:[10,1] cannot find symbol
                [ERROR]   symbol:   class LoanNotFoundException
                [ERROR] /B.java:[20,5] this is some entirely novel diagnostic shape the classifier doesn't know
                """;

        BlockerExtractor.Outcome outcome = BlockerExtractor.extract(stderr);

        assertThat(outcome.result()).isEqualTo(BlockerExtractor.Outcome.Result.REFUSED_UNKNOWN);
        assertThat(outcome.firstUnknownLine())
                .contains("this is some entirely novel diagnostic shape the classifier doesn't know");
        assertThat(outcome.unknownCount()).isEqualTo(1);
    }

    /** Test 6 — Clean deferrable: only `cannot find symbol` + `location:` lines + banners. */
    @Test
    void cleanDeferrable_returnsDeferredWithOneBlocker() {
        String stderr = """
                [INFO] BUILD FAILURE
                [ERROR] COMPILATION ERROR :
                [ERROR] /src/main/java/com/example/demo/LoanService.java:[42,18] cannot find symbol
                [ERROR]   symbol:   method setReturned(boolean)
                [ERROR]   location: class Loan
                [ERROR] -> [Help 1]
                [ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
                """;

        BlockerExtractor.Outcome outcome = BlockerExtractor.extract(stderr);

        assertThat(outcome.result()).isEqualTo(BlockerExtractor.Outcome.Result.DEFERRED);
        assertThat(outcome.blockers()).containsExactly(
                MutationBlocker.missingMember("Loan", "setReturned", "boolean")
        );
        assertThat(outcome.authorErrors()).isEmpty();
        assertThat(outcome.firstUnknownLine()).isNull();
    }

    // -----------------------------------------------------------------------------------
    // Test 10 — Backward compatibility: existing T4-BE invariants preserved
    // -----------------------------------------------------------------------------------

    @Test
    void blankStderr_returnsNoDeferrable() {
        assertThat(BlockerExtractor.extract(null).result()).isEqualTo(BlockerExtractor.Outcome.Result.NO_DEFERRABLE);
        assertThat(BlockerExtractor.extract("").result()).isEqualTo(BlockerExtractor.Outcome.Result.NO_DEFERRABLE);
        assertThat(BlockerExtractor.extract("   ").result()).isEqualTo(BlockerExtractor.Outcome.Result.NO_DEFERRABLE);
    }

    @Test
    void missingConstructor_extractsMissingMemberBlocker() {
        String stderr = """
                [ERROR] /src/main/java/com/example/demo/Demo.java:[10,9] no suitable constructor found for Book(long,java.lang.String,java.lang.String)
                [ERROR]     constructor Book.Book(java.lang.String,java.lang.String) is not applicable
                """;

        BlockerExtractor.Outcome outcome = BlockerExtractor.extract(stderr);

        assertThat(outcome.result()).isEqualTo(BlockerExtractor.Outcome.Result.DEFERRED);
        assertThat(outcome.blockers()).containsExactly(
                MutationBlocker.missingMember("Book", "Book", "long,java.lang.String,java.lang.String")
        );
    }

    @Test
    void bareMissingClass_extractsMissingTypeBlocker() {
        String stderr = """
                [ERROR] /src/main/java/com/example/demo/LoanService.java:[5,8] cannot find symbol
                [ERROR]   symbol:   class LoanNotFoundException
                """;

        BlockerExtractor.Outcome outcome = BlockerExtractor.extract(stderr);

        assertThat(outcome.result()).isEqualTo(BlockerExtractor.Outcome.Result.DEFERRED);
        assertThat(outcome.blockers()).containsExactly(
                MutationBlocker.missingType("LoanNotFoundException")
        );
    }

    @Test
    void duplicateDiagnostics_deduped() {
        String stderr = """
                [ERROR] /A.java:[10,1] cannot find symbol
                [ERROR]   symbol:   method setReturned(boolean)
                [ERROR]   location: class Loan
                [ERROR] /B.java:[20,1] cannot find symbol
                [ERROR]   symbol:   method setReturned(boolean)
                [ERROR]   location: class Loan
                """;

        BlockerExtractor.Outcome outcome = BlockerExtractor.extract(stderr);

        assertThat(outcome.result()).isEqualTo(BlockerExtractor.Outcome.Result.DEFERRED);
        assertThat(outcome.blockers()).hasSize(1);
    }

    @Test
    void fingerprintIsStableAndUnique() {
        MutationBlocker a = MutationBlocker.missingMember("Loan", "setReturned", "boolean");
        MutationBlocker b = MutationBlocker.missingMember("Loan", "setReturned", "boolean");
        MutationBlocker c = MutationBlocker.missingMember("Loan", "setReturned", "int");
        MutationBlocker d = MutationBlocker.missingType("Loan");

        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
        assertThat(a.fingerprint()).isNotEqualTo(c.fingerprint());
        assertThat(a.fingerprint()).isNotEqualTo(d.fingerprint());
        assertThat(d.fingerprint()).isEqualTo("MISSING_TYPE|Loan|Loan|");
    }

    // -----------------------------------------------------------------------------------
    // errorShapes normalisation (supporting evidence for the behaviour-event payload)
    // -----------------------------------------------------------------------------------

    /** Test 8 (part 1) — three different `incompatible types` lines collapse to one shape. */
    @Test
    void errorShapes_incompatibleTypesNormalisedToSameShape() {
        String stderr = """
                [ERROR] /A.java:[1,1] incompatible types: int cannot be converted to String
                [ERROR] /B.java:[2,2] incompatible types: long cannot be converted to Integer
                [ERROR] /C.java:[3,3] incompatible types: double cannot be converted to Float
                """;

        BlockerExtractor.Outcome outcome = BlockerExtractor.extract(stderr);

        assertThat(outcome.errorShapes())
                .containsExactly("incompatible types: ... cannot be converted to ...");
    }

    @Test
    void errorShapes_distinctShapesPreserved() {
        String stderr = """
                [ERROR] /A.java:[1,1] cannot find symbol
                [ERROR]   symbol:   method setReturned(boolean)
                [ERROR]   location: class Loan
                [ERROR] /B.java:[2,2] non-static method getBookById(Long) cannot be referenced from a static context
                """;

        BlockerExtractor.Outcome outcome = BlockerExtractor.extract(stderr);

        assertThat(outcome.errorShapes()).contains(
                "cannot find symbol",
                "non-static method ... cannot be referenced from a static context"
        );
    }
}
