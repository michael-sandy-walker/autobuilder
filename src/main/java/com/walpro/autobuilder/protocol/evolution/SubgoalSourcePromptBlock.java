package com.walpro.autobuilder.protocol.evolution;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.protocol.PromptTokenBudget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
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
 * IS a file in the tree, so no list of common words is needed -- and a file the last, discarded answer
 * could not edit. That file first; then what the subgoal names (for a read-only test it names, the named
 * files that test uses, then the test); then the other files the work may write; then the ones it may only
 * read -- each group first mentioned first.
 *
 * <p><b>How much.</b> At most half the headroom still free where the block is placed; the rest stays
 * for the blocks after it. A file that does not fit is never cut mid-way, because a half-shown file
 * invites the same invention: it is shown as the whole members the work names (an edit block needs only
 * the text it changes), or, when none is named or none fits, named as left out.
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
    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public static String render(CycleContextData input, int headroomTokens) {
        return render(input, headroomTokens, path -> false);
    }

    /**
     * @param isProtected the operator's protected paths: a file the work may only read is shown after the
     *                    files it may write, so a read-only test never takes the place of the class to change.
     */
    public static String render(CycleContextData input, int headroomTokens, Predicate<String> isProtected) {
        if (!enabled) return null;
        if (input == null || input.previousOutput() == null || input.previousOutput().isEmpty()) return null;
        StringBuilder text = new StringBuilder();
        if (input.currentSubGoalDescription() != null) text.append(input.currentSubGoalDescription()).append('\n');
        if (input.currentSubGoalCriteria() != null) input.currentSubGoalCriteria().forEach(c -> text.append(c).append('\n'));
        int subgoalEnd = text.length();
        // T4-EB: and the files the GOAL names, and the reasons the last change was refused. Receiver-questions
        // run 2026-09-29_16-21-43: the subgoal was to analyse a test, the goal said "the one class to change
        // is StuckQuestions", and the model rewrote that 502-line class whole from a two-line outline -- it
        // had never been shown it. First mentioned first, so the subgoal's own files keep the budget.
        if (input.currentGoalDescription() != null) text.append(input.currentGoalDescription()).append('\n');
        if (input.rejectionFeedback() != null) input.rejectionFeedback().forEach(r -> { if (r != null) text.append(r).append('\n'); });

        // T6-T: the files the last, discarded answer could not edit. Its SEARCH text was not in the file,
        // and the one authority for that text is the file -- so it is shown before anything else.
        EvolutionOutcome last = input.lastEvolutionOutcome();
        String discarded = last != null && last.discarded() ? last.summary() : null;
        List<String> refused = new ArrayList<>();
        if (discarded != null) {
            for (String path : input.previousOutput().keySet()) {
                if (path.endsWith(".java") && discarded.contains(path.replace('\\', '/'))) refused.add(path);
            }
        }
        if (text.isEmpty() && refused.isEmpty()) return null;

        Map<String, String> byClass = new LinkedHashMap<>();
        for (String path : input.previousOutput().keySet()) {
            String p = path.replace('\\', '/');
            if (!p.endsWith(".java")) continue;
            byClass.putIfAbsent(p.substring(p.lastIndexOf('/') + 1, p.length() - ".java".length()), path);
        }
        List<String> mentioned = new ArrayList<>();
        List<String> bySubgoal = new ArrayList<>();
        Matcher m = CLASS_NAME.matcher(text);
        while (m.find()) {
            String path = byClass.get(m.group(1));
            if (path != null && !mentioned.contains(path)) mentioned.add(path);
            if (path != null && m.start() < subgoalEnd && !bySubgoal.contains(path)) bySubgoal.add(path);
        }
        // T6-T: the order is what keeps the class to change in. Ladder-rows run 2026-10-05_12-25-01: the one
        // class the change had to edit (16 KB) was named after a read-only specification test and a class the
        // model had already finished; they took the budget first, and it was left out of 49 of 61 prompts.
        // The refused file first; then what the SUBGOAL is about -- the files it names that the work may write,
        // then for each read-only file it names (the failing test) the named files that file uses and that file
        // itself; then the other files the work may write; then the ones it may only read.
        Map<String, String> named = new LinkedHashMap<>();
        java.util.function.Consumer<String> add = path -> {
            String content = input.previousOutput().get(path);
            if (content != null && !content.isBlank()) named.putIfAbsent(path, content);
        };
        refused.forEach(add);
        bySubgoal.stream().filter(p -> !isProtected.test(p)).forEach(add);
        for (String p : bySubgoal) {
            if (!isProtected.test(p)) continue;
            String reader = input.previousOutput().get(p);
            if (reader == null) continue;
            mentioned.stream().filter(q -> !isProtected.test(q) && !q.equals(p))
                    .filter(q -> Pattern.compile("\\b" + Pattern.quote(simpleName(q)) + "\\b").matcher(reader).find())
                    .forEach(add);
            add.accept(p);
        }
        mentioned.stream().filter(p -> !isProtected.test(p)).forEach(add);
        mentioned.stream().filter(isProtected).forEach(add);
        if (named.isEmpty()) return null;

        // The names a too-large file's parts are chosen by: what the work says, and what the discarded answer
        // tried to change.
        String memberText = text + (discarded == null ? "" : discarded + "\n" + nullToEmpty(last.rawResponse()));

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
            // A file the work may only read is there for the cases the work names (the failing test's own),
            // not to be edited: those parts, when it names any, and the budget stays for the files to change.
            String block = isProtected.test(e.getKey()) ? parts(e.getKey(), e.getValue(), memberText, budget - used, true) : null;
            if (block == null) block = "```java [" + e.getKey() + "]\n" + e.getValue().stripTrailing() + "\n```\n\n";
            int cost = PromptTokenBudget.estimateTokens(block);
            if (used + cost > budget) {
                block = parts(e.getKey(), e.getValue(), memberText, budget - used, isProtected.test(e.getKey()));
                cost = block == null ? 0 : PromptTokenBudget.estimateTokens(block);
                if (block == null || used + cost > budget) {
                    leftOut.add(e.getKey());
                    continue;
                }
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

    /**
     * T6-T: a file too large to show whole, shown as the members the work names -- each exactly as the file
     * has it, with its comment, so an edit block's SEARCH part can be copied from it. An edit needs the text
     * it changes, not the whole file; a member is never cut, and a file with no named member stays left out.
     * Null when nothing named fits in {@code tokens}.
     */
    static String parts(String path, String content, String text, int tokens) {
        return parts(path, content, text, tokens, false);
    }

    static String parts(String path, String content, String text, int tokens, boolean readOnly) {
        if (tokens <= 0) return null;
        CompilationUnit unit = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)).parse(content).getResult().orElse(null);
        if (unit == null) return null;
        // A member counts as named when the text writes its name as code -- called, qualified, or in a shape
        // prose does not take (camelCase, underscores): "of" or "row" in a sentence names nothing.
        Set<String> words = new java.util.HashSet<>();
        Matcher w = WORD.matcher(text);
        while (w.find()) {
            String word = w.group();
            boolean called = w.end() < text.length() && text.charAt(w.end()) == '(';
            boolean qualified = w.start() > 0 && text.charAt(w.start() - 1) == '.';
            boolean codeShaped = word.indexOf('_') >= 0 || !word.substring(1).equals(word.substring(1).toLowerCase());
            if (called || qualified || codeShaped) words.add(word);
        }
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder("Only parts of ").append(path).append(" are shown here: the members this work"
                + " names, each exactly as the file has it.").append(readOnly ? " This file is not yours to change.\n"
                : " Change this file ONLY with edit blocks whose SEARCH parts are copied from these parts; a whole-file"
                + " answer would lose everything not shown.\n");
        int header = PromptTokenBudget.estimateTokens(sb);
        int used = header;
        for (BodyDeclaration<?> member : unit.findAll(BodyDeclaration.class)) {
            if (!(member instanceof CallableDeclaration<?> || member instanceof FieldDeclaration)) continue;
            if (member.getParentNode().filter(n -> n instanceof TypeDeclaration<?>).isEmpty()) continue;
            if (!names(member).stream().anyMatch(words::contains)) continue;
            int from = member.getComment().flatMap(c -> c.getBegin()).map(b -> b.line)
                    .orElse(member.getBegin().map(b -> b.line).orElse(0));
            int to = member.getEnd().map(e -> e.line).orElse(0);
            if (from < 1 || to < from || to > lines.length) continue;
            String block = "```java [" + path + ", lines " + from + "-" + to + "]\n"
                    + String.join("\n", java.util.Arrays.copyOfRange(lines, from - 1, to)) + "\n```\n";
            int cost = PromptTokenBudget.estimateTokens(block);
            if (used + cost > tokens) continue;
            sb.append(block);
            used += cost;
        }
        return used == header ? null : sb.append('\n').toString();
    }

    private static List<String> names(BodyDeclaration<?> member) {
        if (member instanceof CallableDeclaration<?> c) return List.of(c.getNameAsString());
        return ((FieldDeclaration) member).getVariables().stream().map(v -> v.getNameAsString()).toList();
    }

    private static String simpleName(String path) {
        String p = path.replace('\\', '/');
        return p.substring(p.lastIndexOf('/') + 1, p.length() - ".java".length());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
