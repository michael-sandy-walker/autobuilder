package org.cashbook.ledger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

public class Ledger {

    private final List<Entry> entries = new ArrayList<>();

    public Ledger() {
    }

    public int append(Entry entry) {
        entries.add(entry);
        return entries.size();
    }

    public int transfer(String from, String to, long cents, String memo) {
        if (cents <= 0) {
            throw new IllegalArgumentException("cents must be positive");
        }
        if (from.equals(to)) {
            throw new IllegalArgumentException("from and to must be different");
        }
        append(new Entry(from, -cents, memo));
        return append(new Entry(to, cents, memo));
    }

    public List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public long balance(String account) {
        long sum = 0;
        for (Entry e : entries) {
            if (e.account().equals(account)) {
                sum += e.amountCents();
            }
        }
        return sum;
    }

    public List<String> accounts() {
        TreeSet<String> set = new TreeSet<>();
        for (Entry e : entries) {
            set.add(e.account());
        }
        return Collections.unmodifiableList(new ArrayList<>(set));
    }
}