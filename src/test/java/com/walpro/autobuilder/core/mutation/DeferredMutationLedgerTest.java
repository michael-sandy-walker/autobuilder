package com.walpro.autobuilder.core.mutation;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.mutation.DeferredMutationGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DeferredMutationLedgerTest {

    private FakeGraph graph;
    private DeferredMutationLedger ledger;

    @BeforeEach
    void setUp() {
        graph = new FakeGraph();
        ledger = new DeferredMutationLedger(graph);
    }

    @Test
    void defer_persistsRecordWithBlockersAndFileHashes() {
        CycleContextData ctx = baseContext("cycle-A", "goal-root", "sub-1");
        List<FileMutation> files = List.of(
                new FileMutation("src/Loan.java", "class Loan {}")
        );
        List<MutationBlocker> blockers = List.of(
                MutationBlocker.missingMember("Loan", "setReturned", "boolean")
        );

        DeferredMutation d = ledger.defer(ctx, files, blockers, 0.7, 0.65);

        assertThat(d).isNotNull();
        assertThat(d.state()).isEqualTo(DeferredMutationState.DEFERRED);
        assertThat(d.intentSubgoalId()).isEqualTo("sub-1");
        assertThat(d.intentGoalId()).isEqualTo("goal-root");
        assertThat(d.touchedFiles()).containsOnlyKeys("src/Loan.java");
        assertThat(d.touchedFiles().get("src/Loan.java")).isNotBlank();
        assertThat(d.blockers()).hasSize(1);
        assertThat(graph.findById(d.id())).isEqualTo(d);
    }

    @Test
    void defer_returnsNullWhenInputsInvalid() {
        CycleContextData ctx = baseContext("cycle-A", "goal-root", "sub-1");
        assertThat(ledger.defer(ctx, List.of(), List.of(MutationBlocker.missingType("X")), null, null)).isNull();
        assertThat(ledger.defer(ctx,
                List.of(new FileMutation("a.java", "x")),
                List.of(),
                null, null)).isNull();
    }

    @Test
    void recordBlockerClearance_flipsStateWhenAllBlockersCleared() {
        CycleContextData ctx = baseContext("cycle-A", "goal-root", "sub-1");
        DeferredMutation d = ledger.defer(
                ctx,
                List.of(new FileMutation("LoanService.java", "x")),
                List.of(
                        MutationBlocker.missingMember("Loan", "setReturned", "boolean"),
                        MutationBlocker.missingMember("Loan", "getReason", "")
                ),
                0.7, 0.65);

        // Partial clearance — should stay DEFERRED
        List<DeferredMutation> readyAfterPartial = ledger.recordBlockerClearance(
                "cycle-B", Set.of(MutationBlocker.missingMember("Loan", "setReturned", "boolean").fingerprint()));
        assertThat(readyAfterPartial).isEmpty();
        assertThat(graph.findById(d.id()).state()).isEqualTo(DeferredMutationState.DEFERRED);

        // Final clearance — should flip to READY_TO_RESTAGE
        List<DeferredMutation> readyAfterFull = ledger.recordBlockerClearance(
                "cycle-C", Set.of(MutationBlocker.missingMember("Loan", "getReason", "").fingerprint()));
        assertThat(readyAfterFull).hasSize(1);
        assertThat(readyAfterFull.get(0).id()).isEqualTo(d.id());
        assertThat(graph.findById(d.id()).state()).isEqualTo(DeferredMutationState.READY_TO_RESTAGE);
    }

    @Test
    void findReadyRanked_ordersByCombinedScoreTimesRecency() {
        CycleContextData ctxA = baseContext("cycle-A", "goal", "sub");
        ledger.__resetCycleClockForTest(0);
        ledger.tick(); // index 1
        DeferredMutation older = ledger.defer(ctxA,
                List.of(new FileMutation("a.java", "a")),
                List.of(MutationBlocker.missingMember("X", "m", "")),
                0.9, 0.9);

        CycleContextData ctxB = baseContext("cycle-B", "goal", "sub");
        ledger.tick(); // index 2 — newer deferral, slightly lower score
        DeferredMutation newer = ledger.defer(ctxB,
                List.of(new FileMutation("b.java", "b")),
                List.of(MutationBlocker.missingMember("Y", "m", "")),
                0.8, 0.8);

        // Clear both blockers in one merge
        Set<String> cleared = Set.of(
                MutationBlocker.missingMember("X", "m", "").fingerprint(),
                MutationBlocker.missingMember("Y", "m", "").fingerprint()
        );
        ledger.recordBlockerClearance("cycle-merge", cleared);
        ledger.tick(); // index 3

        List<DeferredMutation> ranked = ledger.findReadyRanked();
        assertThat(ranked).hasSize(2);
        // Newer + slightly lower score still beats older + slightly higher score because of
        // the recency factor (older deferral has more cycles between defer and clearance).
        assertThat(ranked.get(0).id()).isEqualTo(newer.id());
        assertThat(ranked.get(1).id()).isEqualTo(older.id());
    }

    @Test
    void terminalTransitions_updateState() {
        CycleContextData ctx = baseContext("cycle-A", "goal", "sub");
        DeferredMutation d = ledger.defer(
                ctx,
                List.of(new FileMutation("a.java", "x")),
                List.of(MutationBlocker.missingType("T")),
                null, 0.5);

        ledger.markDroppedDrift(d.id());
        assertThat(graph.findById(d.id()).state()).isEqualTo(DeferredMutationState.DROPPED_DRIFT);

        // Once dropped, the record no longer counts as open.
        assertThat(ledger.findOpen()).isEmpty();
    }

    @Test
    void findOpen_excludesTerminalStates() {
        CycleContextData ctxA = baseContext("cycle-A", "goal", "sub");
        DeferredMutation open = ledger.defer(ctxA,
                List.of(new FileMutation("a.java", "x")),
                List.of(MutationBlocker.missingType("A")),
                null, null);

        CycleContextData ctxB = baseContext("cycle-B", "goal", "sub");
        DeferredMutation evicted = ledger.defer(ctxB,
                List.of(new FileMutation("b.java", "x")),
                List.of(MutationBlocker.missingType("B")),
                null, null);
        ledger.markEvicted(evicted.id(), "ttl");

        List<DeferredMutation> openList = ledger.findOpen();
        assertThat(openList).extracting(DeferredMutation::id).containsExactly(open.id());
    }

    // ---- helpers ----

    private CycleContextData baseContext(String cycleName, String goalId, String subId) {
        return CycleContextData.builder()
                .cycleName(cycleName)
                .currentGoalId(goalId)
                .currentSubGoalId(subId)
                .lastAttemptId("attempt-" + cycleName)
                .outputPath("output")
                .build();
    }

    /** In-memory graph stand-in for unit tests — mirrors the Neo4j adapter's semantics. */
    private static class FakeGraph implements DeferredMutationGraphService {
        private final Map<String, DeferredMutation> store = new LinkedHashMap<>();

        @Override public void persist(DeferredMutation d) { store.put(d.id(), d); }

        @Override public void updateState(String deferredId, DeferredMutationState newState) {
            DeferredMutation cur = store.get(deferredId);
            if (cur != null) store.put(deferredId, cur.withState(newState));
        }

        @Override public Set<String> dropBlockersAndFindCleared(Set<String> clearedFingerprints) {
            Set<String> nowCleared = new HashSet<>();
            for (Map.Entry<String, DeferredMutation> e : store.entrySet()) {
                DeferredMutation d = e.getValue();
                if (!d.state().isOpen()) continue;
                List<MutationBlocker> remaining = new ArrayList<>();
                for (MutationBlocker b : d.blockers()) {
                    if (!clearedFingerprints.contains(b.fingerprint())) remaining.add(b);
                }
                if (remaining.size() != d.blockers().size()) {
                    DeferredMutation updated = new DeferredMutation(
                            d.id(), d.attemptId(), d.cycleId(),
                            d.intentSubgoalId(), d.intentGoalId(),
                            d.llmScore(), d.heuristicScore(),
                            d.deferredAtCycleIndex(), d.deferredAt(),
                            d.mutationDir(), d.state(),
                            d.touchedFiles(), remaining);
                    store.put(d.id(), updated);
                    if (remaining.isEmpty() && updated.state() == DeferredMutationState.DEFERRED) {
                        nowCleared.add(d.id());
                    }
                }
            }
            return nowCleared;
        }

        @Override public List<DeferredMutation> findOpen() {
            List<DeferredMutation> out = new ArrayList<>();
            for (DeferredMutation d : store.values()) if (d.state().isOpen()) out.add(d);
            return out;
        }

        @Override public List<DeferredMutation> findReady() {
            List<DeferredMutation> out = new ArrayList<>();
            for (DeferredMutation d : store.values())
                if (d.state() == DeferredMutationState.READY_TO_RESTAGE) out.add(d);
            return out;
        }

        @Override public List<DeferredMutation> findByIntentGoal(String intentGoalId) {
            List<DeferredMutation> out = new ArrayList<>();
            for (DeferredMutation d : store.values())
                if (d.state().isOpen() && intentGoalId.equals(d.intentGoalId())) out.add(d);
            return out;
        }

        @Override public DeferredMutation findById(String deferredId) {
            return store.get(deferredId);
        }
    }
}
