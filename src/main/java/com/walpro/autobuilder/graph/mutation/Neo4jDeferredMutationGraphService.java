package com.walpro.autobuilder.graph.mutation;

import com.walpro.autobuilder.core.mutation.DeferredMutation;
import com.walpro.autobuilder.core.mutation.DeferredMutationState;
import com.walpro.autobuilder.core.mutation.MutationBlocker;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * T4-BE: Neo4j adapter for the deferred-mutation ledger. Mirrors the schema described in the
 * ticket — one {@code (:DeferredMutation)} node per deferral with {@code BLOCKED_BY} edges to
 * a shared {@code (:Blocker {fingerprint})} node and {@code TOUCHES} edges to per-deferral
 * {@code (:File)} nodes carrying the captured content hash.
 *
 * <p>Blocker nodes are shared by fingerprint, so a merge that adds {@code Loan.setReturned}
 * can match every deferred mutation blocked on it via a single Cypher pattern (clearance
 * detector). Touched-file nodes are scoped per-deferral because the captured hash is the
 * caller's, not a shared property.
 */
@Slf4j
@Service
@Transactional("transactionManager")
@RequiredArgsConstructor
public class Neo4jDeferredMutationGraphService implements DeferredMutationGraphService {

    private final Neo4jClient neo4jClient;

    // T5-Y: preheat ledger labels + property keys so empty-ledger queries don't emit
    // UnknownLabelWarning / UnknownPropertyKeyWarning every cycle before any deferral exists.
    // The constraints and index also formalise schema invariants today only implicit in the
    // persist queries (DeferredMutation.id uniqueness, Blocker.fingerprint uniqueness) and
    // speed up state-filtered scans in findOpen/findReady.
    //
    // T5-AD: constraints register the constraint-property in the schema, but `MATCH (m:X { id: $id })`
    // queries still emit UnknownPropertyKeyWarning when the *data graph* has never contained a
    // node with that property — observed in `autobuilder-2026-05-28_00-21-26.log` (421 warnings
    // per run). Sentinel write-and-delete guarantees every property key referenced in the
    // persist queries is registered in the data dictionary, suppressing the warnings entirely.
    @PostConstruct
    void ensureSchema() {
        neo4jClient.query(
                "CREATE CONSTRAINT deferred_mutation_id_unique IF NOT EXISTS "
                        + "FOR (m:DeferredMutation) REQUIRE m.id IS UNIQUE")
                .run();
        neo4jClient.query(
                "CREATE INDEX deferred_mutation_state_idx IF NOT EXISTS "
                        + "FOR (m:DeferredMutation) ON (m.state)")
                .run();
        neo4jClient.query(
                "CREATE CONSTRAINT blocker_fingerprint_unique IF NOT EXISTS "
                        + "FOR (b:Blocker) REQUIRE b.fingerprint IS UNIQUE")
                .run();

        // T5-AD: sentinel write-and-delete to register every property key referenced by the
        // MATCH/MERGE queries below. The sentinel id is namespaced so a real DeferredMutation
        // can never collide with it.
        neo4jClient.query("""
                MERGE (m:DeferredMutation { id: '__autobuilder_preheat_sentinel__' })
                SET m.attemptId = '',
                    m.cycleId = '',
                    m.intentSubgoalId = '',
                    m.intentGoalId = '',
                    m.llmScore = 0.0,
                    m.heuristicScore = 0.0,
                    m.deferredAtCycleIndex = 0,
                    m.deferredAt = '',
                    m.mutationDir = '',
                    m.state = 'PREHEAT'
                WITH m
                MERGE (b:Blocker { fingerprint: '__autobuilder_preheat_sentinel__' })
                SET b.kind = '',
                    b.receiverType = '',
                    b.symbol = '',
                    b.signature = ''
                MERGE (f:DeferredFile { deferredId: '__autobuilder_preheat_sentinel__', path: '' })
                SET f.contentHash = '',
                    f.deletion = false
                """)
                .run();

        neo4jClient.query("""
                MATCH (m:DeferredMutation { id: '__autobuilder_preheat_sentinel__' })
                OPTIONAL MATCH (b:Blocker { fingerprint: '__autobuilder_preheat_sentinel__' })
                OPTIONAL MATCH (f:DeferredFile { deferredId: '__autobuilder_preheat_sentinel__' })
                DETACH DELETE m, b, f
                """)
                .run();
    }

    @Override
    public void persist(DeferredMutation d) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", d.id());
        params.put("attemptId", d.attemptId());
        params.put("cycleId", d.cycleId());
        params.put("intentSubgoalId", d.intentSubgoalId());
        params.put("intentGoalId", d.intentGoalId());
        params.put("llmScore", d.llmScore());
        params.put("heuristicScore", d.heuristicScore());
        params.put("deferredAtCycleIndex", d.deferredAtCycleIndex());
        params.put("deferredAt", d.deferredAt() != null ? d.deferredAt().toString() : Instant.now().toString());
        params.put("mutationDir", d.mutationDir());
        params.put("state", d.state().name());

        neo4jClient.query("""
                MERGE (m:DeferredMutation { id: $id })
                SET m.attemptId = $attemptId,
                    m.cycleId = $cycleId,
                    m.intentSubgoalId = $intentSubgoalId,
                    m.intentGoalId = $intentGoalId,
                    m.llmScore = $llmScore,
                    m.heuristicScore = $heuristicScore,
                    m.deferredAtCycleIndex = $deferredAtCycleIndex,
                    m.deferredAt = $deferredAt,
                    m.mutationDir = $mutationDir,
                    m.state = $state
                """)
                .bindAll(params)
                .run();

        for (MutationBlocker b : d.blockers()) {
            Map<String, Object> bp = new HashMap<>();
            bp.put("deferredId", d.id());
            bp.put("fingerprint", b.fingerprint());
            bp.put("kind", b.kind().name());
            bp.put("receiverType", b.receiverType());
            bp.put("symbol", b.symbol());
            bp.put("signature", b.signature());
            neo4jClient.query("""
                    MERGE (b:Blocker { fingerprint: $fingerprint })
                    SET b.kind = $kind,
                        b.receiverType = $receiverType,
                        b.symbol = $symbol,
                        b.signature = $signature
                    WITH b
                    MATCH (m:DeferredMutation { id: $deferredId })
                    MERGE (m)-[:BLOCKED_BY]->(b)
                    """)
                    .bindAll(bp)
                    .run();
        }

        for (Map.Entry<String, String> file : d.touchedFiles().entrySet()) {
            Map<String, Object> fp = new HashMap<>();
            fp.put("deferredId", d.id());
            fp.put("path", file.getKey());
            fp.put("contentHash", file.getValue());
            neo4jClient.query("""
                    MATCH (m:DeferredMutation { id: $deferredId })
                    MERGE (f:DeferredFile { deferredId: $deferredId, path: $path })
                    SET f.contentHash = $contentHash
                    MERGE (m)-[:TOUCHES]->(f)
                    """)
                    .bindAll(fp)
                    .run();
        }
    }

    @Override
    public void updateState(String deferredId, DeferredMutationState newState) {
        neo4jClient.query("MATCH (m:DeferredMutation { id: $id }) SET m.state = $state")
                .bindAll(Map.of("id", deferredId, "state", newState.name()))
                .run();
    }

    @Override
    public Set<String> dropBlockersAndFindCleared(Set<String> clearedFingerprints) {
        if (clearedFingerprints == null || clearedFingerprints.isEmpty()) return Set.of();

        // 1. Drop the BLOCKED_BY edges from any DeferredMutation to any blocker whose
        //    fingerprint is in clearedFingerprints.
        neo4jClient.query("""
                MATCH (m:DeferredMutation)-[r:BLOCKED_BY]->(b:Blocker)
                WHERE b.fingerprint IN $fps
                  AND m.state IN ['DEFERRED','READY_TO_RESTAGE']
                DELETE r
                """)
                .bindAll(Map.of("fps", new ArrayList<>(clearedFingerprints)))
                .run();

        // 2. Find any DeferredMutation that now has zero open BLOCKED_BY edges AND is still
        //    in DEFERRED. Caller flips them to READY_TO_RESTAGE.
        Collection<Map<String, Object>> rows = neo4jClient.query("""
                MATCH (m:DeferredMutation)
                WHERE m.state = 'DEFERRED'
                  AND NOT (m)-[:BLOCKED_BY]->(:Blocker)
                RETURN m.id AS id
                """)
                .fetch()
                .all();

        Set<String> cleared = new HashSet<>();
        for (Map<String, Object> row : rows) {
            Object v = row.get("id");
            if (v != null) cleared.add(String.valueOf(v));
        }
        return cleared;
    }

    @Override
    public List<DeferredMutation> findOpen() {
        return loadByState(Set.of("DEFERRED", "READY_TO_RESTAGE"), null);
    }

    @Override
    public List<DeferredMutation> findReady() {
        return loadByState(Set.of("READY_TO_RESTAGE"), null);
    }

    @Override
    public List<DeferredMutation> findByIntentGoal(String intentGoalId) {
        return loadByState(Set.of("DEFERRED", "READY_TO_RESTAGE"), intentGoalId);
    }

    @Override
    public DeferredMutation findById(String deferredId) {
        List<DeferredMutation> list = loadById(deferredId);
        return list.isEmpty() ? null : list.get(0);
    }

    private List<DeferredMutation> loadByState(Set<String> states, String intentGoalIdFilter) {
        StringBuilder cypher = new StringBuilder("MATCH (m:DeferredMutation) WHERE m.state IN $states");
        Map<String, Object> params = new HashMap<>();
        params.put("states", new ArrayList<>(states));
        if (intentGoalIdFilter != null) {
            cypher.append(" AND m.intentGoalId = $intentGoalId");
            params.put("intentGoalId", intentGoalIdFilter);
        }
        cypher.append(" RETURN m.id AS id");
        Collection<Map<String, Object>> rows = neo4jClient.query(cypher.toString()).bindAll(params).fetch().all();
        List<DeferredMutation> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String id = String.valueOf(row.get("id"));
            DeferredMutation d = findById(id);
            if (d != null) out.add(d);
        }
        return out;
    }

    private List<DeferredMutation> loadById(String deferredId) {
        Map<String, Object> params = Map.of("id", deferredId);
        Optional<Map<String, Object>> node = neo4jClient.query("""
                MATCH (m:DeferredMutation { id: $id })
                RETURN m.id AS id, m.attemptId AS attemptId, m.cycleId AS cycleId,
                       m.intentSubgoalId AS intentSubgoalId, m.intentGoalId AS intentGoalId,
                       m.llmScore AS llmScore, m.heuristicScore AS heuristicScore,
                       m.deferredAtCycleIndex AS deferredAtCycleIndex, m.deferredAt AS deferredAt,
                       m.mutationDir AS mutationDir, m.state AS state
                """).bindAll(params).fetch().one();

        if (node.isEmpty()) return List.of();

        Map<String, Object> row = node.get();

        // blockers
        Collection<Map<String, Object>> blockerRows = neo4jClient.query("""
                MATCH (m:DeferredMutation { id: $id })-[:BLOCKED_BY]->(b:Blocker)
                RETURN b.kind AS kind, b.receiverType AS receiverType,
                       b.symbol AS symbol, b.signature AS signature
                """).bindAll(params).fetch().all();
        List<MutationBlocker> blockers = new ArrayList<>(blockerRows.size());
        for (Map<String, Object> br : blockerRows) {
            blockers.add(new MutationBlocker(
                    MutationBlocker.Kind.valueOf(String.valueOf(br.get("kind"))),
                    String.valueOf(br.get("receiverType")),
                    String.valueOf(br.get("symbol")),
                    String.valueOf(br.get("signature"))
            ));
        }

        // touched files
        Collection<Map<String, Object>> fileRows = neo4jClient.query("""
                MATCH (m:DeferredMutation { id: $id })-[:TOUCHES]->(f:DeferredFile)
                RETURN f.path AS path, f.contentHash AS contentHash
                """).bindAll(params).fetch().all();
        Map<String, String> files = new LinkedHashMap<>();
        for (Map<String, Object> fr : fileRows) {
            files.put(String.valueOf(fr.get("path")), String.valueOf(fr.get("contentHash")));
        }

        DeferredMutation d = new DeferredMutation(
                String.valueOf(row.get("id")),
                row.get("attemptId") != null ? String.valueOf(row.get("attemptId")) : null,
                String.valueOf(row.get("cycleId")),
                row.get("intentSubgoalId") != null ? String.valueOf(row.get("intentSubgoalId")) : null,
                row.get("intentGoalId") != null ? String.valueOf(row.get("intentGoalId")) : null,
                (Double) row.get("llmScore"),
                (Double) row.get("heuristicScore"),
                row.get("deferredAtCycleIndex") instanceof Number n ? n.longValue() : 0L,
                row.get("deferredAt") != null ? Instant.parse(String.valueOf(row.get("deferredAt"))) : Instant.now(),
                String.valueOf(row.get("mutationDir")),
                DeferredMutationState.valueOf(String.valueOf(row.get("state"))),
                files,
                blockers
        );
        return List.of(d);
    }

}
