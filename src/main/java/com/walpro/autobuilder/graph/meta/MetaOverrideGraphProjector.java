package com.walpro.autobuilder.graph.meta;

import com.walpro.autobuilder.core.engine.meta.MetaOverrideEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MetaOverrideGraphProjector {

    private final Neo4jClient neo4j;

    public void project(MetaOverrideEvent event) {
        if (event == null) return;

        // 1) Cycle + MetaOverride
        neo4j.query("""
                MERGE (c:Cycle {cycleName: $cycleName})
                CREATE (m:MetaOverride {
                    cycleName: $cycleName,
                    ts: $ts,
                    reasonCode: $reasonCode,
                    category: $category,
                    mode: $mode,
                    currentGoalId: $currentGoalId,
                    currentSubGoalId: $currentSubGoalId,
                    requestedStepCount: $requestedStepCount,
                    executedStepCount: $executedStepCount
                })
                MERGE (c)-[:HAS_META_OVERRIDE]->(m)
                """)
                .bindAll(Map.of(
                        "cycleName", event.cycleName(),
                        "ts", event.ts(),
                        "reasonCode", event.reasonCode(),
                        "category", event.category() != null ? event.category().name() : "UNKNOWN",
                        "mode", event.mode(),
                        "currentGoalId", event.currentGoalId(),
                        "currentSubGoalId", event.currentSubGoalId(),
                        "requestedStepCount", event.requestedStepCount(),
                        "executedStepCount", event.executedStepCount()
                ))
                .run();

        // 2) Skipped steps
        List<Map<String, Object>> skipped = event.skippedSteps();
        if (skipped == null || skipped.isEmpty()) return;

        for (Map<String, Object> s : skipped) {
            Object idx = s.get("index");
            Object role = s.get("role");
            Object reason = s.get("reason");

            neo4j.query("""
                    MATCH (m:MetaOverride {cycleName: $cycleName, ts: $ts})
                    CREATE (k:MetaSkip {
                        cycleName: $cycleName,
                        ts: $ts,
                        index: $index,
                        role: $role,
                        reason: $reason
                    })
                    CREATE (m)-[:SKIPPED]->(k)
                    """)
                    .bindAll(Map.of(
                            "cycleName", event.cycleName(),
                            "ts", event.ts(),
                            "index", idx,
                            "role", role,
                            "reason", reason
                    ))
                    .run();
        }
    }
}
