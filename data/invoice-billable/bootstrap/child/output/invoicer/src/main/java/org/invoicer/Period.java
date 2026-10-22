package org.invoicer;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public record Period(LocalDate from, LocalDate to) {

    public Period {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to must not be null");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to: " + from + " > " + to);
        }
    }

    public boolean contains(LocalDate day) {
        if (day == null) {
            return false;
        }
        return !day.isBefore(from) && !day.isAfter(to);
    }

    public long days() {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }
}