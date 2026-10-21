package org.cashbook.ledger;

public record Entry(String account, long amountCents, String memo) {

    public Entry {
        if (account == null || account.isBlank()) {
            throw new IllegalArgumentException("account must not be null or blank");
        }
        if (amountCents == 0) {
            throw new IllegalArgumentException("amountCents must not be zero");
        }
        memo = memo == null ? "" : memo;
    }
}