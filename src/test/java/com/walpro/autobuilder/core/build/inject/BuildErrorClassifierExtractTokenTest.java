package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.build.BuildErrorClassifier;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BuildErrorClassifierExtractTokenTest {

    @Test
    void extractToken_packageDoesNotExist_returnsPackageName() {
        String stderr = "[ERROR] /src/Book.java:[3,1] package jakarta.persistence does not exist";
        Optional<String> token = BuildErrorClassifier.extractToken(stderr, Kind.PACKAGE_DOES_NOT_EXIST);
        assertThat(token).contains("jakarta.persistence");
    }

    @Test
    void extractToken_cannotFindSymbol_returnsSymbolName() {
        String stderr = "  symbol: class BookRepository\n  location: class com.example.demo.LoanService";
        Optional<String> token = BuildErrorClassifier.extractToken(stderr, Kind.CANNOT_FIND_SYMBOL);
        assertThat(token).contains("BookRepository");
    }

    @Test
    void extractToken_missingPom_returnsEmpty() {
        String stderr = "[ERROR] there is no POM in this directory";
        Optional<String> token = BuildErrorClassifier.extractToken(stderr, Kind.MISSING_POM);
        assertThat(token).isEmpty();
    }

    @Test
    void extractToken_unknownKind_returnsEmpty() {
        String stderr = "anything";
        Optional<String> token = BuildErrorClassifier.extractToken(stderr, Kind.UNKNOWN);
        assertThat(token).isEmpty();
    }

    @Test
    void extractToken_handlesAnsiEscapes() {
        String stderr = "[1;31m[ERROR][m package [1morg.springframework.data.repository[m does not exist";
        Optional<String> token = BuildErrorClassifier.extractToken(stderr, Kind.PACKAGE_DOES_NOT_EXIST);
        assertThat(token).contains("org.springframework.data.repository");
    }

    @Test
    void extractToken_blankInput_returnsEmpty() {
        assertThat(BuildErrorClassifier.extractToken("", Kind.PACKAGE_DOES_NOT_EXIST)).isEmpty();
        assertThat(BuildErrorClassifier.extractToken(null, Kind.PACKAGE_DOES_NOT_EXIST)).isEmpty();
    }
}
