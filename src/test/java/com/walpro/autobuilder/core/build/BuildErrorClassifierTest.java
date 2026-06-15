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
