package org.invoicer;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The specification of invoicer: money with its currency, billing periods, invoice lines and the invoice total. */
class InvoicerSpecTest {

    private static final Period OCTOBER = new Period(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

    @Test
    void moneyParsesADecimalWithAtMostTwoPlacesAndACurrency() {
        assertThat(Money.parse("12.50 EUR")).isEqualTo(new Money(1250, "EUR"));
        assertThat(Money.parse("7 USD")).isEqualTo(new Money(700, "USD"));
        assertThat(Money.parse("-0.05 EUR")).isEqualTo(new Money(-5, "EUR"));
        assertThat(Money.parse("0.5 CHF")).isEqualTo(new Money(50, "CHF"));
    }

    @Test
    void moneyRefusesWhatIsNotADecimalAndACurrency() {
        for (String bad : List.of("1.234 EUR", "abc EUR", "12.50", "12.50 eur", "12.50 EURO", "")) {
            assertThatThrownBy(() -> Money.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new Money(5, "eu")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moneyAddsAndMultipliesInItsCurrencyOnly() {
        assertThat(new Money(1250, "EUR").plus(new Money(50, "EUR"))).isEqualTo(new Money(1300, "EUR"));
        assertThat(new Money(1250, "EUR").times(3)).isEqualTo(new Money(3750, "EUR"));
        assertThatThrownBy(() -> new Money(1, "EUR").plus(new Money(1, "USD"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moneyPrintsTwoPlacesAndItsCurrency() {
        assertThat(new Money(1250, "EUR")).hasToString("12.50 EUR");
        assertThat(new Money(-5, "USD")).hasToString("-0.05 USD");
        assertThat(new Money(700, "CHF")).hasToString("7.00 CHF");
    }

    @Test
    void aPeriodRunsFromItsFirstToItsLastDayInclusive() {
        assertThat(OCTOBER.contains(LocalDate.of(2026, 10, 1))).isTrue();
        assertThat(OCTOBER.contains(LocalDate.of(2026, 10, 31))).isTrue();
        assertThat(OCTOBER.contains(LocalDate.of(2026, 11, 1))).isFalse();
        assertThat(OCTOBER.days()).isEqualTo(31);
        assertThatThrownBy(() -> new Period(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLineItemsAmountIsItsUnitPriceTimesItsQuantity() {
        LineItem line = new LineItem("hours", 3, new Money(4000, "EUR"));
        assertThat(line.amount()).isEqualTo(new Money(12000, "EUR"));
        assertThatThrownBy(() -> new LineItem("hours", 0, new Money(1, "EUR"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LineItem(" ", 1, new Money(1, "EUR"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anInvoiceTotalsItsLinesInItsCurrency() {
        Invoice invoice = new Invoice("2026-001", OCTOBER, "EUR");
        assertThat(invoice.total()).isEqualTo(new Money(0, "EUR"));
        invoice.addLine(new LineItem("hours", 3, new Money(4000, "EUR")));
        invoice.addLine(new LineItem("travel", 1, new Money(2550, "EUR")));
        assertThat(invoice.total()).isEqualTo(new Money(14550, "EUR"));
        assertThat(invoice.number()).isEqualTo("2026-001");
        assertThat(invoice.period()).isEqualTo(OCTOBER);
    }

    @Test
    void anInvoiceRefusesALineInAnotherCurrency() {
        Invoice invoice = new Invoice("2026-002", OCTOBER, "EUR");
        assertThatThrownBy(() -> invoice.addLine(new LineItem("hours", 1, new Money(1, "USD"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(invoice.lines()).isEmpty();
    }

    @Test
    void anInvoicesLinesAreInOrderAndCannotBeModified() {
        Invoice invoice = new Invoice("2026-003", OCTOBER, "EUR");
        LineItem a = new LineItem("a", 1, new Money(1, "EUR")), b = new LineItem("b", 2, new Money(2, "EUR"));
        invoice.addLine(a);
        invoice.addLine(b);
        assertThat(invoice.lines()).containsExactly(a, b);
        assertThatThrownBy(() -> invoice.lines().add(a)).isInstanceOf(UnsupportedOperationException.class);
    }
}
