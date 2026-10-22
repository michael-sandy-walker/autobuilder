package org.pettycash;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public record Period(LocalDate from, LocalDate to) {

    public Period {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to must not be null");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
    }

    public boolean contains(LocalDate date) {
        if (date == null) {
            return false;
        }
        return !date.isBefore(from) && !date.isAfter(to);
    }

    public long days() {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }
}