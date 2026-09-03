package com.walpro.autobuilder.core.checkpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.init.RunIdentityService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalTransitionLog;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * T4-DQ slice 1: writes the points the loop can return to.
 *
 * <p>A checkpoint is mostly references. The output tree is already copied to
 * {@code snapshots/<cycleId>/} every cycle and the cycle context is already serialised whole to
 * {@code mutations/<cycleId>/meta.json}, so what this adds is the part that was never captured
 * anywhere: the decision-bearing in-memory holders, the fold cut point in the goal-status history,
 * and — for the FAITHFUL cache mode — a copy of {@code cache/}, which is a few kilobytes.
 *
 * <p>Cadence is every cycle by default, matching the tree snapshot that a checkpoint is useless
 * without. Storage is bounded by {@link #retainCheckpoints}: the JSON is small, but an unbounded
 * per-cycle directory is how the snapshot tree got to hundreds of megabytes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckpointService {

    public static final String CHECKPOINTS_DIR = "checkpoints";
    public static final String CHECKPOINT_FILE = "checkpoint.json";
    public static final String CACHE_DIR = "cache";

    private final FileService fileService;
    private final ObjectMapper objectMapper;
    private final StateManifest stateManifest;
    private final GoalTransitionLog goalTransitionLog;
    private final RunIdentityService runIdentityService;
    private final StateFingerprintService stateFingerprintService;
    private final com.walpro.autobuilder.goal.GoalPlannerService goalPlannerService;
    private final CheckpointArchive checkpointArchive;

    /**
     * T4-DQ: the cross-run index. Optional so a context without a graph keeps exactly the
     * within-run behaviour it had; with one, a point outlives the run that took it.
     */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.graph.checkpoint.CheckpointGraphService checkpointGraphService;

    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    /** Off by default is the wrong default for a safety net; on by default, one switch to disable. */
    @Value("${autobuilder.checkpoint.enabled:true}")
    private boolean enabled = true;

    /** How many checkpoint directories to keep. Older ones are pruned oldest-first. */
    @Value("${autobuilder.checkpoint.retain:40}")
    private int retainCheckpoints = 40;

    /** Copy {@code cache/} into each checkpoint so a FAITHFUL rewind has something to restore. */
    @Value("${autobuilder.checkpoint.capture-cache:true}")
    private boolean captureCache = true;

    /**
     * T4-DQ: which point the loop's state currently IS. Advances to every checkpoint as it is
     * written and jumps back on a rewind, so a reader can tell "the newest point on disk" apart
     * from "where the loop stands" — after a rewind those differ, and nothing else records the
     * difference (the status endpoint keeps naming the last cycle that RAN). In memory only: a
     * fresh JVM has no position until its first checkpoint, and readers fall back to the newest
     * point, which is then also correct.
     *
     * @param cycleId     the checkpoint the loop's state corresponds to
     * @param since       when it got there
     * @param rewound     true when it got there by a rewind rather than by finishing that cycle
     * @param fromCycleId for a rewind, the cycle the loop was at before; otherwise null
     */
    public record Position(String cycleId, java.time.Instant since, boolean rewound, String fromCycleId) {}

    private volatile Position position;

    public Optional<Position> position() {
        return Optional.ofNullable(position);
    }

    /** Called by the rewind once its restore succeeded: the loop's state is now that of {@code toCycleId}. */
    public void notePositionAfterRewind(String toCycleId, String fromCycleId) {
        position = new Position(toCycleId, java.time.Instant.now(), true, fromCycleId);
    }

    /**
     * Take a checkpoint at the end of {@code context}'s cycle. Never throws: a failed checkpoint
     * costs the loop the ability to rewind to this point, which must not also cost it the cycle.
     */
    public Optional<CheckpointRecord> write(CycleContextData context) {
        if (!enabled || context == null || context.cycleName() == null) return Optional.empty();
        try {
            String dir = CHECKPOINTS_DIR + "/" + context.cycleName();
            fileService.ensureFolderExists(dir);

            StateManifest.Capture capture = stateManifest.capture();
            boolean cacheCopied = captureCache && copyCache(dir);

            CheckpointRecord record = new CheckpointRecord(
                    context.cycleName(),
                    java.time.Instant.now(),
                    runIdentityService.runId(),
                    runIdentityService.lineageId(),
                    context.currentGoalId(),
                    // T4-DQ: the PLANNER's pointer, not the cycle context's. They are two copies
                    // of one fact and the context's is taken earlier in the cycle, so recording it
                    // here while the fingerprint digests the planner's made every rewind restore a
                    // pointer that had never been fingerprinted — reported, correctly, as a goal
                    // divergence on the first two field rewinds.
                    goalPlannerService.getCurrentSubGoalId(),
                    goalTransitionLog.currentSeq(),
                    context.snapshotPath(),
                    context.mutationPath() == null ? null : context.mutationPath() + "/meta.json",
                    context.merged(),
                    cacheCopied,
                    capture.state(),
                    capture.failures(),
                    stateFingerprintService.of(capture));

            fileService.writeJson(dir + "/" + CHECKPOINT_FILE, record);
            position = new Position(record.cycleId(), record.at(), false, null);

            // Keep the point reachable after this run ends: a source-only tree copy that reset and
            // resume do not clear, and an index entry in the graph, which they do not purge either.
            String archivePath = checkpointArchive.archive(record.cycleId(), record.snapshotPath());
            if (checkpointGraphService != null) {
                checkpointGraphService.record(record, archivePath);
            }
            prune();

            if (!capture.complete()) {
                log.warn("T4-DQ: checkpoint {} is INCOMPLETE — holders that refused capture: {}",
                        record.cycleId(), capture.failures());
            }
            log.debug("T4-DQ: checkpoint written for {} (holders={}, goalSeq={}, tree={})",
                    record.cycleId(), record.holders().size(), record.goalTransitionSeq(), record.snapshotPath());
            if (behaviourLogger != null) {
                behaviourLogger.checkpointWritten(record.cycleId(), record.holders().size(),
                        record.goalTransitionSeq(), record.restorable(), capture.failures().size());
            }
            return Optional.of(record);
        } catch (Exception e) {
            log.warn("T4-DQ: checkpoint for cycle {} failed: {}", context.cycleName(), e.getMessage());
            // Leave no directory that looks like a point and holds none — the listing would offer
            // it and every read of it would fail.
            try {
                fileService.deleteRecursively(CHECKPOINTS_DIR + "/" + context.cycleName());
            } catch (IOException | RuntimeException cleanup) {
                log.debug("T4-DQ: could not clear the failed checkpoint directory for {}: {}",
                        context.cycleName(), cleanup.getMessage());
            }
            return Optional.empty();
        }
    }

    /**
     * Read a checkpoint back, or empty when there is none for that cycle.
     *
     * <p><b>The fingerprint is a check, not the payload, and is dropped rather than allowed to
     * cost the restore.</b> Learned the hard way: changing the fingerprint's shape made every
     * checkpoint on disk unreadable, so a run's ten perfectly restorable points — full trees, all
     * holders, no capture failures — became unreachable because the VERIFICATION had changed. A
     * checkpoint that can be restored but not verified is worth strictly more than one that can be
     * neither, and it says which it is: {@code fingerprint == null} means the round trip goes
     * unchecked, which the rewind reports rather than hides.
     */
    public Optional<CheckpointRecord> read(String cycleId) {
        if (cycleId == null || cycleId.isBlank()) return Optional.empty();
        String path = CHECKPOINTS_DIR + "/" + cycleId + "/" + CHECKPOINT_FILE;
        String json;
        try {
            if (!fileService.exists(path)) return Optional.empty();
            json = fileService.readString(path);
        } catch (IOException | RuntimeException e) {
            log.warn("T4-DQ: checkpoint {} is unreadable: {}", cycleId, e.getMessage());
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, CheckpointRecord.class));
        } catch (Exception first) {
            try {
                com.fasterxml.jackson.databind.JsonNode tree = objectMapper.readTree(json);
                if (tree instanceof com.fasterxml.jackson.databind.node.ObjectNode object
                        && object.has("fingerprint")) {
                    object.remove("fingerprint");
                    CheckpointRecord record = objectMapper.treeToValue(object, CheckpointRecord.class);
                    log.info("T4-DQ: checkpoint {} carries a fingerprint this build cannot read — "
                            + "keeping the point, restoring it unverified ({}).", cycleId, first.getMessage());
                    return Optional.of(record);
                }
            } catch (Exception ignored) {
                // fall through to the original failure
            }
            log.warn("T4-DQ: checkpoint {} is unreadable: {}", cycleId, first.getMessage());
            return Optional.empty();
        }
    }

    /** Captured state as a tree, for a restore that wants the raw node. */
    public JsonNode holdersNode(CheckpointRecord record) {
        return objectMapper.valueToTree(record.holders());
    }

    /**
     * A point from ANY run of this lineage: the local file first, the graph index second.
     *
     * <p>Local first because a point taken by the run that is still going is authoritative and
     * needs no round trip. The graph answers for everything older, which is what makes a rewind
     * reach past the run boundary at all.
     */
    public Optional<CheckpointRecord> readAnyRun(String cycleId) {
        Optional<CheckpointRecord> local = read(cycleId);
        if (local.isPresent()) return local;
        if (checkpointGraphService == null) return Optional.empty();
        return checkpointGraphService.byCycleId(cycleId)
                .map(com.walpro.autobuilder.graph.checkpoint.CheckpointGraphService.StoredCheckpoint::record);
    }

    /**
     * Where a point's archived tree lives, or empty when it kept none.
     *
     * <p>The archive's own layout answers first, because the path is deterministic from the cycle
     * id and the copy is sitting on this machine either way. Requiring the graph to find it made a
     * rewind depend on the database for a file it could see — and, since the graph is optional,
     * made the archive invisible wherever one is not wired.
     */
    public Optional<String> archivePathOf(String cycleId) {
        if (cycleId == null || cycleId.isBlank()) return Optional.empty();
        String conventional = CheckpointArchive.ARCHIVE_DIR + "/" + cycleId;
        if (checkpointArchive.has(conventional)) return Optional.of(conventional);
        if (checkpointGraphService == null) return Optional.empty();
        return checkpointGraphService.byCycleId(cycleId)
                .map(com.walpro.autobuilder.graph.checkpoint.CheckpointGraphService.StoredCheckpoint::archivePath)
                .filter(p -> p != null && !p.isBlank());
    }

    /**
     * Every point this lineage can be returned to, newest first, across runs — the local run's own
     * points plus everything the graph remembers, deduplicated by cycle id with the local copy
     * winning.
     *
     * <p>A point is offered only when something can actually restore it: its own tree snapshot is
     * still present, or its archived source-only copy is. A point with neither is history, not a
     * destination, and listing it would be offering a restore that silently restores less than it
     * claims.
     */
    public List<CheckpointRecord> restorableAcrossRuns(String lineageId, int limit) {
        java.util.LinkedHashMap<String, CheckpointRecord> byCycle = new java.util.LinkedHashMap<>();
        for (CheckpointRecord local : restorableCheckpoints()) {
            byCycle.put(local.cycleId(), local);
        }
        if (checkpointGraphService != null) {
            for (var stored : checkpointGraphService.forLineage(lineageId, Math.max(1, limit))) {
                CheckpointRecord record = stored.record();
                if (record == null || record.cycleId() == null) continue;
                if (byCycle.containsKey(record.cycleId())) continue;
                if (!record.complete()) continue;
                boolean restorable = treeStillPresent(record)
                        || checkpointArchive.has(stored.archivePath())
                        || archivePathOf(record.cycleId()).isPresent();
                if (restorable) byCycle.put(record.cycleId(), record);
            }
        }
        List<CheckpointRecord> out = new java.util.ArrayList<>(byCycle.values());
        out.sort(java.util.Comparator.comparing(CheckpointRecord::cycleId).reversed());
        return out;
    }

    /** Every checkpoint cycle id present on disk, oldest first (cycle ids sort chronologically). */
    public List<String> listCheckpointCycles() {
        List<String> cycles = new ArrayList<>();
        try {
            if (!fileService.exists(CHECKPOINTS_DIR)) return cycles;
            for (Path dir : fileService.listSubfolders(CHECKPOINTS_DIR)) {
                cycles.add(dir.getFileName().toString());
            }
        } catch (IOException e) {
            log.warn("T4-DQ: could not list checkpoints: {}", e.getMessage());
        }
        cycles.sort(Comparator.naturalOrder());
        return cycles;
    }

    /**
     * The root goal's status as of a point, folded from the SAME goal history a restore there
     * would apply — so what this predicts and what the rewind actually produces cannot disagree.
     *
     * <p>Empty when the log records nothing about that root so early, which is not evidence that
     * it was settled.
     */
    public Optional<com.walpro.autobuilder.goal.GoalStatus> rootGoalStatusAt(CheckpointRecord record) {
        if (record == null || record.rootGoalId() == null) return Optional.empty();
        try {
            return Optional.ofNullable(goalTransitionLog.foldAt(record.goalTransitionSeq())
                    .statuses().get(record.rootGoalId()));
        } catch (RuntimeException e) {
            log.debug("T4-DQ: could not fold goal history at {}: {}", record.cycleId(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Whether the loop could still WORK from a point, as opposed to merely restore it correctly.
     *
     * <p><b>The distinction cost a field run, 2026-09-03.</b> The grind policy backed out of a
     * stalled path onto the newest point that had landed a merge — and that point's root goal was
     * already COMPLETED, because it belonged to an earlier run of the lineage that had finished.
     * The restore itself was faultless: twelve holders, three goal statuses, no divergence, no
     * failures. The very next cycle read a completed root, concluded {@code GOAL_FULFILLED} and
     * paused. The run had backed out of a bad path onto a terminal one, and five older merged
     * points whose root was still ACTIVE were passed over to reach it.
     *
     * <p>So "this cycle landed work" is not on its own a reason to go back somewhere. A point is
     * somewhere to CONTINUE from only while there is still something left to do there.
     *
     * <p>Unknown is deliberately not treated as settled: only a positively recorded terminal
     * status disqualifies a point, so a missing or unreadable history costs the loop nothing.
     */
    public boolean canContinueFrom(CheckpointRecord record) {
        return rootGoalStatusAt(record)
                .map(status -> status == com.walpro.autobuilder.goal.GoalStatus.ACTIVE)
                .orElse(true);
    }

    /**
     * The points the loop could go back to AND still work from, newest first — what a policy
     * choosing a fork point asks for.
     *
     * <p>The operator listing deliberately shows more than this. Returning to a finished state is
     * something a person may genuinely want, so that surface marks the difference rather than
     * hiding it; a policy whose whole purpose is to keep the run going may not choose one at all.
     */
    public List<CheckpointRecord> continuableCheckpoints() {
        return restorableCheckpoints().stream().filter(this::canContinueFrom).toList();
    }

    /**
     * The checkpoints that could actually be rewound to, newest first — a checkpoint whose tree
     * snapshot has since been cleared is listed nowhere, because offering it would be offering a
     * restore that silently restores less than it claims.
     */
    public List<CheckpointRecord> restorableCheckpoints() {
        List<CheckpointRecord> out = new ArrayList<>();
        List<String> cycles = listCheckpointCycles();
        for (int i = cycles.size() - 1; i >= 0; i--) {
            // An INCOMPLETE checkpoint is not offered. A holder that refused capture would be
            // restored to its start-of-run value instead of the value it actually had, which is a
            // rewind that silently changes a decision — the failure mode this design exists to
            // avoid. Better to fall through to the halt the loop would have taken anyway.
            read(cycles.get(i))
                    .filter(CheckpointRecord::restorable)
                    .filter(CheckpointRecord::complete)
                    .filter(this::treeStillPresent)
                    .ifPresent(out::add);
        }
        return out;
    }

    boolean treeStillPresent(CheckpointRecord record) {
        try {
            return fileService.exists(record.snapshotPath());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The T4-CA state stamp of a checkpoint's tree snapshot — what the tree was like at that
     * point, as recorded when the snapshot was written. Read rather than re-derived: the stamp is
     * a fact about the cycle, and re-probing it later would answer about a different moment.
     */
    public Optional<com.walpro.autobuilder.core.persistence.MutationSnapshotService.SnapshotState>
            snapshotStateOf(String cycleId) {
        return read(cycleId).flatMap(record -> {
            String path = record.snapshotPath() + "/"
                    + com.walpro.autobuilder.core.persistence.MutationSnapshotService.SNAPSHOT_STATE_FILE;
            try {
                if (!fileService.exists(path)) return Optional.empty();
                return Optional.of(objectMapper.readValue(fileService.readString(path),
                        com.walpro.autobuilder.core.persistence.MutationSnapshotService.SnapshotState.class));
            } catch (IOException | RuntimeException e) {
                log.debug("T4-DQ: snapshot state for {} unreadable: {}", cycleId, e.getMessage());
                return Optional.empty();
            }
        });
    }

    /** Where a checkpoint's captured {@code cache/} copy lives. */
    public String cacheCopyPath(String cycleId) {
        return CHECKPOINTS_DIR + "/" + cycleId + "/" + CACHE_DIR;
    }

    private boolean copyCache(String checkpointDir) {
        try {
            if (!fileService.exists(CACHE_DIR)) return false;
            fileService.copyFolder(CACHE_DIR, checkpointDir + "/" + CACHE_DIR);
            return true;
        } catch (IOException | RuntimeException e) {
            log.debug("T4-DQ: cache copy for {} skipped: {}", checkpointDir, e.getMessage());
            return false;
        }
    }

    private void prune() {
        if (retainCheckpoints <= 0) return;
        List<String> cycles = listCheckpointCycles();
        int excess = cycles.size() - retainCheckpoints;
        for (int i = 0; i < excess; i++) {
            try {
                fileService.deleteRecursively(CHECKPOINTS_DIR + "/" + cycles.get(i));
            } catch (IOException e) {
                log.debug("T4-DQ: could not prune checkpoint {}: {}", cycles.get(i), e.getMessage());
            }
        }
    }
}
