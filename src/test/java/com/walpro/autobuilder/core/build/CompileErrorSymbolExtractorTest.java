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

    @Test
    void extract_preservesMethodSignatureParensForSingleParam() {
        // Regression test for run 2026-05-24_17-16-49: a single-param method signature
        // (typed param + null-literal param) was being mangled into four garbled tokens
        // by trimTrailingPunctuation stripping the closing paren. The downstream coverage
        // guard then fed the EvolutionAgent garbage like `setLoanDate(java.time.LocalDateTime`.
        String stderr = """
                [ERROR] /src/LoanService.java:[54,13] cannot find symbol
                  symbol:   method setLoanDate(java.time.LocalDateTime)
                  location: variable book of type com.example.demo.model.Book
                [ERROR] /src/LoanService.java:[55,13] cannot find symbol
                  symbol:   method setMemberId(java.lang.Long)
                [ERROR] /src/LoanService.java:[73,17] cannot find symbol
                  symbol:   method setLoanDate(<nulltype>)
                [ERROR] /src/LoanService.java:[74,17] cannot find symbol
                  symbol:   method setMemberId(<nulltype>)
                """;

        Set<String> out = CompileErrorSymbolExtractor.extract(stderr);

        assertThat(out).containsExactly(
                "setLoanDate(java.time.LocalDateTime)",
                "setMemberId(java.lang.Long)",
                "setLoanDate(<nulltype>)",
                "setMemberId(<nulltype>)"
        );
    }

    @Test
    void extract_preservesMethodSignatureWithMultipleSpaceSeparatedParams() {
        // Forward-looking: javac normally emits commas without spaces, but historical
        // bug reports + IDE-piped builds show "(int, String)" shapes too. The paren-
        // balanced capture survives the internal whitespace that the old \\S+ form
        // would have truncated at.
        String stderr = """
                [ERROR] /src/Demo.java:[10,9] cannot find symbol
                  symbol:   method foo(int, java.lang.String)
                """;

        Set<String> out = CompileErrorSymbolExtractor.extract(stderr);

        assertThat(out).containsExactly("foo(int, java.lang.String)");
    }

    @Test
    void extract_stripsTrailingCommaAndSemicolonButNeverParens() {
        // Punctuation guard: comma / semicolon trail still strips (javac sometimes emits
        // a dangling separator on continuation lines); closing parens never strip because
        // they're structural to method signatures.
        String stderr = """
                [ERROR] cannot find symbol
                  symbol:   class FooException;
                [ERROR] cannot find symbol
                  symbol:   variable count,
                [ERROR] cannot find symbol
                  symbol:   method bar()
                """;

        Set<String> out = CompileErrorSymbolExtractor.extract(stderr);

        assertThat(out).containsExactly("FooException", "count", "bar()");
    }
}
