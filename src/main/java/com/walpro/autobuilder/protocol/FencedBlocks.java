package com.walpro.autobuilder.protocol;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a fenced block in a model's answer ENDS: at three backticks on a line of their own, Markdown's own
 * rule -- never at three backticks anywhere.
 *
 * <p>Every parser that carried a file out of an answer ended the block at the first three backticks it met,
 * mid-line included, so a source file that contains them could not be written at all: edit-hunks run
 * 2026-09-30_18-25 wrote {@code EditHunks}, whose job is to match fences, and every attempt was cut at
 * {@code Pattern.compile("(?s)```} into an "unclosed string literal" that no question could end -- the
 * observer read the cut file and the dossier blamed the model for a missing quote.
 */
public final class FencedBlocks {

    /** A closing fence, as a regex fragment for a pattern compiled with MULTILINE: three backticks alone on their line. */
    public static final String CLOSING_FENCE = "^[ \\t]*```[ \\t]*$";

    private static final Pattern CLOSING = Pattern.compile("(?m)" + CLOSING_FENCE);

    private FencedBlocks() {
    }

    /** The index of the first closing fence at or after {@code from}, or -1 when there is none. */
    public static int closeAfter(String content, int from) {
        if (content == null || from > content.length()) return -1;
        Matcher m = CLOSING.matcher(content);
        return m.find(Math.max(0, from)) ? m.start() : -1;
    }
}
