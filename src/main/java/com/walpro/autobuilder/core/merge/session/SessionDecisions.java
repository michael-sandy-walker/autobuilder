package com.walpro.autobuilder.core.merge.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T4-EC stage 4, slice 4: what a merge session DECIDED, in words an integration goal can carry -- one obligation per
 * confirmed decision. Only what the operator decided counts (the machine proposes, the operator decides): a proposed
 * link or a proposed super node is left out until it is confirmed; what the operator made themselves is decided.
 */
public final class SessionDecisions {

    /** Metadata that is bookkeeping or already said by the sentence itself, not a parameter of the decision. */
    private static final Set<String> NOT_PARAMETERS = Set.of("by", "at", "lastEditedBy", "status", "structure", "verdict",
            "action", "reason", "request", "description", "criteria", "note");

    private SessionDecisions() {
    }

    /** One obligation per confirmed link and confirmed super node (a super node's criteria follow it, one per line). */
    public static List<String> obligations(MergeSession s) {
        List<String> out = new ArrayList<>();
        for (MergeSession.Edge e : s.edges().values()) {
            if (e.kind() != MergeSession.EdgeKind.LINK || !confirmed(e.metadata())) continue;
            MergeSession.Node a = s.nodes().get(e.from()), b = s.nodes().get(e.to());
            if (a == null || b == null) continue;
            String pair = name(a) + " and " + name(b);
            String action = String.valueOf(e.metadata().getOrDefault("action", ""));
            String sentence = switch (action) {
                case "UNIFY" -> pair + " are the same thing: one of them serves for both";
                case "ADAPT_ONE" -> pair + " are one concept in two shapes: keep both, with a conversion where they meet";
                case "KEEP_BOTH" -> pair + " are separate things: keep both as they are";
                default -> pair + " belong together";
            };
            out.add(sentence + parameters(e.metadata()) + note(e.metadata()));
        }
        for (MergeSession.Node n : s.nodes().values()) {
            if (n.kind() != MergeSession.NodeKind.SUPER || !confirmed(n.metadata())) continue;
            List<String> children = new ArrayList<>();
            for (MergeSession.Edge e : s.edges().values()) {
                if (e.kind() == MergeSession.EdgeKind.SUPER_OF && e.from().equals(n.id()) && s.nodes().containsKey(e.to())) {
                    children.add(name(s.nodes().get(e.to())));
                }
            }
            Object description = n.metadata().get("description");
            out.add(String.join(" and ", children) + " are merged as " + n.title()
                    + (description == null || String.valueOf(description).isBlank() ? "" : ": " + description)
                    + parameters(n.metadata()) + note(n.metadata()));
            Object criteria = n.metadata().get("criteria");
            if (criteria instanceof List<?> l) l.forEach(c -> out.add(n.title() + ": " + c));
        }
        return List.copyOf(new java.util.LinkedHashSet<>(out));   // the same decision drawn twice is one obligation
    }

    /** Confirmed by status, or made by the operator and never marked otherwise -- what the operator made is a decision. */
    private static boolean confirmed(Map<String, Object> m) {
        Object status = m.get("status");
        return "confirmed".equals(status) || (status == null && "operator".equals(m.get("by")));
    }

    private static String name(MergeSession.Node n) {
        return n.project() == null ? n.title() : n.title() + " (" + n.project() + ")";
    }

    private static String parameters(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> {
            if (!NOT_PARAMETERS.contains(k) && v != null && !String.valueOf(v).isBlank()) {
                sb.append(sb.isEmpty() ? " (" : ", ").append(k).append(": ").append(v);
            }
        });
        return sb.isEmpty() ? "" : sb.append(")").toString();
    }

    private static String note(Map<String, Object> m) {
        Object note = m.get("note");
        return note == null || String.valueOf(note).isBlank() ? "" : " -- " + note;
    }
}
