package com.walpro.autobuilder.goal.purpose;

import java.util.List;

/**
 * T4-EC slice 1: the ordered, append-only record of a project's inputs. Immutable; {@link PurposeService} holds
 * the current one.
 *
 * <p>Named for the inputs it records: the stuck layer and closure already keep ledgers of their own.
 *
 * <p>A new root is a new purpose (a reset renews the lineage too), so it starts a new ledger; a later input is appended.
 */
public record InputLedger(List<InputEntry> entries) {

    public static final InputLedger EMPTY = new InputLedger(List.of());

    public InputLedger {
        entries = List.copyOf(entries);
    }

    public static InputLedger seeded(InputEntry seed) {
        return new InputLedger(List.of(seed));
    }

    public InputLedger with(InputEntry input) {
        List<InputEntry> out = new java.util.ArrayList<>(entries);
        out.add(input);
        return new InputLedger(out);
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
     * holds only the seed, the seed itself, byte for byte. Several inputs follow one another in arrival order, each
     * under a line naming it; a reader that must tell them apart reads {@link #entries()}.
     */
    public String specification() {
        if (entries.isEmpty()) return null;
        if (entries.size() == 1) return entries.get(0).content();
        StringBuilder out = new StringBuilder();
        for (InputEntry e : entries) {
            if (out.length() > 0) out.append("\n\n");
            out.append("--- input ").append(e.sequence()).append(" (").append(e.kind()).append(") ---\n").append(e.content());
        }
        return out.toString();
    }
}
