package org.pettycash;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ExpenseBook {

    private final List<Expense> expenses = new ArrayList<>();

    public void add(Expense expense) {
        if (expense == null) {
            throw new IllegalArgumentException("expense must not be null");
        }
        expenses.add(expense);
    }

    public List<Expense> expenses() {
        return Collections.unmodifiableList(expenses);
    }

    public Amount total() {
        long sum = 0;
        for (Expense e : expenses) {
            sum += e.amount().cents();
        }
        return new Amount(sum);
    }

    public Amount totalFor(Category category) {
        long sum = 0;
        for (Expense e : expenses) {
            if (e.category() == category) {
                sum += e.amount().cents();
            }
        }
        return new Amount(sum);
    }

    public List<Expense> within(Period period) {
        List<Expense> result = new ArrayList<>();
        for (Expense e : expenses) {
            if (period.contains(e.date())) {
                result.add(e);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public List<Expense> billable() {
        List<Expense> result = new ArrayList<>();
        for (Expense e : expenses) {
            if (e.billable()) {
                result.add(e);
            }
        }
        return Collections.unmodifiableList(result);
    }
}