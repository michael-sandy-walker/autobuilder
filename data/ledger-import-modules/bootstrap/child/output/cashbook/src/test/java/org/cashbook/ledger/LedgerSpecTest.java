package org.cashbook.ledger;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The specification of cashbook: what a Ledger holds, what it answers, and what it refuses. */
class LedgerSpecTest {

    @Test
    void aNewLedgerIsEmpty() {
        Ledger ledger = new Ledger();
        assertThat(ledger.entries()).isEmpty();
        assertThat(ledger.accounts()).isEmpty();
        assertThat(ledger.balance("cash")).isZero();
    }

    @Test
    void appendKeepsEntriesInOrderAndReturnsTheirNumberFromOne() {
        Ledger ledger = new Ledger();
        assertThat(ledger.append(new Entry("cash", 1250, "coffee"))).isEqualTo(1);
        assertThat(ledger.append(new Entry("bank", -500, "fee"))).isEqualTo(2);
        assertThat(ledger.entries()).containsExactly(new Entry("cash", 1250, "coffee"), new Entry("bank", -500, "fee"));
    }

    @Test
    void theBalanceOfAnAccountIsTheSumOfItsEntries() {
        Ledger ledger = new Ledger();
        ledger.append(new Entry("cash", 1250, "a"));
        ledger.append(new Entry("bank", 9000, "b"));
        ledger.append(new Entry("cash", -250, "c"));
        assertThat(ledger.balance("cash")).isEqualTo(1000);
        assertThat(ledger.balance("bank")).isEqualTo(9000);
        assertThat(ledger.balance("savings")).isZero();
    }

    @Test
    void accountsAreTheOnesWithEntriesInAlphabeticalOrder() {
        Ledger ledger = new Ledger();
        ledger.append(new Entry("cash", 1, "a"));
        ledger.append(new Entry("bank", 1, "b"));
        ledger.append(new Entry("cash", 1, "c"));
        assertThat(ledger.accounts()).containsExactly("bank", "cash");
    }

    @Test
    void aTransferAppendsTheDebitThenTheCredit() {
        Ledger ledger = new Ledger();
        ledger.append(new Entry("bank", 10000, "salary"));
        assertThat(ledger.transfer("bank", "cash", 2500, "withdrawal")).isEqualTo(3);
        assertThat(ledger.entries()).containsExactly(new Entry("bank", 10000, "salary"),
                new Entry("bank", -2500, "withdrawal"), new Entry("cash", 2500, "withdrawal"));
        assertThat(ledger.balance("bank")).isEqualTo(7500);
        assertThat(ledger.balance("cash")).isEqualTo(2500);
    }

    @Test
    void aTransferMustMoveAPositiveAmountBetweenTwoAccounts() {
        Ledger ledger = new Ledger();
        assertThatThrownBy(() -> ledger.transfer("bank", "cash", 0, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.transfer("bank", "cash", -5, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.transfer("cash", "cash", 5, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(ledger.entries()).isEmpty();
    }

    @Test
    void anEntryNeedsAnAccountAndANonZeroAmount() {
        assertThatThrownBy(() -> new Entry("", 5, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Entry("  ", 5, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Entry(null, 5, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Entry("cash", 0, "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEntryWithoutAMemoHasAnEmptyMemo() {
        assertThat(new Entry("cash", 5, null).memo()).isEmpty();
        Entry e = new Entry("cash", -5, "m");
        assertThat(e.account()).isEqualTo("cash");
        assertThat(e.amountCents()).isEqualTo(-5);
        assertThat(e.memo()).isEqualTo("m");
    }

    @Test
    void theEntriesCannotBeModifiedFromOutside() {
        Ledger ledger = new Ledger();
        ledger.append(new Entry("cash", 5, "a"));
        List<Entry> entries = ledger.entries();
        assertThatThrownBy(() -> entries.add(new Entry("cash", 1, "b"))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ledger.accounts().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theEntriesReadEarlierDoNotChangeWhenMoreAreAppended() {
        Ledger ledger = new Ledger();
        ledger.append(new Entry("cash", 5, "a"));
        List<Entry> before = ledger.entries();
        ledger.append(new Entry("cash", 6, "b"));
        assertThat(before).hasSize(1);
        assertThat(ledger.entries()).hasSize(2);
    }
}
