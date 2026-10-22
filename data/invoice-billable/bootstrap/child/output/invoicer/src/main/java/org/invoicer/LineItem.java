package org.invoicer;

public record LineItem(String description, int quantity, Money unitPrice) {

    public LineItem {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
        if (unitPrice == null) {
            throw new IllegalArgumentException("unitPrice must not be null");
        }
    }

    public Money amount() {
        return unitPrice.times(quantity);
    }
}