package com.walpro.autobuilder.agent.evaluation.history;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.Optional;
import java.util.OptionalDouble;

@Primary
@Service
@RequiredArgsConstructor
@Transactional("transactionManager")
public class Neo4jScoreHistoryService implements ScoreHistoryService {

    private final Neo4jClient neo4j;

    @Override
    public void recordScores(String cycleId, Map<String, Double> scores) {
        if (cycleId == null || cycleId.isBlank() || scores == null || scores.isEmpty()) return;
        String now = Instant.now().toString();

        neo4j.query("MERGE (c:Cycle {id:$id}) ON CREATE SET c.created=$now")
                .bind(cycleId).to("id")
                .bind(now).to("now")
                .run();

        for (var e : scores.entrySet()) {
            String path = e.getKey();
            Double score = e.getValue();
            if (path == null || path.isBlank() || score == null) continue;

            String id = cycleId + "::" + path;

            neo4j.query("""
                    MATCH (c:Cycle {id:$cycleId})
                    MERGE (fs:FileScore {id:$id})
                    SET fs.path=$path, fs.score=$score, fs.cycleId=$cycleId, fs.created=$now
                    MERGE (c)-[:HAS_FILE_SCORE]->(fs)
                    """)
                    .bind(cycleId).to("cycleId")
                    .bind(id).to("id")
                    .bind(path).to("path")
                    .bind(score).to("score")
                    .bind(now).to("now")
                    .run();
        }
    }

    @Override
    public List<Double> getScoreHistory(String filePath) {
        if (filePath == null || filePath.isBlank()) return List.of();

        return neo4j.query("""
                MATCH (fs:FileScore {path:$path})
                RETURN fs.score AS score, fs.created AS created
                ORDER BY created ASC
                """)
                .bind(filePath).to("path")
                .fetch()
                .all()
                .stream()
                .map(m -> toDouble(m.get("score")))
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public Map<String, Double> computeRecentDeltas() {
        Map<String, List<Double>> all = getAllScoreHistories();
        Map<String, Double> out = new HashMap<>();
        for (var e : all.entrySet()) {
            List<Double> h = e.getValue();
            if (h != null && h.size() >= 2) {
                out.put(e.getKey(), h.get(h.size() - 1) - h.get(h.size() - 2));
            }
        }
        return out;
    }

    @Override
    public Map<String, List<Double>> getAllScoreHistories() {
        Collection<Map<String, Object>> rows = neo4j.query("""
                MATCH (fs:FileScore)
                RETURN fs.path AS path, fs.score AS score, fs.created AS created
                ORDER BY path ASC, created ASC
                """)
                .fetch()
                .all();

        Map<String, List<Double>> out = new LinkedHashMap<>();
        for (var r : rows) {
            String path = (String) r.get("path");
            Double score = toDouble(r.get("score"));
            if (path == null || path.isBlank() || score == null) continue;
            out.computeIfAbsent(path, k -> new ArrayList<>()).add(score);
        }
        return out;
    }

    @Override
    public void recordGlobalScore(String cycleName, double score) {
        if (cycleName == null || cycleName.isBlank()) return;
        String now = Instant.now().toString();

        neo4j.query("MERGE (c:Cycle {id:$id}) ON CREATE SET c.created=$now")
                .bind(cycleName).to("id")
                .bind(now).to("now")
                .run();

        neo4j.query("""
                MATCH (c:Cycle {id:$id})
                MERGE (gs:GlobalScore {id:$id})
                SET gs.score=$score, gs.created=$now
                MERGE (c)-[:HAS_GLOBAL_SCORE]->(gs)
                """)
                .bind(cycleName).to("id")
                .bind(score).to("score")
                .bind(now).to("now")
                .run();
    }

    @Override
    public Optional<Double> getLastGlobalScore() {
        // return "previous" if available (2nd latest), to mimic typical "lastScore" semantics
        List<Double> two = neo4j.query("""
                MATCH (gs:GlobalScore)
                RETURN gs.score AS score, gs.created AS created
                ORDER BY created DESC
                LIMIT 2
                """)
                .fetch()
                .all()
                .stream()
                .map(m -> toDouble(m.get("score")))
                .filter(Objects::nonNull)
                .toList();

        return two.size() < 2 ? Optional.empty() : Optional.of(two.get(1));
    }

    @Override
    public void recordGoalEvaluation(String cycleId, String goalId, double score) {
        recordGoalEvent(cycleId, goalId, score, "GoalEvaluation");
    }

    @Override
    public void recordGoalAcceptance(String cycleId, String goalId, double score) {
        recordGoalEvent(cycleId, goalId, score, "GoalAcceptance");
    }

    @Override
    public OptionalDouble getLastGoalEvaluation(String goalId) {
        return getLastGoalEvent(goalId, "GoalEvaluation");
    }

    @Override
    public OptionalDouble getLastAcceptedGoalScore(String goalId) {
        return getLastGoalEvent(goalId, "GoalAcceptance");
    }

    private void recordGoalEvent(String cycleId, String goalId, double score, String label) {
        if (goalId == null || goalId.isBlank()) return;

        String now = Instant.now().toString();

        // Prefer stable id when cycleId exists, so repeated calls in same cycle don't spam
        String id = (cycleId != null && !cycleId.isBlank())
                ? cycleId + "::" + goalId + "::" + label
                : goalId + "::" + label + "::" + now;

        // Ensure Goal exists
        neo4j.query("""
                MERGE (g:Goal {id:$goalId})
                ON CREATE SET g.created=$now
                """)
                .bind(goalId).to("goalId")
                .bind(now).to("now")
                .run();

        if (cycleId == null || cycleId.isBlank()) {
            // Old behavior: no cycle link possible
            neo4j.query("""
                    MATCH (g:Goal {id:$goalId})
                    MERGE (e:%s {id:$id})
                    SET e.goalId=$goalId, e.score=$score, e.created=$now
                    MERGE (g)-[:HAS_SCORE_EVENT]->(e)
                    """.formatted(label))
                    .bind(id).to("id")
                    .bind(goalId).to("goalId")
                    .bind(score).to("score")
                    .bind(now).to("now")
                    .run();
            return;
        }

        // Ensure Cycle exists
        neo4j.query("""
                MERGE (c:Cycle {id:$cycleId})
                ON CREATE SET c.created=$now
                """)
                .bind(cycleId).to("cycleId")
                .bind(now).to("now")
                .run();

        // Link Cycle -> Goal
        neo4j.query("""
                MATCH (c:Cycle {id:$cycleId})
                MATCH (g:Goal {id:$goalId})
                MERGE (c)-[:TARGETS_GOAL]->(g)
                """)
                .bind(cycleId).to("cycleId")
                .bind(goalId).to("goalId")
                .run();

        // Create/Merge event and attach to Cycle (NOT to Goal)
        neo4j.query("""
                MATCH (c:Cycle {id:$cycleId})
                MERGE (e:%s {id:$id})
                SET e.goalId=$goalId, e.cycleId=$cycleId, e.score=$score, e.created=$now
                MERGE (c)-[:HAS_SCORE_EVENT]->(e)
                """.formatted(label))
                .bind(cycleId).to("cycleId")
                .bind(goalId).to("goalId")
                .bind(id).to("id")
                .bind(score).to("score")
                .bind(now).to("now")
                .run();
    }

    private OptionalDouble getLastGoalEvent(String goalId, String label) {
        if (goalId == null || goalId.isBlank()) return OptionalDouble.empty();

        return neo4j.query("""
                MATCH (:Goal {id:$goalId})-[:HAS_SCORE_EVENT]->(e:%s)
                RETURN e.score AS score, e.created AS created
                ORDER BY created DESC
                LIMIT 1
                """.formatted(label))
                .bind(goalId).to("goalId")
                .fetch()
                .all()
                .stream()
                .findFirst()
                .map(m -> toDouble(m.get("score")))
                .filter(Objects::nonNull)
                .map(OptionalDouble::of)
                .orElse(OptionalDouble.empty());
    }

    private Double toDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception ignored) { return null; }
    }
}
