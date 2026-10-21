package com.walpro.autobuilder.core.merge.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * T4-EC stage 4, slice 1: a merge session -- the graph an operator merges on, as the fold of an append-only edit log.
 *
 * <p>The user's architecture (2026-10-21): a canvas of graphs, acted on by DIRECT EDITING (draw, remove, re-point links;
 * edit any node's or link's metadata) and by PROMPT OPERATIONS on a selection ("merge/refine selected nodes" -> a SUPER
 * NODE above them, the originals its children). Merging builds upward: nothing is overwritten, removing a super node
 * undoes it, provenance is the structure. Every act is recorded with who did it -- structure, model or operator.
 *
 * <p>The log is the authority and its fold is pure: a session's first edits materialise both projects as explicit nodes
 * and edges, so replaying never reads a project again. Stored as files first (the user's choice of three, with a graph
 * store -- Neo4j, or files projected into it -- kept in view: nodes, edges and typed edits map one to one).
 */
public final class MergeSession {

    /** What a node stands for. */
    public enum NodeKind { PROJECT, GOAL, CRITERION, TYPE, OPERATION, SUPER }

    /**
     * What an edge is. STRUCTURE edges come from a project (a goal's subgoal, a goal's criterion, a type's operation) and
     * are never removed; LINK edges join nodes across or within projects and are the operator's to draw and remove;
     * SUPER_OF joins a super node to each node it was made from.
     */
    public enum EdgeKind { STRUCTURE, LINK, SUPER_OF }

    public record Node(String id, NodeKind kind, String project, String title, Map<String, Object> metadata) {
    }

    public record Edge(String id, String from, String to, EdgeKind kind, Map<String, Object> metadata) {
    }

    /**
     * One act on the session. {@code op}: addNode, addEdge, removeEdge, setMeta, addSuper, removeSuper. {@code by}: who did
     * it -- structure, model or operator.
     */
    public record Edit(String op, String by, Instant at, Map<String, Object> args) {
    }

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, Edge> edges = new LinkedHashMap<>();
    private int applied;

    public Map<String, Node> nodes() {
        return nodes;
    }

    public Map<String, Edge> edges() {
        return edges;
    }

    public int applied() {
        return applied;
    }

    /** The session the log describes. */
    public static MergeSession replay(List<Edit> log) {
        MergeSession s = new MergeSession();
        for (Edit e : log) s.apply(e);
        return s;
    }

    /** Why {@code e} cannot be applied to this session, or null when it can. Nothing is changed. */
    public String refusal(Edit e) {
        Map<String, Object> a = e.args() == null ? Map.of() : e.args();
        if (e.by() == null || e.by().isBlank()) return "an edit says who made it";
        return switch (e.op() == null ? "" : e.op()) {
            case "addNode" -> {
                String id = str(a, "id");
                if (id == null || nodes.containsKey(id) || edges.containsKey(id)) yield "a node needs a new id";
                yield kindOf(NodeKind.class, a.get("kind")) == null || kindOf(NodeKind.class, a.get("kind")) == NodeKind.SUPER
                        ? "a node needs a kind (a super node is made with addSuper)" : null;
            }
            case "addEdge" -> {
                String id = str(a, "id");
                if (id == null || nodes.containsKey(id) || edges.containsKey(id)) yield "an edge needs a new id";
                if (!nodes.containsKey(str(a, "from")) || !nodes.containsKey(str(a, "to"))) yield "an edge joins two nodes of the session";
                if (Objects.equals(str(a, "from"), str(a, "to"))) yield "an edge joins two different nodes";
                EdgeKind k = kindOf(EdgeKind.class, a.get("kind"));
                yield k == null || k == EdgeKind.SUPER_OF ? "an edge needs a kind (SUPER_OF comes with addSuper)" : null;
            }
            case "removeEdge" -> {
                Edge edge = edges.get(str(a, "id"));
                yield edge == null ? "no such edge" : edge.kind() != EdgeKind.LINK
                        ? "only a link is removed; a project's structure stays, and a super node is removed whole" : null;
            }
            case "setMeta" -> {
                String target = str(a, "target");
                if (!nodes.containsKey(target) && !edges.containsKey(target)) yield "no such node or edge";
                yield str(a, "key") == null ? "metadata needs a key" : null;
            }
            case "addSuper" -> {
                String id = str(a, "id");
                if (id == null || nodes.containsKey(id) || edges.containsKey(id)) yield "a super node needs a new id";
                List<String> children = strings(a.get("children"));
                if (children.size() < 2) yield "a super node is made over at least two nodes";
                yield children.stream().allMatch(nodes::containsKey) ? null : "a super node is made over nodes of the session";
            }
            case "removeSuper" -> {
                Node n = nodes.get(str(a, "id"));
                if (n == null || n.kind() != NodeKind.SUPER) yield "no such super node";
                yield edges.values().stream().anyMatch(x -> x.kind() == EdgeKind.SUPER_OF && x.to().equals(n.id()))
                        ? "a super node that is itself merged into another is removed after that one" : null;
            }
            default -> "unknown edit: " + e.op();
        };
    }

    /** Applies {@code e}; an edit that cannot be applied throws (the log holds only edits that could). */
    public void apply(Edit e) {
        String why = refusal(e);
        if (why != null) throw new IllegalArgumentException(why);
        Map<String, Object> a = e.args() == null ? Map.of() : e.args();
        switch (e.op()) {
            case "addNode" -> nodes.put(str(a, "id"), new Node(str(a, "id"), kindOf(NodeKind.class, a.get("kind")),
                    str(a, "project"), str(a, "title"), meta(a.get("metadata"), e)));
            case "addEdge" -> edges.put(str(a, "id"), new Edge(str(a, "id"), str(a, "from"), str(a, "to"),
                    kindOf(EdgeKind.class, a.get("kind")), meta(a.get("metadata"), e)));
            case "removeEdge" -> edges.remove(str(a, "id"));
            case "setMeta" -> {
                String target = str(a, "target");
                Map<String, Object> m = nodes.containsKey(target) ? nodes.get(target).metadata() : edges.get(target).metadata();
                Object value = a.get("value");
                if (value == null) m.remove(str(a, "key"));
                else m.put(str(a, "key"), value);
                m.put("lastEditedBy", e.by());
            }
            case "addSuper" -> {
                String id = str(a, "id");
                nodes.put(id, new Node(id, NodeKind.SUPER, null, str(a, "title"), meta(a.get("metadata"), e)));
                for (String child : strings(a.get("children"))) {
                    String edgeId = id + "->" + child;
                    edges.put(edgeId, new Edge(edgeId, id, child, EdgeKind.SUPER_OF, meta(Map.of(), e)));
                }
            }
            case "removeSuper" -> {
                String id = str(a, "id");
                nodes.remove(id);
                edges.values().removeIf(x -> x.from().equals(id) || x.to().equals(id));
            }
            default -> throw new IllegalArgumentException("unknown edit: " + e.op());
        }
        applied++;
    }

    private static Map<String, Object> meta(Object given, Edit e) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (given instanceof Map<?, ?> g) g.forEach((k, v) -> m.put(String.valueOf(k), v));
        m.putIfAbsent("by", e.by());
        if (e.at() != null) m.putIfAbsent("at", e.at().toString());
        return m;
    }

    static String str(Map<String, Object> a, String key) {
        Object v = a.get(key);
        return v == null ? null : String.valueOf(v);
    }

    static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) l.forEach(x -> out.add(String.valueOf(x)));
        return out;
    }

    private static <E extends Enum<E>> E kindOf(Class<E> type, Object v) {
        if (v == null) return null;
        try {
            return Enum.valueOf(type, String.valueOf(v));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
