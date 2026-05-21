package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2-DN: extractor parses all distinct missing symbols from Maven compile stderr.
 */
class CompileErrorSymbolExtractorTest {

    @Test
    void extract_returnsEmpty_whenStderrBlank() {
        assertThat(CompileErrorSymbolExtractor.extract(null)).isEmpty();
        assertThat(CompileErrorSymbolExtractor.extract("")).isEmpty();
        assertThat(CompileErrorSymbolExtractor.extract("   \n")).isEmpty();
    }

    @Test
    void extract_findsAllDistinctMissingSymbols_inOrderOfFirstAppearance() {
        // Mirrors the 22:39 d73064 stderr shape (multiple classes named across separate errors).
        String stderr = """
                [INFO] Scanning for projects...
                [ERROR] /src/LoanService.java:[12,5] cannot find symbol
                  symbol:   class BookRepository
                  location: class LoanService
                [ERROR] /src/LoanService.java:[14,5] cannot find symbol
                  symbol:   class MemberRepository
                [ERROR] /src/LoanService.java:[20,9] cannot find symbol
                  symbol:   class BookNotFoundException
                [ERROR] /src/LoanService.java:[24,9] cannot find symbol
                  symbol:   class BookAlreadyLoanedException
                [ERROR] /src/LoanService.java:[12,5] cannot find symbol
                  symbol:   class BookRepository
                """;

        Set<String> out = CompileErrorSymbolExtractor.extract(stderr);

        assertThat(out)
                .containsExactly("BookRepository", "MemberRepository", "BookNotFoundException", "BookAlreadyLoanedException");
    }

    @Test
    void extract_picksUpPackageDoesNotExistTokens() {
        String stderr = """
                [ERROR] /src/MemberService.java:[3,32] package jakarta.persistence does not exist
                [ERROR] /src/MemberService.java:[4,32] package jakarta.validation does not exist
                """;

        Set<String> out = CompileErrorSymbolExtractor.extract(stderr);

        assertThat(out).containsExactly("jakarta.persistence", "jakarta.validation");
    }

    @Test
    void extract_handlesAnsiEscapesAndMethodAndVariableKinds() {
        // ANSI prefix that Maven sometimes emits; both `method` and `variable` kinds.
        String stderr = "[31m[ERROR][0m cannot find symbol\n"
                + "  symbol: method findByEmail\n"
                + "[ERROR] cannot find symbol\n"
                + "  symbol: variable bookRepository\n";

        Set<String> out = CompileErrorSymbolExtractor.extract(stderr);

        assertThat(out).containsExactlyInAnyOrder("findByEmail", "bookRepository");
    }
}
