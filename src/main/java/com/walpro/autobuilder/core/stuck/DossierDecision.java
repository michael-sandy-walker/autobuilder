package com.walpro.autobuilder.core.stuck;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-DY dossier: the move the model chose, read strictly. A reply that names no move from the menu, or
 * a HINT without its text, is NONE -- a move the loop cannot carry out is not a move.
 *
 * <p>The menu is what the loop may do on its own (design note, point 6). Never on it: changing the
 * specification, releasing a requirement, declaring a goal met, halting the run.
 */
public record DossierDecision(Move move, String because, String hint) {

    public enum Move {
        /** A reading of the situation, delivered to the next change's prompt, labelled as the model's. */
        HINT,
        /** Ask the planner to change the plan for this subgoal. */
        REPLAN,
        /** Split the current subgoal into one work item per unmet criterion. */
        DIVIDE,
        /** Nothing the loop can do helps; the operator's diagnosis request stands alone. */
        NONE
    }

    private static final Pattern MOVE = Pattern.compile("(?im)^\\s*MOVE\\s*:\\s*([A-Z]+)\\b");
    /** A field runs to the next field name at the start of a line, or to the end. */
    private static final String NEXT_FIELD = "(?=^\\s*(?:MOVE|BECAUSE|HINT)\\s*:|\\z)";
    private static final Pattern BECAUSE = Pattern.compile("(?ism)^\\s*BECAUSE\\s*:\\s*(.+?)" + NEXT_FIELD);
    private static final Pattern HINT = Pattern.compile("(?ism)^\\s*HINT\\s*:\\s*(.+?)" + NEXT_FIELD);

    public static DossierDecision none(String because) {
        return new DossierDecision(Move.NONE, because, null);
    }

    public static DossierDecision parse(String reply) {
        if (reply == null) return none("no reply");
        Matcher m = MOVE.matcher(reply);
        if (!m.find()) return none("the reply named no move");
        Move move;
        try {
            move = Move.valueOf(m.group(1).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return none("the reply named a move that is not on the menu: " + m.group(1));
        }
        String because = group(BECAUSE, reply);
        String hint = group(HINT, reply);
        if (move == Move.HINT && (hint == null || hint.isBlank())) return none("HINT without a hint");
        return new DossierDecision(move, because, move == Move.HINT || move == Move.REPLAN ? hint : null);
    }

    private static String group(Pattern p, String reply) {
        Matcher m = p.matcher(reply);
        return m.find() ? m.group(1).strip() : null;
    }
}
