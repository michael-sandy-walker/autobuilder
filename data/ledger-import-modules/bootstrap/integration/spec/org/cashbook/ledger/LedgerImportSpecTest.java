package org.cashbook.ledger;

import org.junit.jupiter.api.Test;
import org.linekit.tokens.LineTokenizer;
import org.linekit.tokens.TokenizeException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** The integration: the ledger imports name=value lines read by linetokens, and says which lines it rejected and why. */
class LedgerImportSpecTest {

    @Test
    void importsEachLineAsAnEntryInOrder() {
        Ledger ledger = new Ledger();
        ImportReport report = LedgerImport.importLines(ledger, List.of(
                "account=cash; amount=12.50; memo=coffee",
                "account=bank; amount=-3"));
        assertThat(report.imported()).isEqualTo(2);
        assertThat(report.rejected()).isEmpty();
        assertThat(ledger.entries()).containsExactly(new Entry("cash", 1250, "coffee"), new Entry("bank", -300, ""));
    }

    @Test
    void amountsAreDecimalsWithAtMostTwoPlaces() {
        Ledger ledger = new Ledger();
        LedgerImport.importLines(ledger, List.of("account=a; amount=0.5", "account=a; amount=7",
                "account=a; amount=-0.05", "account=a; amount=10.00"));
        assertThat(ledger.entries()).extracting(Entry::amountCents).containsExactly(50L, 700L, -5L, 1000L);
    }

    @Test
    void aQuotedMemoKeepsItsSeparators() {
        Ledger ledger = new Ledger();
        LedgerImport.importLines(ledger, List.of("account=cash; amount=4; memo=\"coffee; large\""));
        assertThat(ledger.entries()).containsExactly(new Entry("cash", 400, "coffee; large"));
    }

    @Test
    void blankLinesAreSkippedAndLinesAreNumberedFromOne() {
        Ledger ledger = new Ledger();
        ImportReport report = LedgerImport.importLines(ledger, List.of("account=a; amount=1", "   ", "oops"));
        assertThat(report.imported()).isEqualTo(1);
        assertThat(report.rejected()).extracting(Rejection::line).containsExactly(3);
    }

    @Test
    void aLineTheTokenizerRejectsCarriesTheTokenizersPositionAndMessage() {
        String line = "account=cash; amount=1; memo=\"open";
        TokenizeException expected = catchThrowableOfType(() -> LineTokenizer.tokenize(line), TokenizeException.class);
        ImportReport report = LedgerImport.importLines(new Ledger(), List.of(line));
        assertThat(report.rejected()).containsExactly(new Rejection(1, expected.position(), expected.getMessage()));
        assertThat(report.rejected().get(0).position()).isEqualTo(29);
        assertThat(report.rejected().get(0).reason()).contains("unterminated quote");
    }

    @Test
    void aLineWithoutAnAccountOrAnAmountIsRejected() {
        ImportReport report = LedgerImport.importLines(new Ledger(), List.of("amount=5", "account=x"));
        assertThat(report.rejected()).containsExactly(new Rejection(1, -1, "missing account"),
                new Rejection(2, -1, "missing amount"));
    }

    @Test
    void anAmountThatIsNotANonZeroDecimalWithAtMostTwoPlacesIsRejected() {
        ImportReport report = LedgerImport.importLines(new Ledger(), List.of(
                "account=x; amount=1.234", "account=x; amount=abc", "account=x; amount=0", "account=x; amount="));
        assertThat(report.rejected()).extracting(Rejection::reason).containsOnly("invalid amount");
        assertThat(report.rejected()).extracting(Rejection::line).containsExactly(1, 2, 3, 4);
        assertThat(report.rejected()).extracting(Rejection::position).containsOnly(-1);
    }

    @Test
    void aNameOtherThanAccountAmountOrMemoIsRejected() {
        ImportReport report = LedgerImport.importLines(new Ledger(), List.of("account=x; amount=1; note=y"));
        assertThat(report.rejected()).containsExactly(new Rejection(1, -1, "unknown name 'note'"));
    }

    @Test
    void aRejectedLineAddsNothingAndDoesNotStopTheImport() {
        Ledger ledger = new Ledger();
        ImportReport report = LedgerImport.importLines(ledger, List.of(
                "account=a; amount=1", "account=b; amount=x", "account=c; amount=2; a=1; a=2", "account=d; amount=3"));
        assertThat(report.imported()).isEqualTo(2);
        assertThat(ledger.entries()).extracting(Entry::account).containsExactly("a", "d");
        assertThat(report.rejected()).extracting(Rejection::line).containsExactly(2, 3);
        assertThat(report.rejected().get(1).reason()).contains("duplicate name");
    }

    @Test
    void theRejectionsCannotBeModified() {
        ImportReport report = LedgerImport.importLines(new Ledger(), List.of("oops"));
        assertThatThrownBy(() -> report.rejected().add(new Rejection(9, -1, "x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
