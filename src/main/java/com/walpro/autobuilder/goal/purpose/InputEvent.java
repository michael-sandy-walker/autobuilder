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
 * @param decision  {@code input}, {@code receiving}, or null while open
 * @param by        who settled it: {@code intent}, or who decided
 */
public record InputEvent(String id, String kind, String inputId, String subject, String receiving, String other,
                         String heldLine, String heldPath, List<String> replaces, String status, String decision,
                         String by, Instant at) {

    public static final String CORRECTION = "correction";
    public static final String CONFLICT = "conflict";
    public static final String WITHDRAWAL = "withdrawal";
    public static final String OPEN = "open";
    public static final String DECIDED = "decided";
    public static final String FOR_INPUT = "input";
    public static final String FOR_RECEIVING = "receiving";

    public InputEvent {
        replaces = replaces == null ? List.of() : List.copyOf(replaces);
    }

    public boolean open() {
        return OPEN.equals(status);
    }

    /** The input's rule took the receiving one's place. */
    public boolean replacedReceiving() {
        return CORRECTION.equals(kind) || (CONFLICT.equals(kind) && FOR_INPUT.equals(decision) && heldLine == null);
    }

    /** A carried claim that is not a requirement: held while open, and for good once decided for the receiving project. */
    public boolean keepsLineOut() {
        return heldLine != null && (open() || FOR_RECEIVING.equals(decision));
    }
}
