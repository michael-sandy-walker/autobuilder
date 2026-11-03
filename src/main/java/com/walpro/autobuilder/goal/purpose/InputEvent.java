package com.walpro.autobuilder.goal.purpose;

import java.time.Instant;
import java.util.List;

/**
 * T4-EC slice 5: what was decided about an input, in the ledger beside the inputs. A CORRECTION is a difference the
 * operator's intent settled for the input. A CONFLICT is one nothing settled, or a claim the input carried that
 * cannot be true of the project receiving it: raised, held, and decided by someone. A WITHDRAWAL takes an input back out:
 * undo is an event, so the ledger keeps the history; its {@code replaces} are the criteria its goals carried.
 *
 * @param subject   what differs
 * @param receiving the receiving project's rule
 * @param other     the input's rule
 * @param heldLine  a carried claim held out of the requirements while open; null for a difference between two parts
 * @param heldPath  a file both projects have, the input's copy held; null otherwise
 * @param replaces  the requirement lines that stop counting, because the input's rule took their place
 * @param status    {@code open} or {@code decided}
 * @param decision  {@code input}, {@code receiving}, {@code own}, or null while open
 * @param by        who settled it: {@code intent}, or who decided
 * @param resolution what the operator decided instead, in their words, for an {@code own} decision; null otherwise
 */
public record InputEvent(String id, String kind, String inputId, String subject, String receiving, String other,
                         String heldLine, String heldPath, List<String> replaces, String status, String decision,
                         String by, Instant at, String resolution) {

    public static final String CORRECTION = "correction";
    public static final String CONFLICT = "conflict";
    public static final String WITHDRAWAL = "withdrawal";
    /**
     * T4-ED: an import that waits for the operator's confirmation -- open until kept ({@link #KEPT}); cancelling it is a
     * withdrawal. Its subject is what was brought in, by name.
     */
    public static final String IMPORT = "import";
    public static final String KEPT = "kept";
    public static final String OPEN = "open";
    public static final String DECIDED = "decided";
    public static final String FOR_INPUT = "input";
    public static final String FOR_RECEIVING = "receiving";
    /**
     * T4-ED: neither side as it is -- the operator's own resolution. A difference: their rule replaces the receiving
     * one. A file both have: the two are to be merged. A claim: it stays out, their requirement takes its place.
     */
    public static final String FOR_OWN = "own";

    /** Every event before T4-ED's own decisions: no resolution. */
    public InputEvent(String id, String kind, String inputId, String subject, String receiving, String other,
                      String heldLine, String heldPath, List<String> replaces, String status, String decision,
                      String by, Instant at) {
        this(id, kind, inputId, subject, receiving, other, heldLine, heldPath, replaces, status, decision, by, at, null);
    }

    public InputEvent {
        replaces = replaces == null ? List.of() : List.copyOf(replaces);
    }

    public boolean open() {
        return OPEN.equals(status);
    }

    /** The input's rule took the receiving one's place. */
    public boolean replacedReceiving() {
        return CORRECTION.equals(kind) || (CONFLICT.equals(kind) && heldLine == null
                && (FOR_INPUT.equals(decision) || FOR_OWN.equals(decision)));
    }

    /** A carried claim that is not a requirement: held while open, and for good once decided for the receiving project. */
    public boolean keepsLineOut() {
        return heldLine != null && (open() || FOR_RECEIVING.equals(decision) || FOR_OWN.equals(decision));
    }
}
