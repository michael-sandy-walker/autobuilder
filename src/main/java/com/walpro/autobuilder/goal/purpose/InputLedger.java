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
public record InputLedger(List<InputEntry> entries, List<InputEvent> events) {

    public static final InputLedger EMPTY = new InputLedger(List.of());

    public InputLedger(List<InputEntry> entries) {
        this(entries, List.of());
    }

    public InputLedger {
        entries = List.copyOf(entries);
        events = List.copyOf(events);
    }

    public static InputLedger seeded(InputEntry seed) {
        return new InputLedger(List.of(seed));
    }

    public InputLedger with(InputEntry input) {
        List<InputEntry> out = new java.util.ArrayList<>(entries);
        out.add(input);
        return new InputLedger(out, events);
    }

    /** {@code event} recorded, or replacing the one with its id: events are decided, never removed. */
    public InputLedger with(InputEvent event) {
        List<InputEvent> out = new java.util.ArrayList<>(events);
        out.removeIf(e -> e.id().equals(event.id()));
        out.add(event);
        return new InputLedger(entries, out);
    }

    public boolean withdrawn(String inputId) {
        return events.stream().anyMatch(e -> InputEvent.WITHDRAWAL.equals(e.kind()) && e.inputId().equals(inputId));
    }

    /** The inputs that count: a withdrawn one stays in the record and asks nothing. */
    public List<InputEntry> active() {
        return entries.stream().filter(e -> !withdrawn(e.id())).toList();
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
     * under a line naming it, and what was decided about them follows; a reader that must tell them apart reads
     * {@link #entries()}.
     */
    public String specification() {
        if (entries.isEmpty()) return null;
        StringBuilder out = new StringBuilder();
        List<InputEntry> counting = active();
        if (counting.size() == 1) {
            out.append(counting.get(0).content());
        } else {
            for (InputEntry e : counting) {
                if (out.length() > 0) out.append("\n\n");
                out.append("--- input ").append(e.sequence()).append(" (").append(e.kind()).append(") ---\n").append(e.content());
            }
        }
        for (InputEvent e : events) {
            if (e.replacedReceiving() && !withdrawn(e.inputId())) {
                out.append("\n\n--- decided: ").append(e.subject()).append(" ---\n").append(e.other())
                        .append(" This replaces: ").append(e.receiving());
            }
        }
        return out.toString();
    }
}
