package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuildErrorClassifierTest {

    // ── MISSING_POM ──────────────────────────────────────────────────────────

    @Test
    void classify_missingPom_capturedFixture() {
        // Realistic Maven stderr from the failing 2026-05-04 run.
        String stderr = """
                [INFO] Scanning for projects...
                [INFO]
                [INFO] ------------------------------------------------------------------------
                [ERROR]
                [ERROR] The goal you specified requires a project to execute but there is no POM in this directory (/data/firestarter). Please verify you invoked Maven from the correct directory.
                [ERROR] -> [Help 1]
                [ERROR]
                """;
        Classification c = BuildErrorClassifier.classify(stderr);

        assertThat(c.kind()).isEqualTo(Kind.MISSING_POM);
        String criteria = c.criteria().toString();
        assertThat(criteria).contains("pom.xml").contains("project root");
    }

    @Test
    void classify_missingPom_alternatePhrasing() {
        // Maven can also say "could not find a POM" in some versions.
        String stderr = "[ERROR] could not find a POM in /build/project\n";
        Classification c = BuildErrorClassifier.classify(stderr);

        assertThat(c.kind()).isEqualTo(Kind.MISSING_POM);
        assertThat(c.criteria().toString()).contains("pom.xml");
    }

    @Test
    void classify_missingPom_ansiColoured() {
        // Stripping is genuinely required: the ESC code interrupts the matched phrase so the
        // pattern "no POM in this directory" only matches after stripping.
        String stderr = "\u001B[1;31mno POM in this\u001B[m directory (/app)";
        Classification c = BuildErrorClassifier.classify(stderr);

        assertThat(c.kind()).isEqualTo(Kind.MISSING_POM);
    }

    // ── CANNOT_FIND_SYMBOL ───────────────────────────────────────────────────

    @Test
    void classify_cannotFindSymbol_withLeadingInfoLines() {
        // Real Maven output has [INFO] lines before the [ERROR] block.
        String stderr = """
                [INFO] Compiling 5 source files
                [ERROR] /src/main/java/Foo.java:[12,15] cannot find symbol
                [ERROR]   symbol:   class UserRepository
                [ERROR]   location: class com.example.Foo
                """;
        Classification c = BuildErrorClassifier.classify(stderr);

        assertThat(c.kind()).isEqualTo(Kind.CANNOT_FIND_SYMBOL);
        assertThat(c.criteria().toString()).contains("UserRepository");
    }

    @Test
    void classify_cannotFindSymbol_withTypeParameters() {
        // Javac sometimes reports generic types: "symbol: class List<String>".
        // \S+ captures angle-bracket notation verbatim — the LLM interprets it correctly.
        String stderr = "[ERROR]   symbol:   class List<String>\n[ERROR]   location: class com.example.Foo\n";
        Classification c = BuildErrorClassifier.classify(stderr);

        assertThat(c.kind()).isEqualTo(Kind.CANNOT_FIND_SYMBOL);
        assertThat(c.criteria().toString()).contains("List<String>");
    }

    // ── PACKAGE_DOES_NOT_EXIST ───────────────────────────────────────────────

    @Test
    void classify_packageDoesNotExist_extractsPackageName() {
        String stderr = "[ERROR] /src/main/java/Bar.java:[3,14] package org.springframework.data does not exist\n";
        Classification c = BuildErrorClassifier.classify(stderr);

        assertThat(c.kind()).isEqualTo(Kind.PACKAGE_DOES_NOT_EXIST);
        assertThat(c.criteria().toString()).contains("org.springframework.data");
    }

    @Test
    void classify_packageAndCascadeSymbol_reportsThePackage_notTheSymptom() {
        // Verbatim from run 2026-08-24_00-07-06, which halted on a convergence stall after 20
        // cycles editing this file. An unresolvable import emits BOTH markers: the package on the
        // import line, the symbol on every use of a type from it. Returning the symbol sends the
        // loop to create or import JpaRepository — neither of which can work, because the package
        // is not on the classpath at all. The package is the cause and routes to CONFIG_FIXABLE.
        String stderr = """
                [ERROR] /src/main/java/com/example/library/domain/repository/LoanRepository.java:[3,47] package org.springframework.data.jpa.repository does not exist
                [ERROR] /src/main/java/com/example/library/domain/repository/LoanRepository.java:[5,41] cannot find symbol
                [ERROR]   symbol: class JpaRepository
                """;
        Classification c = BuildErrorClassifier.classify(stderr);

        assertThat(c.kind()).isEqualTo(Kind.PACKAGE_DOES_NOT_EXIST);
        assertThat(c.criteria().toString()).contains("org.springframework.data.jpa.repository");
        assertThat(c.criteria().toString()).doesNotContain("JpaRepository is defined");
    }

    @Test
    void classify_packageDoesNotExist_routesToTheClasspathCategory() {
        // The reorder only matters because the two kinds route differently.
        assertThat(BuildErrorClassifier.defaultCategoryFor(Kind.PACKAGE_DOES_NOT_EXIST))
                .isEqualTo(BuildErrorClassifier.Category.CONFIG_FIXABLE);
        assertThat(BuildErrorClassifier.defaultCategoryFor(Kind.CANNOT_FIND_SYMBOL))
                .isEqualTo(BuildErrorClassifier.Category.SOURCE_FIXABLE);
    }

    @Test
    void classify_symbolOnly_isUnaffectedByTheReorder() {
        // Stderr with no package marker must still classify as a symbol problem.
        String stderr = """
                [ERROR] /src/main/java/Foo.java:[12,15] cannot find symbol
                [ERROR]   symbol:   class BookService
                """;
        Classification c = BuildErrorClassifier.classify(stderr);

        assertThat(c.kind()).isEqualTo(Kind.CANNOT_FIND_SYMBOL);
        assertThat(c.criteria().toString()).contains("BookService");
    }

    // ── UNKNOWN ──────────────────────────────────────────────────────────────

    @Test
    void classify_nullStderr_returnsUnknown() {
        Classification c = BuildErrorClassifier.classify(null);

        assertThat(c.kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(c.criteria().toString())
                .contains("Project compiles without errors")
                .contains("Compile error reported in the previous build is resolved");
    }

    @Test
    void classify_emptyStderr_returnsUnknown() {
        Classification c = BuildErrorClassifier.classify("   ");

        assertThat(c.kind()).isEqualTo(Kind.UNKNOWN);
    }

    @Test
    void classify_unrecognisedStderr_returnsUnknownWithGenericCriteria() {
        String stderr = "[ERROR] some totally unrecognised Maven failure nobody predicted\n";
        Classification c = BuildErrorClassifier.classify(stderr);

        assertThat(c.kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(c.criteria().toString())
                .contains("Project compiles without errors")
                .contains("Compile error reported in the previous build is resolved");
    }

    @Test
    void classify_firstMatchWins_missingPomBeforeCannotFindSymbol() {
        // When both patterns appear in the same stderr, the first match (MISSING_POM) wins.
        String stderr = "[ERROR] no POM in this directory\n[ERROR]   symbol:   class Foo\n";
        Classification c = BuildErrorClassifier.classify(stderr);

        assertThat(c.kind()).isEqualTo(Kind.MISSING_POM);
    }

}
