package com.walpro.autobuilder.graph.mutation;

import com.walpro.autobuilder.graph.HistoryRead;
import com.walpro.autobuilder.graph.HistorySpan;
import com.walpro.autobuilder.graph.HistoryUse;
import com.walpro.autobuilder.core.mutation.DeferredMutation;
import com.walpro.autobuilder.core.mutation.DeferredMutationState;
import com.walpro.autobuilder.core.mutation.MutationBlocker;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
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
    private final Driver driver;
    private final com.walpro.autobuilder.core.init.RunIdentityService runIdentity;

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
        // T1-FO: schema DDL (CREATE CONSTRAINT/INDEX) and the sentinel data write (MERGE/DELETE)
        // MUST NOT share a transaction — Neo4j rejects a write after a schema modification in the
        // same transaction (Neo.ClientError.Transaction.ForbiddenDueToTransactionType), which
        // crashes a first-ever boot against a FRESH database (there the CREATE CONSTRAINT is a
        // real schema change; a warm database no-ops the DDL and masks the bug). Each statement
        // runs through the driver in its own auto-commit transaction, so the DDL always commits
        // before the sentinel write regardless of any ambient Spring transaction.
        runInOwnTransaction(
                "CREATE CONSTRAINT deferred_mutation_id_unique IF NOT EXISTS "
                        + "FOR (m:DeferredMutation) REQUIRE m.id IS UNIQUE");
        runInOwnTransaction(
                "CREATE INDEX deferred_mutation_state_idx IF NOT EXISTS "
                        + "FOR (m:DeferredMutation) ON (m.state)");
        runInOwnTransaction(
                "CREATE CONSTRAINT blocker_fingerprint_unique IF NOT EXISTS "
                        + "FOR (b:Blocker) REQUIRE b.fingerprint IS UNIQUE");

        // T6-J: the orchestration graph had THREE indexes at 65,360 nodes, all on the two labels
        // above, while every label the loop writes each cycle had none — Artifact 19,741,
        // Relation 11,657, Action 10,248, Interpretation 3,680, Cycle 1,715, Episode 1,695,
        // Goal 1,419. Measured A/B on one build and one graph, adding these took the per-cycle
        // memory-ingest from 120.4s to 95.5s: a real 21%, and NOT the main cost (that is
        // GraphWriter.Batch.commit() itself, ~93s, which no index can touch — see the ticket).
        //
        // Created here because they were first added by hand against a live database, where they
        // would have been lost on any fresh graph and the 21% with them. T5-BJ predicted exactly
        // this class and was filed "latent; free at 558 goals" — the premise held and the scale
        // assumption did not, so these are added for the labels that GROW rather than the one
        // that was measured.
        for (String label : java.util.List.of(
                "Episode", "Artifact", "Interpretation", "Relation",
                "Cycle", "Action", "Goal", "Merge", "CriterionResult", "FileScore")) {
            runInOwnTransaction("CREATE INDEX " + label.toLowerCase(java.util.Locale.ROOT)
                    + "_id_idx IF NOT EXISTS FOR (n:" + label + ") ON (n.id)");
        }

        // T5-BJ: T6-J above indexed Goal.id, but the dedup reads filter on lineageId, so they
        // stayed full label scans. Measured on the live graph at 3,028 goals / 78 lineages:
        // `MATCH (g:Goal) WHERE g.lineageId = $lineageId RETURN count(g)` costs 6,057 DbHits to
        // return 96 rows — the hits track the TOTAL goal count, not the lineage's own size, which
        // is what a scan means. Every subgoal proposal pays it (findAllDescriptions), and again
        // for the unachievable guard (findDescriptionsByStatus).
        runInOwnTransaction("CREATE INDEX goal_lineage_idx IF NOT EXISTS FOR (g:Goal) ON (g.lineageId)");
        // Composite, because findDescriptionsByStatus filters on both and a lineage's goals are
        // mostly one status late in a run — the lineage index alone still scans them all.
        runInOwnTransaction(
                "CREATE INDEX goal_lineage_status_idx IF NOT EXISTS FOR (g:Goal) ON (g.lineageId, g.status)");

        // T5-AD: sentinel write-and-delete to register every property key referenced by the
        // MATCH/MERGE persist queries. The sentinel id is namespaced so a real DeferredMutation
        // can never collide with it. Runs after the schema has settled (separate transactions).
        runInOwnTransaction("""
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
                    m.state = 'PREHEAT',
                    m.lineageId = '',
                    m.runId = ''
                WITH m
                MERGE (b:Blocker { fingerprint: '__autobuilder_preheat_sentinel__' })
                SET b.kind = '',
                    b.receiverType = '',
                    b.symbol = '',
                    b.signature = ''
                MERGE (f:DeferredFile { deferredId: '__autobuilder_preheat_sentinel__', path: '' })
                SET f.contentHash = '',
                    f.deletion = false
                """);

        runInOwnTransaction("""
                MATCH (m:DeferredMutation { id: '__autobuilder_preheat_sentinel__' })
                OPTIONAL MATCH (b:Blocker { fingerprint: '__autobuilder_preheat_sentinel__' })
                OPTIONAL MATCH (f:DeferredFile { deferredId: '__autobuilder_preheat_sentinel__' })
                DETACH DELETE m, b, f
                """);
    }

    /**
     * T1-FO: run one Cypher statement in its own driver-managed auto-commit transaction,
     * independent of any Spring-managed transaction bound to the calling thread. Used by
     * {@link #ensureSchema()} to keep schema DDL and the sentinel data write in separate
     * transactions.
     */
    private void runInOwnTransaction(String cypher) {
        try (Session session = driver.session()) {
            session.run(cypher).consume();
        }
    }

    @Override
    public void persist(DeferredMutation d) {
        Map<String, Object> params = new HashMap<>();
        params.put("lineageId", runIdentity.lineageId());
        params.put("runId", runIdentity.runId());
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
                    m.state = $state,
                    m.lineageId = $lineageId,
                    m.runId = $runId
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
            bp.put("lineageId", runIdentity.lineageId());
            bp.put("runId", runIdentity.runId());
            neo4jClient.query("""
                    MERGE (b:Blocker { fingerprint: $fingerprint })
                    SET b.kind = $kind,
                        b.receiverType = $receiverType,
                        b.symbol = $symbol,
                        b.signature = $signature,
                        b.lineageId = $lineageId,
                        b.runId = $runId
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
            fp.put("lineageId", runIdentity.lineageId());
            fp.put("runId", runIdentity.runId());
            neo4jClient.query("""
                    MATCH (m:DeferredMutation { id: $deferredId })
                    MERGE (f:DeferredFile { deferredId: $deferredId, path: $path })
                    SET f.contentHash = $contentHash,
                        f.lineageId = $lineageId,
                        f.runId = $runId
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

    @HistoryRead(span = HistorySpan.LINEAGE, use = HistoryUse.BINDING,
            note = "Clears a parked mutation's blockers and returns the ones now ready to restage. Scoped 2026-09-23 (T2-ER): unscoped, it could mark another lineage's parked mutation ready.")
    @Override
    public Set<String> dropBlockersAndFindCleared(Set<String> clearedFingerprints) {
        if (clearedFingerprints == null || clearedFingerprints.isEmpty()) return Set.of();

        // 1. Drop the BLOCKED_BY edges from any DeferredMutation to any blocker whose
        //    fingerprint is in clearedFingerprints.
        //    T2-ER: this lineage's mutations only. A Blocker node is shared by fingerprint across
        //    every lineage that ever parked on it, so an unscoped match reached into other
        //    lineages' parked work.
        neo4jClient.query("""
                MATCH (m:DeferredMutation)-[r:BLOCKED_BY]->(b:Blocker)
                WHERE b.fingerprint IN $fps
                  AND m.state IN ['DEFERRED','READY_TO_RESTAGE']
                  AND m.lineageId = $lineageId
                DELETE r
                """)
                .bindAll(Map.of("fps", new ArrayList<>(clearedFingerprints),
                        "lineageId", runIdentity.lineageId()))
                .run();

        // 2. Find any DeferredMutation that now has zero open BLOCKED_BY edges AND is still
        //    in DEFERRED. Caller flips them to READY_TO_RESTAGE.
        Collection<Map<String, Object>> rows = neo4jClient.query("""
                MATCH (m:DeferredMutation)
                WHERE m.state = 'DEFERRED'
                  AND m.lineageId = $lineageId
                  AND NOT (m)-[:BLOCKED_BY]->(:Blocker)
                RETURN m.id AS id
                """)
                .bind(runIdentity.lineageId()).to("lineageId")
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

    @HistoryRead(span = HistorySpan.LINEAGE, use = HistoryUse.BINDING,
            note = "The open and ready deferred mutations the restage scheduler acts on. Scoped 2026-09-23 (T2-ER): unscoped, a fresh lineage's first sweep found another lineage's parked mutations, judged their intent goal missing, and rewrote them DROPPED_INTENT_CLOSED.")
    private List<DeferredMutation> loadByState(Set<String> states, String intentGoalIdFilter) {
        // T2-ER: this lineage's parked work only. Unscoped, a fresh lineage's first sweep found
        // another lineage's parked mutations, judged their intent goal missing, and rewrote that
        // lineage's records as DROPPED_INTENT_CLOSED.
        StringBuilder cypher = new StringBuilder(
                "MATCH (m:DeferredMutation) WHERE m.state IN $states AND m.lineageId = $lineageId");
        Map<String, Object> params = new HashMap<>();
        params.put("states", new ArrayList<>(states));
        params.put("lineageId", runIdentity.lineageId());
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

    @HistoryRead(span = HistorySpan.KEY_OWNER, use = HistoryUse.BINDING,
            note = "One deferred mutation by id, with its blockers and files; the id comes from the lineage-scoped loadByState.")
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
