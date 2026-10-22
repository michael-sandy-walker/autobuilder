package com.walpro.autobuilder.core.merge.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.merge.ProjectMatchService;
import com.walpro.autobuilder.core.merge.ProjectMatcher;
import com.walpro.autobuilder.core.merge.ProjectMerge;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T4-EC stage 4, slice 1: merge sessions over two projects -- made from their snapshots (and, optionally, the stage-3
 * match list as proposed links), then edited one validated act at a time. Headless on purpose: the canvas (slice 2) and
 * a scripted operator both use this, so a session is testable by replay before any page draws it.
 */
@Service
public class MergeSessionService {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final MergeSessionStore store;

    @org.springframework.beans.factory.annotation.Autowired   // two constructors: this one is Spring's
    public MergeSessionService(@Value("${autobuilder.merge.sessions-dir:${user.home}/.autobuilder/merge-sessions}") String root) {
        this(new FileMergeSessionStore(Path.of(root)));
    }

    MergeSessionService(MergeSessionStore store) {
        this.store = store;
    }

    /** What a reader gets: the session's nodes and edges after {@code applied} edits. */
    public record Graph(String sessionId, int applied, List<MergeSession.Node> nodes, List<MergeSession.Edge> edges) {
    }

    /**
     * A new session over {@code first} and {@code second}: both projects -- goal trees, criteria, obligations, types and
     * their public operations -- written into the log as nodes and STRUCTURE edges by "structure"; and each pair of
     * {@code matchesJson} (MATCHES.json of stage 3), when given, as a proposed LINK by whoever decided it.
     */
    public synchronized Graph create(String sessionId, ProjectMerge.Parent first, ProjectMerge.Parent second, Path matchesJson)
            throws IOException {
        Instant now = Instant.now();
        List<MergeSession.Edit> edits = new ArrayList<>();
        for (ProjectMerge.Parent p : List.of(first, second)) project(p, now, edits);
        Set<String> ids = new LinkedHashSet<>();   // a criterion a goal lists twice is one edge
        edits.removeIf(e -> !ids.add(e.op() + ":" + e.args().get("id")));
        if (matchesJson != null) proposals(first.label(), second.label(), matchesJson, now, edits);
        MergeSession check = MergeSession.replay(edits);   // refuses an inconsistent start before anything is written
        store.create(sessionId);
        store.append(sessionId, edits);
        return publish(sessionId, check);
    }

    /** Applies one act -- refused, with the reason, when it does not fit the session as it stands. */
    public synchronized Graph apply(String sessionId, MergeSession.Edit edit) throws IOException {
        requireSession(sessionId);
        MergeSession s = MergeSession.replay(store.log(sessionId));
        MergeSession.Edit stamped = new MergeSession.Edit(edit.op(), edit.by(), edit.at() == null ? Instant.now() : edit.at(),
                edit.args());
        String why = s.refusal(stamped);
        if (why == null) why = duplicateLink(s, stamped);
        if (why != null) throw new IllegalArgumentException(why);
        s.apply(stamped);
        store.append(sessionId, List.of(stamped));
        return publish(sessionId, s);
    }

    /**
     * A second link between two nodes already linked (either way round) is refused as it arrives -- the user's session
     * held the same Amount-Money link three times. Checked here, not in the fold, so a log that already holds such
     * duplicates still replays.
     */
    private static String duplicateLink(MergeSession s, MergeSession.Edit e) {
        if (!"addEdge".equals(e.op()) || e.args() == null || !"LINK".equals(String.valueOf(e.args().get("kind")))) return null;
        String a = String.valueOf(e.args().get("from")), b = String.valueOf(e.args().get("to"));
        for (MergeSession.Edge x : s.edges().values()) {
            if (x.kind() == MergeSession.EdgeKind.LINK && ((x.from().equals(a) && x.to().equals(b)) || (x.from().equals(b) && x.to().equals(a)))) {
                return "these two nodes are already linked (" + x.id() + ")";
            }
        }
        return null;
    }

    public synchronized Graph graph(String sessionId) throws IOException {
        requireSession(sessionId);
        return view(sessionId, MergeSession.replay(store.log(sessionId)));
    }

    /** T4-EC slice 3: the model that answers a prompt operation; optional -- without it a refine is refused. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.client.LLMClient llmClient;

    /** What a refine produced: the graph, and the id of the super node the model proposed. */
    public record Refined(Graph graph, String nodeId) {
    }

    /**
     * T4-EC slice 3: "merge/refine selected nodes" -- the model is shown the selection and the request and proposes one
     * node above them, recorded at once as the model's super node, marked proposed (the user's choice of three). An
     * answer that is not a proposal records nothing.
     */
    public Refined refine(String sessionId, List<String> selection, String request) throws IOException {
        if (llmClient == null) throw new IllegalStateException("no model is configured to answer a prompt operation");
        return refine(sessionId, selection, request, this::ask);
    }

    synchronized Refined refine(String sessionId, List<String> selection, String request,
                                java.util.function.Function<String, String> model) throws IOException {
        requireSession(sessionId);
        MergeSession s = MergeSession.replay(store.log(sessionId));
        List<String> chosen = selection == null ? List.of() : selection.stream().distinct().toList();
        if (chosen.size() < 2 || !chosen.stream().allMatch(s.nodes()::containsKey)) {
            throw new IllegalArgumentException("select two or more nodes of the session");
        }
        String answer = model.apply(MergeRefine.question(s, chosen, request));
        MergeRefine.Proposal p = MergeRefine.read(answer);
        if (p == null) {
            throw new IllegalArgumentException("the model's answer was not a proposal (no title or action) -- nothing recorded");
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("status", "proposed");
        meta.put("description", p.description());
        meta.put("criteria", p.criteria());
        meta.put("action", p.action());
        meta.put("reason", p.reason());
        meta.put("request", request == null || request.isBlank() ? "merge/refine the selected nodes" : request.strip());
        String id = freshId(s, "super:model:");
        Graph g = apply(sessionId, new MergeSession.Edit("addSuper", "model", null,
                Map.of("id", id, "title", p.title(), "children", chosen, "metadata", meta)));
        return new Refined(g, id);
    }

    private String ask(String question) {
        try (var purpose = com.walpro.autobuilder.client.LlmCallContext.scopeIfUnscoped("merge-refine")) {
            var r = llmClient.ask(List.of(new com.walpro.autobuilder.client.dto.Message("system", MergeRefine.SYSTEM),
                    new com.walpro.autobuilder.client.dto.Message("user", question)));
            return r.getBody() == null ? null : r.getBody().getFirstContent();
        } catch (Exception e) {
            throw new IllegalStateException("the model did not answer: " + e.getMessage(), e);
        }
    }

    private static String freshId(MergeSession s, String prefix) {
        for (int i = 1; ; i++) if (!s.nodes().containsKey(prefix + i) && !s.edges().containsKey(prefix + i)) return prefix + i;
    }

    /** Where a child records the merge session it was made from -- the loop mirrors its goal tree into that session. */
    public static final String SESSION_LINK = "meta/merge-session.json";

    /**
     * T4-EC slice 4, the bridge: a confirmed session and the operator's integration goal become the child the loop runs
     * on -- {@code brought} integrated into {@code into} by {@link ProjectMerge#integrate} (the default layout unless one
     * is given), its obligations the operator's own lines followed by one per confirmed decision of the session
     * ({@link SessionDecisions}); the child records the session under {@value #SESSION_LINK}. Nothing in the session
     * changes. Refused when the session has no confirmed decision or the child directory holds files.
     */
    public synchronized ProjectMerge.Result integrate(String sessionId, ProjectMerge.Parent into, ProjectMerge.Parent brought,
                                                      ProjectMerge.Layout layout, String goal, List<String> obligations,
                                                      Path child) throws IOException {
        requireSession(sessionId);
        if (goal == null || goal.isBlank()) throw new IllegalArgumentException("an integration needs a goal");
        if (Files.isDirectory(child)) {
            try (var w = Files.list(child)) {
                if (w.findAny().isPresent()) throw new IllegalArgumentException(child + " is not empty");
            }
        }
        List<String> decisions = SessionDecisions.obligations(MergeSession.replay(store.log(sessionId)));
        if (decisions.isEmpty()) throw new IllegalArgumentException("the session has no confirmed decision to integrate from");
        List<String> lines = new ArrayList<>(obligations == null ? List.of() : obligations);
        lines.addAll(decisions);
        ProjectMerge.Result r = ProjectMerge.integrate(into, brought, layout == null ? ProjectMerge.Layout.MODULES : layout,
                goal, lines, Instant.now());
        ProjectMerge.writeChild(r, child);
        Files.writeString(child.resolve(SESSION_LINK), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                Map.of("sessionId", sessionId, "decisions", decisions)));
        return r;
    }

    public List<MergeSession.Edit> log(String sessionId) throws IOException {
        requireSession(sessionId);
        return store.log(sessionId);
    }

    private void requireSession(String sessionId) {
        if (!store.exists(sessionId)) throw new IllegalArgumentException("no such session: " + sessionId);
    }

    private Graph publish(String sessionId, MergeSession s) throws IOException {
        Graph g = view(sessionId, s);
        store.publish(sessionId, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(g));
        return g;
    }

    private static Graph view(String sessionId, MergeSession s) {
        return new Graph(sessionId, s.applied(), List.copyOf(s.nodes().values()), List.copyOf(s.edges().values()));
    }

    // --- materialising a project ---------------------------------------------------------------------------------

    private static void project(ProjectMerge.Parent p, Instant now, List<MergeSession.Edit> edits) throws IOException {
        String label = p.label();
        add(edits, now, "addNode", Map.of("id", label, "kind", "PROJECT", "project", label, "title", label,
                "metadata", Map.of("runId", p.runId())));
        JsonNode root = MAPPER.readTree(p.dir().resolve("meta/GoalStack.json").toFile()).path("goalTree");
        Set<String> criteriaSeen = new LinkedHashSet<>();
        goal(label, root, label, now, edits, criteriaSeen);
        Path seed = p.dir().resolve("bootstrap/seed.txt"), obl = p.dir().resolve("bootstrap/seed.obligations.txt");
        if (Files.isRegularFile(seed)) {
            List<String> criteria = ProjectMatchService.criteria(p);
            for (String c : criteria) {
                String id = criterionId(label, c);
                if (criteriaSeen.contains(id)) continue;
                criteriaSeen.add(id);
                add(edits, now, "addNode", Map.of("id", id, "kind", "CRITERION", "project", label, "title", c,
                        "metadata", Map.of("from", "obligations")));
                add(edits, now, "addEdge", Map.of("id", label + "->" + id, "from", label, "to", id, "kind", "STRUCTURE"));
            }
        }
        for (ProjectMatcher.Surface t : ProjectMatcher.surfaces(ProjectMatchService.main(p)).values()) {
            String typeId = label + ":type:" + t.name();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("kind", t.kind());
            meta.put("members", List.copyOf(t.members()));
            if (!t.components().isEmpty()) meta.put("components", t.components());
            add(edits, now, "addNode", Map.of("id", typeId, "kind", "TYPE", "project", label, "title", t.name(), "metadata", meta));
            add(edits, now, "addEdge", Map.of("id", label + "->" + typeId, "from", label, "to", typeId, "kind", "STRUCTURE"));
            for (Map.Entry<String, String> op : t.returns().entrySet()) {
                String opId = label + ":op:" + t.name() + "." + op.getKey();
                add(edits, now, "addNode", Map.of("id", opId, "kind", "OPERATION", "project", label,
                        "title", t.name() + "." + op.getKey(), "metadata", Map.of("returns", op.getValue())));
                add(edits, now, "addEdge", Map.of("id", typeId + "->" + opId, "from", typeId, "to", opId, "kind", "STRUCTURE"));
            }
        }
    }

    private static void goal(String label, JsonNode g, String parentId, Instant now, List<MergeSession.Edit> edits,
                             Set<String> criteriaSeen) {
        String id = label + ":goal:" + g.path("id").asText();
        String description = g.path("description").asText().strip();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("status", g.path("status").asText());
        meta.put("description", description);
        meta.put("sourceGoalId", g.path("id").asText());
        add(edits, now, "addNode", Map.of("id", id, "kind", "GOAL", "project", label, "title", firstLine(description), "metadata", meta));
        add(edits, now, "addEdge", Map.of("id", parentId + "->" + id, "from", parentId, "to", id, "kind", "STRUCTURE"));
        for (JsonNode c : g.path("metadata").path("acceptanceCriteria")) {
            String cid = criterionId(label, c.asText().strip());
            if (criteriaSeen.add(cid)) {
                add(edits, now, "addNode", Map.of("id", cid, "kind", "CRITERION", "project", label, "title", c.asText().strip()));
            }
            add(edits, now, "addEdge", Map.of("id", id + "->" + cid, "from", id, "to", cid, "kind", "STRUCTURE"));
        }
        for (JsonNode s : g.path("subGoals")) goal(label, s, id, now, edits, criteriaSeen);
    }

    // --- the matcher's proposals --------------------------------------------------------------------------------

    private static void proposals(String labelA, String labelB, Path matchesJson, Instant now, List<MergeSession.Edit> edits)
            throws IOException {
        MergeSession sofar = MergeSession.replay(edits);
        int n = 0;
        for (JsonNode m : MAPPER.readTree(matchesJson.toFile()).path("matches")) {
            String kind = m.path("kind").asText();
            String a = nodeFor(labelA, kind, m.path("a").asText()), b = nodeFor(labelB, kind, m.path("b").asText());
            if (!sofar.nodes().containsKey(a) || !sofar.nodes().containsKey(b)) continue;   // a pair the session has no node for
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("status", "proposed");
            meta.put("structure", m.path("level").asText());
            for (String k : List.of("verdict", "action", "reason")) if (!m.path(k).isNull()) meta.put(k, m.path(k).asText());
            String by = m.path("source").asText("structure");
            edits.add(new MergeSession.Edit("addEdge", by.equals("none") ? "structure" : by, now,
                    Map.of("id", "link:proposed:" + (++n), "from", a, "to", b, "kind", "LINK", "metadata", meta)));
        }
    }

    private static String nodeFor(String label, String kind, String text) {
        return switch (kind) {
            case "TYPE" -> label + ":type:" + text;
            case "OPERATION" -> label + ":op:" + text;
            case "CRITERION" -> criterionId(label, text.strip());
            default -> label + ":?:" + text;
        };
    }

    private static String criterionId(String label, String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return label + ":criterion:" + HexFormat.of().formatHex(d).substring(0, 12);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String firstLine(String s) {
        String l = s.lines().map(String::strip).filter(x -> !x.isEmpty()).findFirst().orElse("");
        return l.length() > 140 ? l.substring(0, 137) + "..." : l;
    }

    private static void add(List<MergeSession.Edit> edits, Instant at, String op, Map<String, Object> args) {
        edits.add(new MergeSession.Edit(op, "structure", at, args));
    }
}
