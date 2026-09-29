package com.walpro.autobuilder.protocol.evolution;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.protocol.PromptTokenBudget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-CQ: the SOURCE of the existing files the current subgoal names, so the model changing them has
 * read them.
 *
 * <p><b>The gap, from the field.</b> The evolution prompt shows an existing file only as the
 * structure summary -- one line per public method, {@code name(...) : ReturnType} -- and a file the
 * model must change is written back WHOLE. On a tree the loop wrote itself that was survivable: the
 * model is shown the structure of its own previous proposal. On an ADOPTED project the model had
 * never seen the source of the class it was asked to extend "keeping all existing declarations", so
 * it wrote them from the summary: participation-roles run 2026-09-25_19-57-35 changed eight existing
 * members of {@code ParticipationWindows}, and participation-totals run 2026-09-25_20-51-35 invented a
 * six-component {@code ChannelRequest} (it has seven) and called that constructor in every one of 19
 * candidates. The summary for that record lists its methods and not one of its components. The
 * authority for what the file says was the file, and the prompt cropped it to names.
 *
 * <p><b>Which files.</b> An existing file whose class name the subgoal's description or criteria, the
 * goal's description, or the reasons the last change was refused mention -- a name counts only when it
 * IS a file in the tree, so no list of common words is needed. First mentioned first.
 *
 * <p><b>How much.</b> At most half the headroom still free where the block is placed; the rest stays
 * for the blocks after it. A file that does not fit is named as left out rather than cut, because a
 * half-shown file invites the same invention.
 */
public final class SubgoalSourcePromptBlock {

    private SubgoalSourcePromptBlock() {
    }

    /**
     * On by default. Off only to REPLAY the grind this block ended (T4-DY slice B's DoD: the
     * participation-totals constructor grind, recreated by taking the fact away again), set from
     * {@code autobuilder.evolution.subgoal-source-block}.
     */
    private static volatile boolean enabled = true;

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    private static final Pattern CLASS_NAME = Pattern.compile("\\b([A-Z][A-Za-z0-9_]{2,})\\b");

    public static String render(CycleContextData input, int headroomTokens) {
        if (!enabled) return null;
        if (input == null || input.previousOutput() == null || input.previousOutput().isEmpty()) return null;
        StringBuilder text = new StringBuilder();
        if (input.currentSubGoalDescription() != null) text.append(input.currentSubGoalDescription()).append('\n');
        if (input.currentSubGoalCriteria() != null) input.currentSubGoalCriteria().forEach(c -> text.append(c).append('\n'));
        // T4-EB: and the files the GOAL names, and the reasons the last change was refused. Receiver-questions
        // run 2026-09-29_16-21-43: the subgoal was to analyse a test, the goal said "the one class to change
        // is StuckQuestions", and the model rewrote that 502-line class whole from a two-line outline -- it
        // had never been shown it. First mentioned first, so the subgoal's own files keep the budget.
        if (input.currentGoalDescription() != null) text.append(input.currentGoalDescription()).append('\n');
        if (input.rejectionFeedback() != null) input.rejectionFeedback().forEach(r -> { if (r != null) text.append(r).append('\n'); });
        if (text.isEmpty()) return null;

        Map<String, String> byClass = new LinkedHashMap<>();
        for (String path : input.previousOutput().keySet()) {
            String p = path.replace('\\', '/');
            if (!p.endsWith(".java")) continue;
            byClass.putIfAbsent(p.substring(p.lastIndexOf('/') + 1, p.length() - ".java".length()), path);
        }
        Map<String, String> named = new LinkedHashMap<>();
        Matcher m = CLASS_NAME.matcher(text);
        while (m.find()) {
            String path = byClass.get(m.group(1));
            String content = path == null ? null : input.previousOutput().get(path);
            if (content != null && !content.isBlank()) named.putIfAbsent(path, content);
        }
        if (named.isEmpty()) return null;

        int budget = Math.max(0, headroomTokens) / 2;
        StringBuilder sb = new StringBuilder("--- SOURCE OF THE EXISTING FILES THIS WORK NAMES ---\n")
                .append("These files exist, and this is exactly what they say now. To change one, send an edit block:"
                        + " each SEARCH part copied exactly from here, then what replaces it; everything you leave out"
                        + " stays as it is. If you write one whole instead, keep every declaration shown here -- the"
                        + " type's header, its fields, its constructors and the bodies of its methods -- as it is,"
                        + " unless the work asks you to change that declaration, and add yours beside them.\n\n");
        int used = PromptTokenBudget.estimateTokens(sb);
        List<String> leftOut = new ArrayList<>();
        int shown = 0;
        for (Map.Entry<String, String> e : named.entrySet()) {
            String block = "```java [" + e.getKey() + "]\n" + e.getValue().stripTrailing() + "\n```\n\n";
            int cost = PromptTokenBudget.estimateTokens(block);
            if (used + cost > budget) {
                leftOut.add(e.getKey());
                continue;
            }
            sb.append(block);
            used += cost;
            shown++;
        }
        if (shown == 0) return null;
        if (!leftOut.isEmpty()) {
            sb.append("Also named but too large to show here: ").append(String.join(", ", leftOut)).append(".\n");
        }
        return sb.toString().stripTrailing();
    }
}
