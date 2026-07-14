package com.walpro.autobuilder.core.mutation;

import com.walpro.autobuilder.core.build.inject.WellKnownPackageRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

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
    // errorShapes normalisation (supporting observation for the behaviour-event payload)
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

    // -----------------------------------------------------------------------------------
    // T1-ET — PACKAGE_DOES_NOT_EXIST classifier gap
    // -----------------------------------------------------------------------------------

    /** Regression-lock from `behaviour-2026-05-25_01-10-21.ndjson` — the verbatim 4-line
     *  cascade that previously surfaced as REFUSED_UNKNOWN with unk=4. With T1-ET Part A the
     *  same stderr must produce DEFERRED with one MissingPackage blocker per distinct
     *  package (the two `cannot find symbol` lines collapse into the missing-type blocker for
     *  the same `class ...` location continuation). */
    @Test
    void packageDoesNotExist_regressionLockFrom20260525_0110_run() {
        String stderr = """
                [ERROR] /src/main/java/com/example/demo/controller/MemberController.java:[14,24] package javax.validation does not exist
                [ERROR] /src/main/java/com/example/demo/controller/MemberController.java:[15,36] package javax.validation.constraints does not exist
                [ERROR] /src/main/java/com/example/demo/controller/MemberController.java:[40,17] cannot find symbol
                [ERROR]   symbol:   class Valid
                """;

        BlockerExtractor.Outcome outcome = BlockerExtractor.extract(stderr);

        assertThat(outcome.result()).isEqualTo(BlockerExtractor.Outcome.Result.DEFERRED);
        assertThat(outcome.blockers()).contains(
                MutationBlocker.missingPackage("javax.validation"),
                MutationBlocker.missingPackage("javax.validation.constraints"),
                MutationBlocker.missingType("Valid")
        );
        assertThat(outcome.firstUnknownLine()).isNull();
        assertThat(outcome.unknownCount()).isZero();
        // T1-ET shape normalisation: the two `package … does not exist` lines collapse to
        // one shape so aggregation in the behaviour log is informative.
        assertThat(outcome.errorShapes()).contains(
                "package ... does not exist",
                "cannot find symbol"
        );
    }

    /** Pure package-does-not-exist with no co-mingled diagnostics — DEFERRED, one blocker. */
    @Test
    void packageDoesNotExist_pure_deferredWithOnePackageBlocker() {
        String stderr = """
                [ERROR] /src/main/java/com/example/A.java:[3,18] package com.acme.api does not exist
                """;

        BlockerExtractor.Outcome outcome = BlockerExtractor.extract(stderr);

        assertThat(outcome.result()).isEqualTo(BlockerExtractor.Outcome.Result.DEFERRED);
        assertThat(outcome.blockers()).containsExactly(
                MutationBlocker.missingPackage("com.acme.api")
        );
    }

    /** Duplicate `package X.Y does not exist` lines for the same package collapse to one
     *  blocker (dedup mirrors the existing `cannot find symbol` dedup behaviour). */
    @Test
    void packageDoesNotExist_duplicatePackages_deduped() {
        String stderr = """
                [ERROR] /A.java:[1,1] package com.acme.api does not exist
                [ERROR] /B.java:[2,2] package com.acme.api does not exist
                """;

        BlockerExtractor.Outcome outcome = BlockerExtractor.extract(stderr);

        assertThat(outcome.blockers()).containsExactly(
                MutationBlocker.missingPackage("com.acme.api")
        );
    }

    /** Registry-aware overload: when the registry has a replacement, the blocker is upgraded
     *  to PACKAGE_REWRITE carrying the modern prefix + GAV coordinates. */
    @Test
    void packageDoesNotExist_registryReplacement_emitsPackageRewriteBlocker() {
        WellKnownPackageRegistry registry = Mockito.mock(WellKnownPackageRegistry.class);
        Mockito.when(registry.replacedBy("javax.validation", "<pom/>"))
                .thenReturn(Optional.of(new WellKnownPackageRegistry.PackageReplacement(
                        "javax.validation", "jakarta.validation")));
        Mockito.when(registry.lookup("javax.validation", "<pom/>"))
                .thenReturn(Optional.of(new WellKnownPackageRegistry.Gav(
                        "jakarta.validation", "jakarta.validation-api", "3.0.2")));

        String stderr = "[ERROR] /A.java:[1,1] package javax.validation does not exist";

        BlockerExtractor.Outcome outcome = BlockerExtractor.extract(stderr, registry, "<pom/>");

        assertThat(outcome.result()).isEqualTo(BlockerExtractor.Outcome.Result.DEFERRED);
        assertThat(outcome.blockers()).containsExactly(
                MutationBlocker.packageRewrite(
                        "javax.validation",
                        "jakarta.validation",
                        "jakarta.validation:jakarta.validation-api:3.0.2")
        );
    }

    /** Registry overload with no replacement available falls back to MISSING_PACKAGE. */
    @Test
    void packageDoesNotExist_registryNoReplacement_fallsBackToMissingPackage() {
        WellKnownPackageRegistry registry = Mockito.mock(WellKnownPackageRegistry.class);
        Mockito.when(registry.replacedBy(Mockito.anyString(), Mockito.any()))
                .thenReturn(Optional.empty());

        String stderr = "[ERROR] /A.java:[1,1] package com.acme.unknown does not exist";

        BlockerExtractor.Outcome outcome = BlockerExtractor.extract(stderr, registry, "<pom/>");

        assertThat(outcome.blockers()).containsExactly(
                MutationBlocker.missingPackage("com.acme.unknown")
        );
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

    @Test
    void locationPackageLine_isDeferrable_notUnknown() {
        // 2026-06-10 14:04 run: `location: package com.example.demo` (package-qualified reference
        // to a missing type) was the only unclassified shape — 15 REFUSED_UNKNOWN outcomes, each
        // vetoing deferral of 5-9 legitimate blockers from the same stderr.
        String stderr = """
                [ERROR] /out/src/main/java/com/example/demo/loan/LoanService.java:[12,34] cannot find symbol
                [ERROR]   symbol:   class BookService
                [ERROR]   location: package com.example.demo
                """;

        BlockerExtractor.Outcome outcome = BlockerExtractor.extract(stderr);

        assertThat(outcome.result()).isEqualTo(BlockerExtractor.Outcome.Result.DEFERRED);
        assertThat(outcome.unknownCount()).isZero();
        assertThat(outcome.blockers()).anySatisfy(b -> assertThat(b.symbol()).isEqualTo("BookService"));
    }
}
