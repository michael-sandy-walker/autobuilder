package com.walpro.autobuilder.agent.evaluation.history;

import com.walpro.autobuilder.graph.HistoryRead;
import com.walpro.autobuilder.graph.HistorySpan;
import com.walpro.autobuilder.graph.HistoryUse;
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
    // T4-CJ slice 2: score events are per-run facts; stamping them is what lets a
    // lineage's history be told apart from the run currently executing.
    private final com.walpro.autobuilder.core.init.RunIdentityService runIdentity;

    /**
     * T1-FS: the run whose scores may be read, or null when the run has no identity yet.
     *
     * <p>The writers have always STAMPED {@code runId}/{@code lineageId} — the stamp is what tells
     * a lineage's history apart from the run currently executing — and every reader dropped it.
     * Until {@code T1-D} (2026-07-26) a reset wiped the graph, so an unscoped read was
     * accidentally correct; making lineages durable turned the same code into a leak. Measured on
     * 2026-08-23: 9,216 scores from 71 runs reaching one prompt, 269 file paths of which 259 did
     * not exist, and the model calling a 10-file project "severely fragmented" as a result.
     *
     * <p><b>Scoped to the RUN, not the lineage, deliberately.</b> A resumed run's own prior scores
     * describe the same tree and could be argued for — but {@code T4-CD} already holds that loaded
     * state is hypothesis rather than fact and drops banked criteria reads on resume, so carrying
     * prior-run SCORES across the boundary would contradict the contract the rest of resume obeys.
     *
     * <p><b>Null means EMPTY, never everything.</b> Falling back to an unscoped read on a missing
     * run id would restore exactly the defect this fixes, and silently. Having no history is safe;
     * having a stranger's is not.
     */
    private String currentRunId() {
        return runIdentity == null ? null : runIdentity.runId();
    }

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
                    SET fs.path=$path, fs.score=$score, fs.cycleId=$cycleId, fs.created=$now,
                        fs.lineageId=$lineageId, fs.runId=$runId
                    MERGE (c)-[:HAS_FILE_SCORE]->(fs)
                    """)
                    .bind(cycleId).to("cycleId")
                    .bind(id).to("id")
                    .bind(path).to("path")
                    .bind(score).to("score")
                    .bind(now).to("now")
                    .bind(runIdentity.lineageId()).to("lineageId")
                    .bind(runIdentity.runId()).to("runId")
                    .run();
        }
    }

    @HistoryRead(span = HistorySpan.RUN, use = HistoryUse.ADVISORY,
            note = "One file's score series in this run; feeds computeRecentDeltas, carried on the scoring result as context. A previous run's series describes a different tree.")
    @Override
    public List<Double> getScoreHistory(String filePath) {
        if (filePath == null || filePath.isBlank()) return List.of();

        String runId = currentRunId();
        if (runId == null) return List.of();

        return neo4j.query("""
                MATCH (fs:FileScore {path:$path, runId:$runId})
                RETURN fs.score AS score, fs.created AS created
                ORDER BY created ASC
                """)
                .bind(filePath).to("path")
                .bind(runId).to("runId")
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

    @HistoryRead(span = HistorySpan.RUN, use = HistoryUse.ADVISORY,
            note = "ScoringService carries this run's per-file history on the scoring result as context; nothing decides on it.")
    @Override
    public Map<String, List<Double>> getAllScoreHistories() {
        String runId = currentRunId();
        if (runId == null) return Map.of();

        Collection<Map<String, Object>> rows = neo4j.query("""
                MATCH (fs:FileScore {runId:$runId})
                RETURN fs.path AS path, fs.score AS score, fs.created AS created
                ORDER BY path ASC, created ASC
                """)
                .bind(runId).to("runId")
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
                SET gs.score=$score, gs.created=$now,
                        gs.lineageId=$lineageId, gs.runId=$runId
                MERGE (c)-[:HAS_GLOBAL_SCORE]->(gs)
                """)
                .bind(cycleName).to("id")
                .bind(score).to("score")
                .bind(now).to("now")
                .bind(runIdentity.lineageId()).to("lineageId")
                    .bind(runIdentity.runId()).to("runId")
                    .run();
    }

    @HistoryRead(span = HistorySpan.RUN, use = HistoryUse.BINDING,
            note = "The baseline a regression is measured from (DeterministicScoreHeuristic, EvaluationAgent's fallback baseline). A previous run's last score describes a different tree, so it is this run's only.")
    @Override
    public Optional<Double> getLastGlobalScore() {
        // return "previous" if available (2nd latest), to mimic typical "lastScore" semantics
        String runId = currentRunId();
        if (runId == null) return Optional.empty();

        List<Double> two = neo4j.query("""
                MATCH (gs:GlobalScore {runId:$runId})
                RETURN gs.score AS score, gs.created AS created
                ORDER BY created DESC
                LIMIT 2
                """)
                .bind(runId).to("runId")
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
                ON CREATE SET g.created=$now, g.lineageId=$lineageId, g.createdInRunId=$runId
                """)
                .bind(goalId).to("goalId")
                .bind(now).to("now")
                .bind(runIdentity.lineageId()).to("lineageId")
                    .bind(runIdentity.runId()).to("runId")
                    .run();

        if (cycleId == null || cycleId.isBlank()) {
            // Old behavior: no cycle link possible
            neo4j.query("""
                    MATCH (g:Goal {id:$goalId})
                    MERGE (e:%s {id:$id})
                    SET e.goalId=$goalId, e.score=$score, e.created=$now,
                            e.lineageId=$lineageId, e.runId=$runId
                    MERGE (g)-[:HAS_SCORE_EVENT]->(e)
                    """.formatted(label))
                    .bind(id).to("id")
                    .bind(goalId).to("goalId")
                    .bind(score).to("score")
                    .bind(now).to("now")
                    .bind(runIdentity.lineageId()).to("lineageId")
                    .bind(runIdentity.runId()).to("runId")
                    .run();
            return;
        }

        // Ensure Cycle exists
        neo4j.query("""
                MERGE (c:Cycle {id:$cycleId})
                ON CREATE SET c.created=$now, c.lineageId=$lineageId, c.runId=$runId
                """)
                .bind(cycleId).to("cycleId")
                .bind(now).to("now")
                .bind(runIdentity.lineageId()).to("lineageId")
                    .bind(runIdentity.runId()).to("runId")
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
                SET e.goalId=$goalId, e.cycleId=$cycleId, e.score=$score, e.created=$now,
                        e.lineageId=$lineageId, e.runId=$runId
                MERGE (c)-[:HAS_SCORE_EVENT]->(e)
                """.formatted(label))
                .bind(cycleId).to("cycleId")
                .bind(goalId).to("goalId")
                .bind(id).to("id")
                .bind(score).to("score")
                .bind(now).to("now")
                .bind(runIdentity.lineageId()).to("lineageId")
                    .bind(runIdentity.runId()).to("runId")
                    .run();
    }

    @HistoryRead(span = HistorySpan.KEY_OWNER, use = HistoryUse.BINDING,
            note = "The goal's own last evaluation/acceptance score, the within-goal regression baseline. A goal id belongs to one lineage, and a resumed lineage continuing its goal's baseline is intended.")
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
