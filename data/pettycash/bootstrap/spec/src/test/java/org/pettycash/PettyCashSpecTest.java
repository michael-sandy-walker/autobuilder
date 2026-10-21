package org.pettycash;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The specification of pettycash: amounts in cents, periods, expenses, and the book that totals them. */
class PettyCashSpecTest {

    private static final LocalDate D1 = LocalDate.of(2026, 10, 3), D2 = LocalDate.of(2026, 10, 20), D3 = LocalDate.of(2026, 11, 2);

    private static Expense expense(LocalDate date, String payee, String amount, Category category, boolean billable) {
        return new Expense(date, payee, Amount.parse(amount), category, billable);
    }

    @Test
    void anAmountParsesADecimalWithAtMostTwoPlaces() {
        assertThat(Amount.parse("12.50")).isEqualTo(new Amount(1250));
        assertThat(Amount.parse("7")).isEqualTo(new Amount(700));
        assertThat(Amount.parse("-0.05")).isEqualTo(new Amount(-5));
        assertThat(Amount.parse("0.5")).isEqualTo(new Amount(50));
        for (String bad : List.of("1.234", "abc", "", "12,50")) {
            assertThatThrownBy(() -> Amount.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void anAmountAddsNegatesAndPrintsTwoPlaces() {
        assertThat(new Amount(1250).plus(new Amount(50))).isEqualTo(new Amount(1300));
        assertThat(new Amount(1250).negate()).isEqualTo(new Amount(-1250));
        assertThat(new Amount(1250)).hasToString("12.50");
        assertThat(new Amount(-5)).hasToString("-0.05");
    }

    @Test
    void aPeriodRunsFromItsFirstToItsLastDayInclusive() {
        Period october = new Period(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertThat(october.contains(LocalDate.of(2026, 10, 31))).isTrue();
        assertThat(october.contains(LocalDate.of(2026, 9, 30))).isFalse();
        assertThat(october.days()).isEqualTo(31);
        assertThatThrownBy(() -> new Period(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anExpenseNeedsAPayeeAndAPositiveAmount() {
        assertThatThrownBy(() -> expense(D1, " ", "5", Category.FOOD, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> expense(D1, "cafe", "0", Category.FOOD, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> expense(D1, "cafe", "-1", Category.FOOD, false)).isInstanceOf(IllegalArgumentException.class);
        Expense e = expense(D1, "cafe", "4.20", Category.FOOD, true);
        assertThat(e.payee()).isEqualTo("cafe");
        assertThat(e.amount()).isEqualTo(new Amount(420));
        assertThat(e.billable()).isTrue();
    }

    @Test
    void theCategoriesAreFoodTravelOfficeAndOther() {
        assertThat(Category.values()).containsExactly(Category.FOOD, Category.TRAVEL, Category.OFFICE, Category.OTHER);
    }

    @Test
    void theBookKeepsExpensesInOrderAndTotalsThem() {
        ExpenseBook book = new ExpenseBook();
        assertThat(book.total()).isEqualTo(new Amount(0));
        Expense a = expense(D1, "cafe", "4.20", Category.FOOD, false), b = expense(D2, "rail", "30", Category.TRAVEL, true);
        book.add(a);
        book.add(b);
        assertThat(book.expenses()).containsExactly(a, b);
        assertThat(book.total()).isEqualTo(new Amount(3420));
        assertThatThrownBy(() -> book.expenses().add(a)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theBookTotalsACategory() {
        ExpenseBook book = new ExpenseBook();
        book.add(expense(D1, "cafe", "4.20", Category.FOOD, false));
        book.add(expense(D2, "bakery", "1.80", Category.FOOD, false));
        book.add(expense(D2, "rail", "30", Category.TRAVEL, true));
        assertThat(book.totalFor(Category.FOOD)).isEqualTo(new Amount(600));
        assertThat(book.totalFor(Category.OFFICE)).isEqualTo(new Amount(0));
    }

    @Test
    void theBookListsTheExpensesOfAPeriodAndTheBillableOnes() {
        ExpenseBook book = new ExpenseBook();
        Expense a = expense(D1, "cafe", "4.20", Category.FOOD, false);
        Expense b = expense(D2, "rail", "30", Category.TRAVEL, true);
        Expense c = expense(D3, "paper", "9.99", Category.OFFICE, true);
        book.add(a);
        book.add(b);
        book.add(c);
        assertThat(book.within(new Period(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))).containsExactly(a, b);
        assertThat(book.billable()).containsExactly(b, c);
    }
}
