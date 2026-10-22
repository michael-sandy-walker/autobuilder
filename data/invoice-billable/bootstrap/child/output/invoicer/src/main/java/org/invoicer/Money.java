package org.invoicer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record Money(long cents, String currency) {

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern PARSE = Pattern.compile("^(-?\\d+(\\.\\d{1,2})?) ([A-Z]{3})$");

    public Money {
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be three upper-case letters: " + currency);
        }
    }

    public static Money parse(String s) {
        if (s == null) {
            throw new IllegalArgumentException("null input");
        }
        Matcher m = PARSE.matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException("cannot parse: " + s);
        }
        String amount = m.group(1);
        String currency = m.group(3);
        boolean negative = amount.startsWith("-");
        String digits = negative ? amount.substring(1) : amount;
        int dot = digits.indexOf('.');
        long whole = dot < 0 ? Long.parseLong(digits) : Long.parseLong(digits.substring(0, dot));
        long frac = 0;
        if (dot >= 0) {
            String fracStr = digits.substring(dot + 1);
            frac = Long.parseLong(fracStr);
            if (fracStr.length() == 1) {
                frac *= 10;
            }
        }
        long cents = whole * 100 + frac;
        if (negative) {
            cents = -cents;
        }
        return new Money(cents, currency);
    }

    public Money plus(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException("currency mismatch: " + this.currency + " vs " + other.currency);
        }
        return new Money(this.cents + other.cents, this.currency);
    }

    public Money times(int n) {
        return new Money(this.cents * n, this.currency);
    }

    @Override
    public String toString() {
        long abs = Math.abs(cents);
        long whole = abs / 100;
        long frac = abs % 100;
        String sign = cents < 0 ? "-" : "";
        return sign + whole + "." + String.format("%02d", frac) + " " + currency;
    }
}