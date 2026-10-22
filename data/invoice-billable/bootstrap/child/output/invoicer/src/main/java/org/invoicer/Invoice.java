package org.invoicer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Invoice {

    private final String number;
    private final Period period;
    private final String currency;
    private final List<LineItem> lines = new ArrayList<>();

    public Invoice(String number, Period period, String currency) {
        if (number == null) {
            throw new IllegalArgumentException("number must not be null");
        }
        if (period == null) {
            throw new IllegalArgumentException("period must not be null");
        }
        if (currency == null) {
            throw new IllegalArgumentException("currency must not be null");
        }
        this.number = number;
        this.period = period;
        this.currency = currency;
    }

    public void addLine(LineItem line) {
        if (line == null) {
            throw new IllegalArgumentException("line must not be null");
        }
        if (!line.unitPrice().currency().equals(this.currency)) {
            throw new IllegalArgumentException("line currency " + line.unitPrice().currency() + " does not match invoice currency " + this.currency);
        }
        lines.add(line);
    }

    public List<LineItem> lines() {
        return Collections.unmodifiableList(lines);
    }

    public Money total() {
        Money sum = new Money(0, currency);
        for (LineItem line : lines) {
            sum = sum.plus(line.amount());
        }
        return sum;
    }

    public String number() {
        return number;
    }

    public Period period() {
        return period;
    }
}