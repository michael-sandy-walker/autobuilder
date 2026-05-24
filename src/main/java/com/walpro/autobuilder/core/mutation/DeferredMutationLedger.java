package com.walpro.autobuilder.core.mutation;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.mutation.DeferredMutationGraphService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * T4-BE: high-level API the rest of the system uses to defer / inspect / transition
 * rejected-mutation records. Wraps the graph adapter and emits behaviour events; never
 * persists mutation bytes (those already live under {@code firestarter/mutations/<cycleId>/}
 * via T5-BA).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeferredMutationLedger {

    private final DeferredMutationGraphService graph;

    /** T5-H optional. */
    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    /** Monotonic cycle counter — used for TTL / recency. Survives the lifetime of the JVM. */
    private final AtomicLong cycleClock = new AtomicLong();

    /** Bump on every cycle_start so deferredAtCycleIndex / clearance recency are comparable. */
    public long tick() {
        return cycleClock.incrementAndGet();
    }

    public long currentTick() {
        return cycleClock.get();
    }

    /**
     * Persist a deferral for a rejected mutation. Returns the stored record, or {@code null} if
     * the inputs are invalid (no files, no blockers).
     */
    public DeferredMutation defer(
            CycleContextData context,
            List<FileMutation> proposedFiles,
            List<MutationBlocker> blockers,
            Double llmScore,
            Double heuristicScore
    ) {
        if (proposedFiles == null || proposedFiles.isEmpty()) return null;
        if (blockers == null || blockers.isEmpty()) return null;

        Map<String, String> hashes = new LinkedHashMap<>();
        for (FileMutation fm : proposedFiles) {
            if (fm == null || fm.path() == null || fm.content() == null) continue;
            hashes.put(fm.path(), sha256(fm.content()));
        }
        if (hashes.isEmpty()) return null;

        String id = UUID.randomUUID().toString();
        DeferredMutation d = new DeferredMutation(
                id,
                context.lastAttemptId(),
                context.cycleName(),
                context.currentSubGoalId(),
                context.currentGoalId(),
                llmScore,
                heuristicScore,
                cycleClock.get(),
                Instant.now(),
                "mutations/" + context.cycleName(),
                DeferredMutationState.DEFERRED,
                hashes,
                List.copyOf(blockers)
        );

        graph.persist(d);
        log.info("T4-BE: deferred mutation {} (cycle={} blockers={} files={} llmScore={} heurScore={})",
                id, context.cycleName(), blockers.size(), hashes.size(), llmScore, heuristicScore);
        if (behaviourLogger != null) {
            behaviourLogger.deferredMutationRecorded(
                    context.cycleName(), id, blockers.size(), hashes.size(),
                    context.currentSubGoalId(), llmScore, heuristicScore);
        }
        return d;
    }

    /**
     * Drop {@code BLOCKED_BY} edges for any blocker whose fingerprint is in
     * {@code clearedFingerprints}; flip newly-unblocked records to READY_TO_RESTAGE. Returns
     * the records that became ready (callers may want to surface them in snapshot / fire
     * behaviour events).
     */
    public List<DeferredMutation> recordBlockerClearance(String mergedInCycleId, Set<String> clearedFingerprints) {
        if (clearedFingerprints == null || clearedFingerprints.isEmpty()) return List.of();
        Set<String> nowReady = graph.dropBlockersAndFindCleared(clearedFingerprints);
        List<DeferredMutation> readyList = new ArrayList<>(nowReady.size());
        for (String id : nowReady) {
            graph.updateState(id, DeferredMutationState.READY_TO_RESTAGE);
            DeferredMutation d = graph.findById(id);
            if (d != null) readyList.add(d);
            if (behaviourLogger != null) {
                behaviourLogger.deferredMutationReady(mergedInCycleId, id, clearedFingerprints.size());
            }
            log.info("T4-BE: deferred mutation {} → READY_TO_RESTAGE (cleared by merge in {})", id, mergedInCycleId);
        }
        return readyList;
    }

    /** All open records sorted by deferredAtCycleIndex ASC (oldest first). */
    public List<DeferredMutation> findOpen() {
        List<DeferredMutation> list = new ArrayList<>(graph.findOpen());
        list.sort(Comparator.comparingLong(DeferredMutation::deferredAtCycleIndex));
        return list;
    }

    /** All READY records sorted by ranking score DESC (best first). */
    public List<DeferredMutation> findReadyRanked() {
        long now = cycleClock.get();
        List<DeferredMutation> list = new ArrayList<>(graph.findReady());
        list.sort(Comparator.<DeferredMutation>comparingDouble(d -> d.rank(now)).reversed());
        return list;
    }

    public List<DeferredMutation> findByIntentGoal(String intentGoalId) {
        return graph.findByIntentGoal(intentGoalId);
    }

    public DeferredMutation findById(String id) {
        return graph.findById(id);
    }

    public void markRestaged(String id) { transition(id, DeferredMutationState.RESTAGED, "restaged"); }
    public void markDroppedDrift(String id) { transition(id, DeferredMutationState.DROPPED_DRIFT, "drift"); }
    public void markDroppedReProbeFailed(String id) { transition(id, DeferredMutationState.DROPPED_REPROBE_FAILED, "reprobe_failed"); }
    public void markDroppedIntentClosed(String id) { transition(id, DeferredMutationState.DROPPED_INTENT_CLOSED, "intent_closed"); }
    public void markEvicted(String id, String reason) { transition(id, DeferredMutationState.EVICTED, reason); }

    private void transition(String id, DeferredMutationState newState, String reason) {
        graph.updateState(id, newState);
        if (behaviourLogger != null) {
            switch (newState) {
                case RESTAGED -> behaviourLogger.deferredMutationRestaged(currentCycleFor(id), id);
                case EVICTED -> behaviourLogger.deferredMutationEvicted(currentCycleFor(id), id, reason);
                case DROPPED_DRIFT, DROPPED_REPROBE_FAILED, DROPPED_INTENT_CLOSED ->
                        behaviourLogger.deferredMutationDropped(currentCycleFor(id), id, reason);
                default -> {}
            }
        }
        log.info("T4-BE: deferred mutation {} → {} ({})", id, newState, reason);
    }

    /**
     * Best-effort cycle id for behaviour events on terminal transitions. We don't always know
     * the current cycle id from the ledger context; callers can pass it through if it matters,
     * but for now the deferral's originating cycleId is the most stable handle.
     */
    private String currentCycleFor(String id) {
        DeferredMutation d = graph.findById(id);
        return d != null ? d.cycleId() : id;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // Fallback — never happens for SHA-256 on a Java 21 JVM.
            return Integer.toHexString(s.hashCode());
        }
    }

    /** Test seam — used by replay tests to align cycle counter with logs. */
    public void __resetCycleClockForTest(long value) {
        cycleClock.set(value);
    }

    /** Test seam — used to compose multiple ledger calls in deterministic order. */
    public Collection<String> __openIdsForTest() {
        Set<String> ids = new HashSet<>();
        for (DeferredMutation d : graph.findOpen()) ids.add(d.id());
        return ids;
    }

    /**
     * T4-BE: structured summary the orchestration snapshot exposes to the meta-deliberation
     * agent. Returns counts and a thin description of every READY candidate so the agent can
     * reason about pending re-stages without scraping behaviour logs.
     */
    public Summary summarize() {
        List<DeferredMutation> open = findOpen();
        List<DeferredMutation> ready = findReadyRanked();
        List<ReadyEntry> entries = new ArrayList<>(ready.size());
        for (DeferredMutation d : ready) {
            entries.add(new ReadyEntry(
                    d.id(),
                    d.intentSubgoalId(),
                    d.intentGoalId(),
                    d.combinedScore(),
                    d.touchedFiles() != null ? d.touchedFiles().size() : 0,
                    d.deferredAtCycleIndex()
            ));
        }
        return new Summary(open.size(), ready.size(), entries);
    }

    public record Summary(int openCount, int readyCount, List<ReadyEntry> readyCandidates) {}

    public record ReadyEntry(
            String id,
            String intentSubgoalId,
            String intentGoalId,
            double combinedScore,
            int touchedFileCount,
            long deferredAtCycleIndex
    ) {}
}
