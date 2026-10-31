package org.shelfkit;

import org.junit.jupiter.api.Test;
import org.shelfkit.catalogue.CatalogueEntry;
import org.shelfkit.catalogue.CatalogueException;
import org.shelfkit.catalogue.Isbn;
import org.shelfkit.lending.LendingPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShelfkitTest {

    private static final Isbn DUNE = new Isbn("9780441013593");

    @Test
    void anIsbnWithItsCheckDigitIsAccepted() {
        assertThat(new Isbn("1234567890128")).hasToString("1234567890128");
        assertThat(new Isbn("9780000000002").value()).isEqualTo("9780000000002");
    }

    @Test
    void aWrongCheckDigitIsRefused() {
        assertThatThrownBy(() -> new Isbn("1234567890123")).isInstanceOf(CatalogueException.class)
                .hasMessageContaining("check digit");
    }

    @Test
    void anythingButThirteenDigitsIsRefused() {
        for (String s : new String[]{null, "", "12345", "123456789012X", "12345678901280"}) {
            assertThatThrownBy(() -> new Isbn(s)).isInstanceOf(CatalogueException.class);
        }
    }

    @Test
    void aTitleOfTwoHundredCharactersIsAccepted_oneMoreIsNot() {
        assertThat(new CatalogueEntry(DUNE, "x".repeat(200)).title()).hasSize(200);
        assertThatThrownBy(() -> new CatalogueEntry(DUNE, "x".repeat(201))).isInstanceOf(CatalogueException.class);
    }

    @Test
    void anEntryNeedsATitleAndAnIsbn() {
        assertThatThrownBy(() -> new CatalogueEntry(DUNE, " ")).isInstanceOf(CatalogueException.class);
        assertThatThrownBy(() -> new CatalogueEntry(null, "Dune")).isInstanceOf(CatalogueException.class);
    }

    @Test
    void aFreeTitleGoesOutUpToTheLimit() {
        LendingPolicy policy = LendingPolicy.standard();
        assertThat(policy.decide(false, 2)).isEqualTo(LendingPolicy.Verdict.ALLOWED);
        assertThat(policy.decide(false, 3)).isEqualTo(LendingPolicy.Verdict.LIMIT_REACHED);
    }

    @Test
    void aTitleOnLoanIsRefusedBeforeTheLimitIsAsked() {
        assertThat(LendingPolicy.standard().decide(true, 3)).isEqualTo(LendingPolicy.Verdict.ALREADY_ON_LOAN);
    }
}
