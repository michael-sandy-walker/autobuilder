package org.pettycash;

import java.time.LocalDate;

public record Expense(LocalDate date, String payee, Amount amount, Category category, boolean billable) {

    public Expense {
        if (date == null) {
            throw new IllegalArgumentException("date must not be null");
        }
        if (payee == null || payee.isBlank()) {
            throw new IllegalArgumentException("payee must not be blank");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null");
        }
        if (amount.cents() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (category == null) {
            throw new IllegalArgumentException("category must not be null");
        }
    }
}