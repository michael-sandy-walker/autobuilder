//package com.walpro.autobuilder.graph.diagnostic;
//
//import lombok.RequiredArgsConstructor;
//import org.springframework.data.neo4j.core.Neo4jClient;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.util.*;
//
//@Service
//@Transactional("transactionManager")
//@RequiredArgsConstructor
//public class DiagnosticScanService {
//
//    private final Neo4jClient neo4j;
//    private final Neo4jDiagnosticGraphService neo4jDiagnosticGraphService;
//
//    public DiagnosticScanResult scan(String goalId, String subGoalId, String attemptId, int limit) {
//        if (attemptId == null || attemptId.isBlank()) return DiagnosticScanResult.empty();
//
//        String scopeGoalId = (subGoalId != null && !subGoalId.isBlank()) ? subGoalId : goalId;
//        int lim = Math.max(1, Math.min(limit, 30));
//
//        List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) neo4j.query("""
//            MATCH (m:Diagnostic {enabled:true})
//            RETURN m.id AS id,
//                   m.name AS name,
//                   coalesce(m.severity, 'MEDIUM') AS severity,
//                   coalesce(m.weight, 1.0) AS weight,
//                   m.cypher AS cypher
//            ORDER BY coalesce(m.weight, 1.0) DESC, m.name ASC
//            """)
//                .fetch()
//                .all();
//
//        if (diagnostics == null || diagnostics.isEmpty()) return DiagnosticScanResult.empty();
//
//        List<DiagnosticMatch> matches = new ArrayList<>();
//
//        for (Map<String, Object> m : diagnostics) {
//            String diagnosticId = Objects.toString(m.get("id"), null);
//            String name = Objects.toString(m.get("name"), diagnosticId);
//            String severity = Objects.toString(m.get("severity"), "MEDIUM");
//            double weight = toDouble(m.get("weight"), 1.0);
//            String cypher = Objects.toString(m.get("cypher"), "").trim();
//
//            if (cypher.isBlank()) continue;
//
//            // Guardrails: block write keywords; keep it read-only.
//            if (looksLikeWriteQuery(cypher)) {
//                matches.add(new DiagnosticMatch(
//                        diagnosticId, name, severity, 0.0,
//                        "blocked: diagnostic cypher looked like a write query",
//                        Map.of("blocked", true)
//                ));
//                continue;
//            }
//
//            List<Map<String, Object>> rows;
//            try {
//                rows = (List<Map<String, Object>>) neo4j.query(cypher)
//                        .bindAll(Map.of(
//                                "attemptId", attemptId,
//                                "scopeGoalId", scopeGoalId,
//                                "limit", lim
//                        ))
//                        .fetch()
//                        .all();
//            } catch (Exception e) {
//                matches.add(new DiagnosticMatch(
//                        diagnosticId, name, severity, 0.0,
//                        "error: " + safeMsg(e),
//                        Map.of("error", true)
//                ));
//                continue;
//            }
//
//            if (rows == null || rows.isEmpty()) continue;
//
//            // Take the strongest row (by rawScore if present)
//            Map<String, Object> best = rows.get(0);
//            double bestRaw = toDouble(best.get("rawScore"), 1.0);
//            String observation = Objects.toString(best.get("observation"), "(observation missing)");
//
//            for (Map<String, Object> r : rows) {
//                double rs = toDouble(r.get("rawScore"), 1.0);
//                if (rs > bestRaw) {
//                    bestRaw = rs;
//                    best = r;
//                    observation = Objects.toString(r.get("observation"), "(observation missing)");
//                }
//            }
//
//            double score = clamp01(bestRaw * weight);
//
//            matches.add(new DiagnosticMatch(
//                    diagnosticId,
//                    name,
//                    severity,
//                    score,
//                    observation,
//                    Map.of(
//                            "rows", rows.size(),
//                            "weight", weight,
//                            "rawScore", bestRaw
//                    )
//            ));
//        }
//
//        // Sort by final score desc
//        matches.sort(Comparator.comparingDouble(DiagnosticMatch::score).reversed());
//
//        // Cap output size
//        if (matches.size() > lim) {
//            matches = matches.subList(0, lim);
//        }
//
////        neo4jDiagnosticGraphService.persistScan(goalId, subGoalId, attemptId, limit, matches);
//
//        return new DiagnosticScanResult(matches);
//    }
//
//    private static boolean looksLikeWriteQuery(String cypher) {
//        String s = cypher.toLowerCase(Locale.ROOT);
//        return s.contains("create ") || s.contains("merge ") || s.contains("delete ") || s.contains("set ")
//                || s.contains("detach delete") || s.contains("remove ");
//    }
//
//    private static double toDouble(Object v, double fallback) {
//        if (v == null) return fallback;
//        if (v instanceof Number n) return n.doubleValue();
//        try { return Double.parseDouble(v.toString()); } catch (Exception ignored) { return fallback; }
//    }
//
//    private static double clamp01(double d) {
//        if (d < 0.0) return 0.0;
//        if (d > 1.0) return 1.0;
//        return d;
//    }
//
//    private static String safeMsg(Exception e) {
//        String m = e.getMessage();
//        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
//    }
//}

package com.walpro.autobuilder.graph.diagnostic;

import com.walpro.autobuilder.graph.HistoryRead;
import com.walpro.autobuilder.graph.HistorySpan;
import com.walpro.autobuilder.graph.HistoryUse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Transactional("transactionManager")
@RequiredArgsConstructor
public class DiagnosticScanService {

    private static final int MAX_LIMIT = 30;

    private final Neo4jClient neo4j;

    @HistoryRead(span = HistorySpan.KEY_OWNER, use = HistoryUse.ADVISORY,
            note = "Diagnostic patterns over one attempt's actions, returned by the graph query endpoint.")
    public DiagnosticScanResult scan(String goalId, String subGoalId, String attemptId, int limit) {
        if (attemptId == null || attemptId.isBlank()) return DiagnosticScanResult.empty();

        String scopeGoalId = (subGoalId != null && !subGoalId.isBlank()) ? subGoalId : goalId;
        int lim = Math.max(1, Math.min(limit, MAX_LIMIT));

        List<DiagnosticMatch> matches = new ArrayList<>();


// A0) Debug: show latest evo/eval timestamps (always returns when evo exists)
        {
            var row = neo4j.query("""
        MATCH (evo:Action {type:'evolution'})
        WHERE evo.attemptId = $attemptId
           OR ($scopeGoalId IS NOT NULL AND $scopeGoalId <> '' AND evo.targetsGoalId = $scopeGoalId)
        WITH max(evo.timestamp) AS lastEvoTs
        OPTIONAL MATCH (ev:Action {type:'evaluation'})
        WHERE ev.attemptId = $attemptId
           OR ($scopeGoalId IS NOT NULL AND $scopeGoalId <> '' AND ev.targetsGoalId = $scopeGoalId)
        WITH lastEvoTs, max(ev.timestamp) AS lastEvalTs
        WHERE lastEvoTs IS NOT NULL
        RETURN
          'lastEvo=' + toString(lastEvoTs) + ', lastEval=' + toString(lastEvalTs) AS observation,
          0.10 AS rawScore
        LIMIT 1
        """)
                    .bindAll(Map.of("attemptId", attemptId, "scopeGoalId", scopeGoalId))
                    .fetch()
                    .one()
                    .orElse(null);

            if (row != null) {
                matches.add(new DiagnosticMatch(
                        "diag_debug_last_evo_eval",
                        "Debug: last evolution/evaluation timestamps",
                        "LOW",
                        0.10,
                        Objects.toString(row.get("observation"), "(missing observation)"),
                        Map.of("attemptId", attemptId)
                ));
            }
        }

        // A1) Evaluation missing after evolution (most basic deadlock cause)
        {
            var row = neo4j.query("""
                MATCH (evo:Action {type:'evolution'})
                WHERE evo.attemptId = $attemptId
                   OR ($scopeGoalId IS NOT NULL AND $scopeGoalId <> '' AND evo.targetsGoalId = $scopeGoalId)
                WITH max(evo.timestamp) AS lastEvoTs
                OPTIONAL MATCH (ev:Action {type:'evaluation'})
                WHERE ev.attemptId = $attemptId
                   OR ($scopeGoalId IS NOT NULL AND $scopeGoalId <> '' AND ev.targetsGoalId = $scopeGoalId)
                WITH lastEvoTs, max(ev.timestamp) AS lastEvalTs
                WHERE lastEvoTs IS NOT NULL AND (lastEvalTs IS NULL OR lastEvalTs < lastEvoTs)
                RETURN
                  'lastEvo=' + toString(lastEvoTs) + ', lastEval=' + toString(lastEvalTs) AS observation,
                  1.0 AS rawScore
                LIMIT 1
                """)
                    .bindAll(Map.of("attemptId", attemptId, "scopeGoalId", scopeGoalId))
                    .fetch()
                    .one()
                    .orElse(null);

            if (row != null) {
                String observation = Objects.toString(row.get("observation"), "(missing observation)");
                double raw = toDouble(row.get("rawScore"), 1.0);

                matches.add(new DiagnosticMatch(
                        "diag_eval_missing_after_evo",
                        "Evaluation missing after evolution",
                        "HIGH",
                        clamp01(raw),
                        observation,
                        Map.of("attemptId", attemptId)
                ));
            }
        }

        // A2) Multiple evaluations without evolution (thrash / loop symptom)
        {
            var row = neo4j.query("""
                MATCH (ev:Action {type:'evaluation'})
                WHERE ev.attemptId = $attemptId
                   OR ($scopeGoalId IS NOT NULL AND $scopeGoalId <> '' AND ev.targetsGoalId = $scopeGoalId)
                WITH count(ev) AS evalCount, max(ev.timestamp) AS lastEvalTs
                OPTIONAL MATCH (evo:Action {type:'evolution'})
                WHERE evo.attemptId = $attemptId
                   OR ($scopeGoalId IS NOT NULL AND $scopeGoalId <> '' AND evo.targetsGoalId = $scopeGoalId)
                WITH evalCount, lastEvalTs, count(evo) AS evoCount
                WHERE evalCount >= 3 AND evoCount = 0
                RETURN
                  'evalCount=' + toString(evalCount) + ', lastEval=' + toString(lastEvalTs) AS observation,
                  CASE WHEN evalCount >= 6 THEN 1.0 ELSE 0.7 END AS rawScore
                LIMIT 1
                """)
                    .bindAll(Map.of("attemptId", attemptId, "scopeGoalId", scopeGoalId))
                    .fetch()
                    .one()
                    .orElse(null);

            if (row != null) {
                String observation = Objects.toString(row.get("observation"), "(missing observation)");
                double raw = toDouble(row.get("rawScore"), 0.7);

                matches.add(new DiagnosticMatch(
                        "diag_eval_thrashing_no_evo",
                        "Repeated evaluation without evolution",
                        "MEDIUM",
                        clamp01(raw),
                        observation,
                        Map.of("attemptId", attemptId)
                ));
            }
        }

        // A3) Planner cascade in same scope (symptom: too many planner actions for scopeGoalId)
        // Uses targetsGoalId because your Action registrar sets it; falls back to attempt-only if scope is null.
        {
            var row = neo4j.query("""
                MATCH (p:Action {type:'planner'})
                WHERE p.attemptId = $attemptId
                   OR ($scopeGoalId IS NOT NULL AND $scopeGoalId <> '' AND p.targetsGoalId = $scopeGoalId)
                WITH count(p) AS n, max(p.timestamp) AS lastTs
                WHERE n >= 4
                RETURN
                  'plannerActions=' + toString(n) + ', last=' + toString(lastTs) AS observation,
                  CASE WHEN n >= 8 THEN 1.0 ELSE 0.6 END AS rawScore
                LIMIT 1
                """)
                    .bindAll(Map.of(
                            "attemptId", attemptId,
                            "scopeGoalId", scopeGoalId
                    ))
                    .fetch()
                    .one()
                    .orElse(null);

            if (row != null) {
                String observation = Objects.toString(row.get("observation"), "(missing observation)");
                double raw = toDouble(row.get("rawScore"), 0.6);

                matches.add(new DiagnosticMatch(
                        "diag_planner_cascade",
                        "Planner cascade in scope",
                        "MEDIUM",
                        clamp01(raw),
                        observation,
                        Map.of(
                                "attemptId", attemptId,
                                "scopeGoalId", scopeGoalId == null ? "" : scopeGoalId
                        )
                ));
            }
        }

        // A4) Rejection loop: many evals, most are REJECTED — common stagnation pattern
        {
            var row = neo4j.query("""
                MATCH (ev:Action {type:'evaluation'})
                WHERE ev.attemptId = $attemptId
                   OR ($scopeGoalId IS NOT NULL AND $scopeGoalId <> '' AND ev.targetsGoalId = $scopeGoalId)
                WITH count(ev) AS total,
                     sum(CASE WHEN ev.status = 'REJECTED' THEN 1 ELSE 0 END) AS rejected,
                     max(ev.timestamp) AS lastEvalTs
                WHERE total >= 3 AND rejected * 1.0 / total >= 0.60
                RETURN
                  'total=' + toString(total) + ', rejected=' + toString(rejected)
                    + ', rate=' + toString(round(rejected * 100.0 / total)) + '%'
                    + ', lastEval=' + toString(lastEvalTs) AS observation,
                  CASE WHEN rejected * 1.0 / total >= 0.85 THEN 1.0
                       WHEN rejected * 1.0 / total >= 0.70 THEN 0.85
                       ELSE 0.70 END AS rawScore
                LIMIT 1
                """)
                    .bindAll(Map.of("attemptId", attemptId, "scopeGoalId", scopeGoalId))
                    .fetch()
                    .one()
                    .orElse(null);

            if (row != null) {
                String observation = Objects.toString(row.get("observation"), "(missing observation)");
                double raw = toDouble(row.get("rawScore"), 0.70);

                matches.add(new DiagnosticMatch(
                        "diag_rejection_loop",
                        "Persistent rejection loop",
                        "HIGH",
                        clamp01(raw),
                        observation,
                        Map.of("attemptId", attemptId, "scopeGoalId", scopeGoalId == null ? "" : scopeGoalId)
                ));
            }
        }

        matches.sort(Comparator.comparingDouble(DiagnosticMatch::score).reversed());
        if (matches.size() > lim) matches = matches.subList(0, lim);

        return new DiagnosticScanResult(matches);
    }

    private static double toDouble(Object v, double fallback) {
        if (v == null) return fallback;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception ignored) { return fallback; }
    }

    private static double clamp01(double d) {
        if (d < 0.0) return 0.0;
        if (d > 1.0) return 1.0;
        return d;
    }
}