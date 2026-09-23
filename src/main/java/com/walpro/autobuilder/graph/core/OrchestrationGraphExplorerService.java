package com.walpro.autobuilder.graph.core;

import com.walpro.autobuilder.graph.HistoryRead;
import com.walpro.autobuilder.graph.HistorySpan;
import com.walpro.autobuilder.graph.HistoryUse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * T4-CM slice 1: read-only query surface over the LIVE orchestration graph — the in-app
 * successor to the offline {@code scripts/dump_graph.sh} forensic dump. Browses whatever
 * the graph actually contains (goals, actions, cycles, merges, criteria …) by label and by
 * the T4-CJ run/lineage stamps, and drills from any node to its relationship neighborhood.
 *
 * <p>Deliberately generic: no per-label DTOs — the graph's own labels and properties ARE
 * the schema, mirrored to the client the way the dump format mirrors them to a corpus.
 * Purely additive reads; no writes, no schema assumptions beyond the stamp property names.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrchestrationGraphExplorerService {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    /** Cypher labels cannot be bound as parameters — restrict to plain identifiers instead. */
    private static final Pattern SAFE_LABEL = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Neo4jClient neo4jClient;

    public record LabelCount(String label, long count) {}
    public record StampCount(String id, long count) {}
    public record GraphOverview(List<LabelCount> labels, List<StampCount> lineages, List<StampCount> runs) {}
    public record GraphNodeView(String elementId, List<String> labels, Map<String, Object> properties) {}
    public record NeighborView(String relationshipType, String direction, GraphNodeView node) {}
    public record NodeNeighborhood(GraphNodeView node, List<NeighborView> neighbors) {}

    /** T5-BM: one kind of thing that happened to a goal, and how often. */
    public record GoalActivityKind(String type, String status, long count) {}

    /**
     * T5-BM: what was DONE about a goal, summarised.
     *
     * <p><b>A summary rather than a chronology, and the graph is what decided that.</b> The busiest
     * goal in the store carries <b>216</b> actions — 82 merges, 32 deliberations, 26 evolutions —
     * so rendering them in order around a node would be 216 items at exactly the moment a reader
     * most needs an answer. The same goal's type-and-status breakdown is eleven rows and says what
     * happened at a glance. The dense case is the normal case here, not the exception.
     */
    public record GoalActivity(String goalId, long total, List<GoalActivityKind> kinds,
                               String firstAt, String lastAt) {}

    /** The filter vocabulary: which labels, lineages and runs exist right now, with counts. */
    @HistoryRead(span = HistorySpan.ALL_LINEAGES, use = HistoryUse.ADVISORY,
            note = "The operator's graph browser. It spans every retained lineage on purpose: it is how history is inspected, and nothing the loop decides reads it.")
    public GraphOverview overview() {
        List<LabelCount> labels = neo4jClient.query("""
                        MATCH (n) UNWIND labels(n) AS label
                        RETURN label, count(*) AS count ORDER BY count DESC, label
                        """)
                .fetch().all().stream()
                .map(row -> new LabelCount((String) row.get("label"), asLong(row.get("count"))))
                .toList();
        return new GraphOverview(labels, stampCounts("lineageId"), stampCounts("runId"));
    }

    @HistoryRead(span = HistorySpan.ALL_LINEAGES, use = HistoryUse.ADVISORY,
            note = "The operator's graph browser: per-stamp node counts across every retained lineage.")
    private List<StampCount> stampCounts(String property) {
        return neo4jClient.query("""
                        MATCH (n) WHERE n[$property] IS NOT NULL
                        RETURN n[$property] AS id, count(*) AS count ORDER BY count DESC LIMIT 100
                        """)
                .bind(property).to("property")
                .fetch().all().stream()
                .map(row -> new StampCount(String.valueOf(row.get("id")), asLong(row.get("count"))))
                .toList();
    }

    /**
     * Nodes matching the optional label / lineage / run filters. Filters compose with AND;
     * all of them absent browses the whole graph (bounded by {@code limit}).
     */
    @HistoryRead(span = HistorySpan.ALL_LINEAGES, use = HistoryUse.ADVISORY,
            note = "The operator's graph browser: node listing across every retained lineage.")
    public List<GraphNodeView> nodes(String label, String lineageId, String runId, Integer limit) {
        String match = "MATCH (n" + labelFragment(label) + ")";
        StringBuilder where = new StringBuilder();
        if (lineageId != null && !lineageId.isBlank()) where.append(" n.lineageId = $lineageId ");
        if (runId != null && !runId.isBlank()) {
            if (!where.isEmpty()) where.append("AND");
            where.append(" n.runId = $runId ");
        }
        String cypher = match + (where.isEmpty() ? "" : " WHERE" + where)
                + " RETURN elementId(n) AS elementId, labels(n) AS labels, properties(n) AS props"
                + " LIMIT $limit";

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("limit", clampLimit(limit));
        if (lineageId != null && !lineageId.isBlank()) params.put("lineageId", lineageId);
        if (runId != null && !runId.isBlank()) params.put("runId", runId);
        return neo4jClient.query(cypher).bindAll(params).fetch().all().stream()
                .map(OrchestrationGraphExplorerService::toNodeView).toList();
    }

    /** One node plus its full relationship neighborhood (both directions, bounded). */
    @HistoryRead(span = HistorySpan.ALL_LINEAGES, use = HistoryUse.ADVISORY,
            note = "The operator's graph browser: the neighbourhood of a node the operator picked.")
    public Optional<NodeNeighborhood> neighbors(String elementId) {
        Optional<GraphNodeView> node = neo4jClient.query("""
                        MATCH (n) WHERE elementId(n) = $id
                        RETURN elementId(n) AS elementId, labels(n) AS labels, properties(n) AS props
                        """)
                .bind(elementId).to("id")
                .fetch().all().stream()
                .map(OrchestrationGraphExplorerService::toNodeView)
                .findFirst();
        if (node.isEmpty()) return Optional.empty();

        List<NeighborView> neighbors = new ArrayList<>();
        neo4jClient.query("""
                        MATCH (n)-[r]->(m) WHERE elementId(n) = $id
                        RETURN type(r) AS type, elementId(m) AS elementId, labels(m) AS labels,
                               properties(m) AS props LIMIT $limit
                        """)
                .bind(elementId).to("id").bind(MAX_LIMIT).to("limit")
                .fetch().all()
                .forEach(row -> neighbors.add(new NeighborView((String) row.get("type"), "outgoing", toNodeView(row))));
        neo4jClient.query("""
                        MATCH (n)<-[r]-(m) WHERE elementId(n) = $id
                        RETURN type(r) AS type, elementId(m) AS elementId, labels(m) AS labels,
                               properties(m) AS props LIMIT $limit
                        """)
                .bind(elementId).to("id").bind(MAX_LIMIT).to("limit")
                .fetch().all()
                .forEach(row -> neighbors.add(new NeighborView((String) row.get("type"), "incoming", toNodeView(row))));
        return Optional.of(new NodeNeighborhood(node.get(), neighbors));
    }


    /**
     * T5-BM: the actions taken on one goal, grouped by what they were.
     *
     * <p>Answers "what happened to this subgoal?" without a global filter change — the question the
     * explorer previously sent a reader to {@code autobuilder.log} for. Empty when the goal has no
     * actions, which is itself an answer: nothing was done about it.
     */
    @HistoryRead(span = HistorySpan.KEY_OWNER, use = HistoryUse.ADVISORY,
            note = "The operator's view of what was done about one goal.")
    public Optional<GoalActivity> goalActivity(String goalId) {
        if (goalId == null || goalId.isBlank()) return Optional.empty();
        List<GoalActivityKind> kinds = new ArrayList<>();
        neo4jClient.query("""
                        MATCH (a:Action)-[:TARGETS_GOAL]->(g:Goal {id: $goalId})
                        RETURN a.type AS type, a.status AS status, count(*) AS n
                        ORDER BY n DESC, type ASC
                        """)
                .bind(goalId).to("goalId")
                .fetch().all()
                .forEach(row -> kinds.add(new GoalActivityKind(
                        (String) row.get("type"), (String) row.get("status"),
                        ((Number) row.getOrDefault("n", 0L)).longValue())));
        if (kinds.isEmpty()) return Optional.empty();

        Map<String, Object> span = neo4jClient.query("""
                        MATCH (a:Action)-[:TARGETS_GOAL]->(g:Goal {id: $goalId})
                        RETURN count(a) AS total, min(a.timestamp) AS firstAt, max(a.timestamp) AS lastAt
                        """)
                .bind(goalId).to("goalId")
                .fetch().one().orElse(Map.of());
        return Optional.of(new GoalActivity(goalId,
                ((Number) span.getOrDefault("total", 0L)).longValue(), List.copyOf(kinds),
                String.valueOf(span.get("firstAt")), String.valueOf(span.get("lastAt"))));
    }

    // ======================================================================================
    // Row mapping and input handling (unit-tested)
    // ======================================================================================

    @SuppressWarnings("unchecked")
    private static GraphNodeView toNodeView(Map<String, Object> row) {
        List<String> labels = ((List<Object>) row.getOrDefault("labels", List.of())).stream()
                .map(String::valueOf).toList();
        Map<String, Object> rawProps = (Map<String, Object>) row.getOrDefault("props", Map.of());
        Map<String, Object> props = new LinkedHashMap<>();
        rawProps.forEach((k, v) -> props.put(k, jsonSafe(v)));
        return new GraphNodeView((String) row.get("elementId"), labels, props);
    }

    /**
     * Properties pass through as-is when plainly JSON-representable and render as strings
     * otherwise (temporal and other driver types) — forensic fidelity over type fidelity,
     * same trade the dump format makes.
     */
    static Object jsonSafe(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(OrchestrationGraphExplorerService::jsonSafe).toList();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((k, v) -> safe.put(String.valueOf(k), jsonSafe(v)));
            return safe;
        }
        return String.valueOf(value);
    }

    /** Returns {@code ""} for no label filter or {@code ":Label"} after identifier validation. */
    static String labelFragment(String label) {
        if (label == null || label.isBlank()) return "";
        if (!SAFE_LABEL.matcher(label).matches()) {
            throw new IllegalArgumentException("label must be a plain identifier, got: " + label);
        }
        return ":" + label;
    }

    static int clampLimit(Integer limit) {
        if (limit == null) return DEFAULT_LIMIT;
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
