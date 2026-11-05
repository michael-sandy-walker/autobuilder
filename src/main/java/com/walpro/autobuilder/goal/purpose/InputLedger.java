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

    /** The inputs not withdrawn: a withdrawn one stays in the record and asks nothing. */
    public List<InputEntry> active() {
        return entries.stream().filter(e -> !withdrawn(e.id())).toList();
    }

    /** T4-ED: an import that waits for the operator to keep it or cancel it. */
    public boolean pending(String inputId) {
        return events.stream().anyMatch(e -> InputEvent.IMPORT.equals(e.kind()) && e.open() && e.inputId().equals(inputId))
                && !withdrawn(inputId);
    }

    /**
     * T4-EG: the inputs that are the project -- the seed and everything kept. A pending import is a proposal: it counts
     * for nothing until it is kept, and cancelling it leaves nothing behind.
     */
    public List<InputEntry> kept() {
        return active().stream().filter(e -> !pending(e.id())).toList();
    }

    /** T4-EG: whether {@code inputId} is part of the project. */
    public boolean isKept(String inputId) {
        return kept().stream().anyMatch(e -> e.id().equals(inputId));
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** The seed, or null when nothing has been recorded. */
    public InputEntry seed() {
        return entries.isEmpty() ? null : entries.get(0);
    }

    /**
     * The project as one specification -- what {@code meta/specification.txt} is written from (PurposeService). While
     * the project is only its seed, the seed itself, byte for byte. Each kept input's text follows in arrival order, as
     * plain text: T4-EG, once kept an input is the project, and nothing in the text says which part came from where. A
     * rule decided for an input follows as a line of its own.
     */
    public String specification() {
        if (entries.isEmpty()) return null;
        StringBuilder out = new StringBuilder();
        for (InputEntry e : kept()) {
            if (out.length() > 0) out.append("\n\n");
            out.append(e.content());
        }
        for (InputEvent e : events) {
            if (e.replacedReceiving() && isKept(e.inputId())) {
                String rule = InputEvent.FOR_OWN.equals(e.decision()) && e.resolution() != null ? e.resolution() : e.other();
                out.append("\n\n").append(e.subject()).append(": ").append(rule)
                        .append(" (this replaces: ").append(e.receiving()).append(")");
            }
        }
        return out.toString();
    }
}
