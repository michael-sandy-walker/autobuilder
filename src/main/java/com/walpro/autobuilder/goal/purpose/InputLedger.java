package com.walpro.autobuilder.goal.purpose;

import java.util.List;

/**
 * T4-EC slice 1: the ordered, append-only record of a project's inputs. Immutable; {@link PurposeService} holds
 * the current one.
 *
 * <p>Named for the inputs it records: the stuck layer and closure already keep ledgers of their own.
 *
 * <p>Slice 1 holds one entry, the seed. A new root is a new purpose (a reset renews the lineage too), so it starts
 * a new ledger rather than appending to the old one; appending arrives with the slices that add a second input.
 */
public record InputLedger(List<InputEntry> entries) {

    public static final InputLedger EMPTY = new InputLedger(List.of());

    public InputLedger {
        entries = List.copyOf(entries);
    }

    public static InputLedger seeded(InputEntry seed) {
        return new InputLedger(List.of(seed));
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** The seed, or null when nothing has been recorded. */
    public InputEntry seed() {
        return entries.isEmpty() ? null : entries.get(0);
    }

    /**
     * The inputs so far as one specification -- the text every reader of the purpose works from. While the ledger
     * holds only the seed, the seed itself. Readers that must tell inputs apart read {@link #entries()} instead;
     * they move there when a second input can exist.
     */
    public String specification() {
        if (entries.size() > 1) {
            throw new IllegalStateException("the ledger holds " + entries.size()
                    + " inputs; reading them as one specification is not defined yet");
        }
        return entries.isEmpty() ? null : entries.get(0).content();
    }
}
