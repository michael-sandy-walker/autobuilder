package org.pettycash;

import java.util.regex.Pattern;

public record Amount(long cents) {

    private static final Pattern PATTERN = Pattern.compile("-?\\d+(\\.\\d{1,2})?");

    public static Amount parse(String s) {
        if (s == null || !PATTERN.matcher(s).matches()) {
            throw new IllegalArgumentException("Invalid amount: " + s);
        }
        boolean negative = s.startsWith("-");
        String digits = negative ? s.substring(1) : s;
        int dot = digits.indexOf('.');
        long whole;
        long frac;
        if (dot < 0) {
            whole = Long.parseLong(digits);
            frac = 0;
        } else {
            whole = Long.parseLong(digits.substring(0, dot));
            String fracStr = digits.substring(dot + 1);
            frac = Long.parseLong(fracStr);
            if (fracStr.length() == 1) {
                frac *= 10;
            }
        }
        long result = whole * 100 + frac;
        return new Amount(negative ? -result : result);
    }

    public Amount plus(Amount other) {
        return new Amount(this.cents + other.cents);
    }

    public Amount negate() {
        return new Amount(-this.cents);
    }

    @Override
    public String toString() {
        boolean negative = cents < 0;
        long abs = Math.abs(cents);
        long whole = abs / 100;
        long frac = abs % 100;
        return (negative ? "-" : "") + whole + "." + String.format("%02d", frac);
    }
}