package org.invoicer;

import org.junit.jupiter.api.Test;
import org.pettycash.Amount;
import org.pettycash.Category;
import org.pettycash.Expense;
import org.pettycash.ExpenseBook;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The integration: the billable expenses of a petty-cash book become the lines of an invoice. */
class BillableExpensesSpecTest {

    private static final Period OCTOBER = new Period(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

    private static ExpenseBook book() {
        ExpenseBook book = new ExpenseBook();
        book.add(new Expense(LocalDate.of(2026, 9, 30), "early", Amount.parse("1"), Category.OTHER, true));
        book.add(new Expense(LocalDate.of(2026, 10, 1), "rail", Amount.parse("30"), Category.TRAVEL, true));
        book.add(new Expense(LocalDate.of(2026, 10, 3), "cafe", Amount.parse("4.20"), Category.FOOD, false));
        book.add(new Expense(LocalDate.of(2026, 10, 31), "paper", Amount.parse("9.99"), Category.OFFICE, true));
        book.add(new Expense(LocalDate.of(2026, 11, 1), "late", Amount.parse("2"), Category.OTHER, true));
        return book;
    }

    @Test
    void theBillableExpensesOfThePeriodBecomeLinesInOrder() {
        assertThat(BillableExpenses.lines(book(), OCTOBER, "EUR")).containsExactly(
                new LineItem("rail", 1, new Money(3000, "EUR")),
                new LineItem("paper", 1, new Money(999, "EUR")));
    }

    @Test
    void thePeriodIncludesItsFirstAndLastDayAndNothingOutside() {
        assertThat(BillableExpenses.lines(book(), OCTOBER, "EUR")).extracting(LineItem::description)
                .containsExactly("rail", "paper").doesNotContain("early", "late");
    }

    @Test
    void anExpenseThatIsNotBillableIsNoLine() {
        assertThat(BillableExpenses.lines(book(), OCTOBER, "EUR")).extracting(LineItem::description).doesNotContain("cafe");
    }

    @Test
    void theAmountIsTakenInTheInvoicesCurrency() {
        assertThat(BillableExpenses.lines(book(), OCTOBER, "CHF")).extracting(l -> l.unitPrice().currency()).containsOnly("CHF");
        assertThat(BillableExpenses.lines(book(), OCTOBER, "CHF").get(0).unitPrice().cents()).isEqualTo(3000);
    }

    @Test
    void anInvoiceIsMadeFromTheBookForItsPeriod() {
        Invoice invoice = BillableExpenses.invoice("2026-010", OCTOBER, "EUR", book());
        assertThat(invoice.number()).isEqualTo("2026-010");
        assertThat(invoice.period()).isEqualTo(OCTOBER);
        assertThat(invoice.lines()).hasSize(2);
        assertThat(invoice.total()).isEqualTo(new Money(3999, "EUR"));
    }

    @Test
    void aBookWithNothingBillableInThePeriodGivesNoLinesAndAZeroInvoice() {
        ExpenseBook book = new ExpenseBook();
        book.add(new Expense(LocalDate.of(2026, 10, 3), "cafe", Amount.parse("4.20"), Category.FOOD, false));
        assertThat(BillableExpenses.lines(book, OCTOBER, "EUR")).isEmpty();
        assertThat(BillableExpenses.invoice("2026-011", OCTOBER, "EUR", book).total()).isEqualTo(new Money(0, "EUR"));
    }

    @Test
    void theLinesCannotBeModified() {
        List<LineItem> lines = BillableExpenses.lines(book(), OCTOBER, "EUR");
        assertThatThrownBy(() -> lines.add(new LineItem("x", 1, new Money(1, "EUR")))).isInstanceOf(UnsupportedOperationException.class);
    }
}
