package com.walpro.autobuilder.core.merge.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-EC stage 4, slice 3: a prompt operation on a selection -- the user's "merge/refine selected nodes" -- as the question
 * put to a model and the strict reading of its answer. Pure: the session service asks and records (option B of three,
 * the user's choice: the model's super node enters the log at once, by "model", marked proposed, and the operator
 * confirms, edits or removes it with the acts the canvas already has).
 *
 * <p>The model sees what the session holds about each selected node -- kind, project, title, metadata, what hangs under it
 * -- and answers in a fixed form. An answer without a title or an action is not a proposal, and nothing is recorded.
 */
public final class MergeRefine {

    /** What the model proposed: the super node's title, description, criteria, the action, and why. */
    public record Proposal(String title, String description, List<String> criteria, String action, String reason) {
    }

    private static final Pattern FIELD = Pattern.compile("(?m)^\\s*(TITLE|DESCRIPTION|ACTION|REASON):\\s*(.*)$");
    private static final Pattern ACTION = Pattern.compile("\\b(UNIFY|ADAPT_ONE|KEEP_BOTH)\\b");
    private static final int MAX_CHILDREN = 20;

    static final String SYSTEM = "You help merge two independently written software projects. You are shown nodes an operator"
            + " selected from their graphs, and the operator's request. You propose one new node above them that refines or"
            + " merges what they say. Answer only in the form asked for.";

    private MergeRefine() {
    }

    /** The question: the operator's request, then every selected node as the session holds it. */
    public static String question(MergeSession s, List<String> selection, String request) {
        StringBuilder q = new StringBuilder("The operator's request: ").append(request == null || request.isBlank()
                ? "merge/refine the selected nodes" : request.strip()).append("\n\nThe selected nodes:\n");
        int i = 0;
        for (String id : selection) {
            MergeSession.Node n = s.nodes().get(id);
            if (n == null) continue;
            q.append("\n").append(++i).append(". ").append(n.kind()).append(n.project() == null ? "" : " of project " + n.project())
                    .append(": ").append(n.title()).append("\n");
            for (Map.Entry<String, Object> m : n.metadata().entrySet()) {
                if (List.of("by", "at", "lastEditedBy").contains(m.getKey())) continue;
                q.append("   ").append(m.getKey()).append(": ").append(m.getValue()).append("\n");
            }
            List<String> below = new ArrayList<>();
            for (MergeSession.Edge e : s.edges().values()) {
                if (!e.from().equals(id) || e.kind() == MergeSession.EdgeKind.LINK) continue;
                MergeSession.Node c = s.nodes().get(e.to());
                if (c != null) below.add(c.kind().name().toLowerCase() + " " + c.title());
            }
            if (!below.isEmpty()) {
                q.append("   under it:\n");
                below.stream().limit(MAX_CHILDREN).forEach(b -> q.append("    - ").append(b).append("\n"));
                if (below.size() > MAX_CHILDREN) q.append("    - ... and ").append(below.size() - MAX_CHILDREN).append(" more\n");
            }
        }
        return q.append("""

                Propose the one node above them. Answer in exactly this form:
                TITLE: <a short name for what they are together>
                DESCRIPTION: <one or two sentences: what the merged node stands for>
                CRITERIA:
                - <a requirement the merged node must meet; one line each; the unified wording where the selected say the same>
                ACTION: UNIFY|ADAPT_ONE|KEEP_BOTH  (UNIFY: one of them serves for all; ADAPT_ONE: keep them, with a conversion
                where they meet; KEEP_BOTH: they are separate things under one heading)
                REASON: <one sentence>""").toString();
    }

    /** The proposal in {@code answer}, or null when it lacks a title or an action -- an answer that is not a proposal. */
    public static Proposal read(String answer) {
        if (answer == null) return null;
        Map<String, String> f = new LinkedHashMap<>();
        Matcher m = FIELD.matcher(answer);
        while (m.find()) f.putIfAbsent(m.group(1), m.group(2).strip());
        String title = f.get("TITLE");
        Matcher a = ACTION.matcher(f.getOrDefault("ACTION", ""));
        if (title == null || title.isBlank() || !a.find()) return null;
        List<String> criteria = new ArrayList<>();
        int start = answer.indexOf("CRITERIA:");
        if (start >= 0) {
            for (String line : answer.substring(start + "CRITERIA:".length()).split("\n")) {
                String t = line.strip();
                if (t.matches("(?i)(ACTION|REASON|TITLE|DESCRIPTION):.*")) break;
                if (t.startsWith("-") || t.startsWith("*")) {
                    String c = t.substring(1).strip();
                    if (!c.isEmpty()) criteria.add(c);
                }
            }
        }
        return new Proposal(title.replaceAll("^[\"'`]|[\"'`]$", ""), f.getOrDefault("DESCRIPTION", ""), criteria, a.group(1),
                f.getOrDefault("REASON", ""));
    }
}
