package com.walpro.autobuilder.core.merge.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * T4-EC stage 4, slice 4: a run made from a merge session reports its goal tree into that session, as it happens -- the
 * loop is one more author ("loop") beside structure, model and operator, so the canvas shows the run growing on the
 * merge (the user: "linkage, etc. of the nodes happens in real-time and can be viewed during generation").
 *
 * <p>Inert unless the target records a session ({@link MergeSessionService#SESSION_LINK}). A goal carried from a parent
 * is the session's node for it (its {@code mergedFrom} and {@code sourceGoalId}); the integration root becomes a super
 * node over the parents' roots; every other goal is a new GOAL node under its parent's. Status changes and a goal's
 * criteria arrive as metadata edits. Never throws into the loop: after repeated failures it stops mirroring and says so.
 */
@Slf4j
@Component
@com.walpro.autobuilder.core.checkpoint.StateHolder(value = com.walpro.autobuilder.core.checkpoint.StateBucket.ALREADY_PERSISTED,
        note = "what it mirrored lives in the merge session's own log; its fields cache that log and meta/merge-session.json"
                + " -- after a rewind the next observation compares the restored tree with them and writes the difference")
public class MergeSessionMirror {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_FAILURES = 5;

    private final FileService fileService;
    private final MergeSessionService sessions;

    private boolean initialised;
    private String sessionId;
    private int failures;
    private final Map<String, String> mirroredStatus = new HashMap<>();
    private final Map<String, Integer> mirroredCriteria = new HashMap<>();

    @Autowired
    public MergeSessionMirror(FileService fileService, MergeSessionService sessions) {
        this.fileService = fileService;
        this.sessions = sessions;
    }

    /** The goal tree as it stands; called where the loop records every goal's creation and status change. */
    public synchronized void observe(Collection<GoalEntity> goals, String rootId) {
        if (!enabled() || goals == null || goals.isEmpty()) return;
        try {
            MergeSession s = MergeSession.replay(sessions.log(sessionId));
            Map<String, GoalEntity> byId = new LinkedHashMap<>();
            for (GoalEntity g : goals) byId.put(g.getId(), g);
            Map<String, List<GoalEntity>> children = new HashMap<>();
            for (GoalEntity g : goals) children.computeIfAbsent(g.getParentId(), k -> new ArrayList<>()).add(g);
            GoalEntity root = rootId == null ? null : byId.get(rootId);
            if (root == null) return;
            java.util.Set<String> present = new java.util.HashSet<>(s.nodes().keySet());
            visit(s, present, root, null, children);
        } catch (Exception e) {
            if (++failures >= MAX_FAILURES) {
                log.warn("T4-EC: stopped mirroring the goal tree into merge session {} after {} failures: {}", sessionId,
                        failures, e.getMessage());
                sessionId = null;
            } else {
                log.debug("T4-EC: mirroring into merge session {} failed ({}): {}", sessionId, failures, e.getMessage());
            }
        }
    }

    private void visit(MergeSession s, java.util.Set<String> present, GoalEntity g, String parentNode,
                       Map<String, List<GoalEntity>> children) throws Exception {
        String node = nodeFor(s, g, parentNode == null, children);
        if (!present.contains(node)) {
            if (parentNode == null || present.contains(parentNode)) {
                if (create(s, g, node, parentNode, children)) present.add(node);
            }
        } else if (s.nodes().containsKey(node)) {
            update(s, g, node);
        }
        if (!present.contains(node)) return;   // nothing to hang its subtree on (not a merge child's tree)
        for (GoalEntity c : children.getOrDefault(g.getId(), List.of())) visit(s, present, c, node, children);
    }

    /** The session node a goal is: a parent's goal keeps its node; the root and every new goal get one of their own. */
    private String nodeFor(MergeSession s, GoalEntity g, boolean isRoot, Map<String, List<GoalEntity>> children) {
        Object from = g.getMetadata().get("mergedFrom"), source = g.getMetadata().get("sourceGoalId");
        if (from != null && source != null) {
            String carried = from + ":goal:" + source;
            if (s.nodes().containsKey(carried)) return carried;
        }
        return isRoot ? "loop:root" : "loop:goal:" + g.getId();
    }

    /** Creates the goal's node; false when it cannot be made (a root over fewer than two parents' trees). */
    private boolean create(MergeSession s, GoalEntity g, String node, String parentNode, Map<String, List<GoalEntity>> children)
            throws Exception {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("status", String.valueOf(g.getStatus()));
        meta.put("description", g.getDescription() == null ? "" : g.getDescription().strip());
        meta.put("goalId", g.getId());
        if (parentNode == null) {
            // The integration root: a super node over the parents' goal trees it was made over.
            List<String> over = new ArrayList<>();
            for (GoalEntity c : children.getOrDefault(g.getId(), List.of())) {
                String n = nodeFor(s, c, false, children);
                if (s.nodes().containsKey(n)) over.add(n);
            }
            if (over.size() < 2) return false;   // not a merge child: nothing to hang the run on
            apply("addSuper", Map.of("id", node, "title", title(g), "children", over, "metadata", meta));
            mirroredStatus.put(g.getId(), String.valueOf(g.getStatus()));
            return true;
        }
        apply("addNode", Map.of("id", node, "kind", "GOAL", "project", "loop", "title", title(g), "metadata", meta));
        apply("addEdge", Map.of("id", parentNode + "->" + node, "from", parentNode, "to", node, "kind", "STRUCTURE"));
        mirroredStatus.put(g.getId(), String.valueOf(g.getStatus()));
        return true;
    }

    private void update(MergeSession s, GoalEntity g, String node) throws Exception {
        String status = String.valueOf(g.getStatus());
        String known = mirroredStatus.containsKey(g.getId()) ? mirroredStatus.get(g.getId())
                : String.valueOf(s.nodes().get(node).metadata().get("status"));
        if (!Objects.equals(status, known)) {
            apply("setMeta", Map.of("target", node, "key", "status", "value", status));
        }
        mirroredStatus.put(g.getId(), status);
        Object criteria = g.getMetadata().get("acceptanceCriteria");
        if (criteria instanceof List<?> l && !l.isEmpty() && !Integer.valueOf(l.size()).equals(mirroredCriteria.get(g.getId()))
                && node.startsWith("loop:")) {
            apply("setMeta", Map.of("target", node, "key", "criteria", "value", List.copyOf(l)));
            mirroredCriteria.put(g.getId(), l.size());
        }
    }

    private void apply(String op, Map<String, Object> args) throws Exception {
        sessions.apply(sessionId, new MergeSession.Edit(op, "loop", null, args));
    }

    private boolean enabled() {
        if (!initialised) {
            initialised = true;
            try {
                if (fileService.exists(MergeSessionService.SESSION_LINK)) {
                    sessionId = MAPPER.readTree(fileService.readString(MergeSessionService.SESSION_LINK)).path("sessionId").asText(null);
                    if (sessionId != null) log.warn("T4-EC: this run mirrors its goal tree into merge session {}", sessionId);
                }
            } catch (Exception e) {
                log.warn("T4-EC: unreadable {} -- not mirroring: {}", MergeSessionService.SESSION_LINK, e.getMessage());
            }
        }
        return sessionId != null;
    }

    private static String title(GoalEntity g) {
        String d = g.getDescription() == null ? g.getId() : g.getDescription();
        String l = d.lines().map(String::strip).filter(x -> !x.isEmpty()).findFirst().orElse(g.getId());
        return l.length() > 140 ? l.substring(0, 137) + "..." : l;
    }
}
