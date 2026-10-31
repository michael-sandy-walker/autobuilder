package org.shelfkit.catalogue;

/** An ISBN-13: thirteen digits whose last one is the EAN-13 check digit of the twelve before it. */
public record Isbn(String value) {

    public Isbn {
        if (value == null || !value.matches("\\d{13}")) {
            throw new CatalogueException("an ISBN is thirteen digits");
        }
        if (value.charAt(12) - '0' != checkDigit(value)) {
            throw new CatalogueException("the ISBN's check digit is wrong");
        }
    }

    /** The check digit of a number's first twelve digits: weights 1 and 3 in turn, up to the next ten. */
    static int checkDigit(String digits) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (digits.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return (10 - sum % 10) % 10;
    }

    @Override
    public String toString() {
        return value;
    }
}
