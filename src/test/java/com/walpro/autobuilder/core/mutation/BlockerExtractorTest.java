package com.walpro.autobuilder.core.mutation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BlockerExtractorTest {

    @Test
    void blankStderr_returnsEmpty() {
        assertThat(BlockerExtractor.extract(null)).isEmpty();
        assertThat(BlockerExtractor.extract("")).isEmpty();
        assertThat(BlockerExtractor.extract("   ")).isEmpty();
    }

    @Test
    void missingMethod_extractsMissingMemberBlocker() {
        String stderr = """
                [ERROR] /src/main/java/com/example/demo/LoanService.java:[42,18] cannot find symbol
                [ERROR]   symbol:   method setReturned(boolean)
                [ERROR]   location: class com.example.demo.Loan
                """;

        Optional<List<MutationBlocker>> result = BlockerExtractor.extract(stderr);

        assertThat(result).isPresent();
        assertThat(result.get()).containsExactly(
                MutationBlocker.missingMember("Loan", "setReturned", "boolean")
        );
    }

    @Test
    void missingConstructor_extractsMissingMemberBlocker() {
        String stderr = """
                [ERROR] /src/main/java/com/example/demo/Demo.java:[10,9] no suitable constructor found for Book(long,java.lang.String,java.lang.String)
                [ERROR]     constructor Book.Book(java.lang.String,java.lang.String) is not applicable
                """;

        Optional<List<MutationBlocker>> result = BlockerExtractor.extract(stderr);

        assertThat(result).isPresent();
        assertThat(result.get()).containsExactly(
                MutationBlocker.missingMember("Book", "Book", "long,java.lang.String,java.lang.String")
        );
    }

    @Test
    void bareMissingClass_extractsMissingTypeBlocker() {
        String stderr = """
                [ERROR] /src/main/java/com/example/demo/LoanService.java:[5,8] cannot find symbol
                [ERROR]   symbol:   class LoanNotFoundException
                """;

        Optional<List<MutationBlocker>> result = BlockerExtractor.extract(stderr);

        assertThat(result).isPresent();
        assertThat(result.get()).containsExactly(
                MutationBlocker.missingType("LoanNotFoundException")
        );
    }

    @Test
    void unexplainedDiagnostic_returnsEmpty() {
        // A real diagnostic the extractor doesn't recognise (e.g. a generic compilation
        // failure with no symbol info) should abort the extraction — re-staging cannot
        // resolve what we can't fingerprint.
        String stderr = """
                [ERROR] /src/main/java/com/example/demo/LoanService.java:[42,18] incompatible types: int cannot be converted to String
                """;

        assertThat(BlockerExtractor.extract(stderr)).isEmpty();
    }

    @Test
    void mixOfExplainedAndUnexplained_returnsEmpty() {
        // One missing-symbol diagnostic, one unrelated type error. The mix is unsafe —
        // re-staging would clear the symbol blocker but leave the type error standing.
        String stderr = """
                [ERROR] /A.java:[1,1] cannot find symbol
                [ERROR]   symbol:   method setReturned(boolean)
                [ERROR]   location: class Loan
                [ERROR] /B.java:[5,5] incompatible types: int cannot be converted to String
                """;

        assertThat(BlockerExtractor.extract(stderr)).isEmpty();
    }

    @Test
    void mavenBanners_ignored() {
        // The framing lines around real diagnostics shouldn't count as unexplained.
        String stderr = """
                [INFO] BUILD FAILURE
                [ERROR] COMPILATION ERROR :
                [ERROR] /src/main/java/com/example/demo/LoanService.java:[42,18] cannot find symbol
                [ERROR]   symbol:   method setReturned(boolean)
                [ERROR]   location: class Loan
                [ERROR] -> [Help 1]
                [ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
                """;

        Optional<List<MutationBlocker>> result = BlockerExtractor.extract(stderr);

        assertThat(result).isPresent();
        assertThat(result.get()).containsExactly(
                MutationBlocker.missingMember("Loan", "setReturned", "boolean")
        );
    }

    @Test
    void duplicateDiagnostics_deduped() {
        // Two locations reporting the same missing-method shape → one blocker.
        String stderr = """
                [ERROR] /A.java:[10,1] cannot find symbol
                [ERROR]   symbol:   method setReturned(boolean)
                [ERROR]   location: class Loan
                [ERROR] /B.java:[20,1] cannot find symbol
                [ERROR]   symbol:   method setReturned(boolean)
                [ERROR]   location: class Loan
                """;

        Optional<List<MutationBlocker>> result = BlockerExtractor.extract(stderr);

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
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
}
