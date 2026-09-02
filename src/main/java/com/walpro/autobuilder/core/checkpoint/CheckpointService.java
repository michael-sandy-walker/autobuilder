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
                    context.currentSubGoalId(),
                    goalTransitionLog.currentSeq(),
                    context.snapshotPath(),
                    context.mutationPath() == null ? null : context.mutationPath() + "/meta.json",
                    context.merged(),
                    cacheCopied,
                    capture.state(),
                    capture.failures(),
                    stateFingerprintService.of(capture));

            fileService.writeJson(dir + "/" + CHECKPOINT_FILE, record);
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

    /** Read a checkpoint back, or empty when there is none for that cycle. */
    public Optional<CheckpointRecord> read(String cycleId) {
        if (cycleId == null || cycleId.isBlank()) return Optional.empty();
        String path = CHECKPOINTS_DIR + "/" + cycleId + "/" + CHECKPOINT_FILE;
        try {
            if (!fileService.exists(path)) return Optional.empty();
            return Optional.of(objectMapper.readValue(fileService.readString(path), CheckpointRecord.class));
        } catch (IOException | RuntimeException e) {
            log.warn("T4-DQ: checkpoint {} is unreadable: {}", cycleId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Captured state as a tree, for a restore that wants the raw node. */
    public JsonNode holdersNode(CheckpointRecord record) {
        return objectMapper.valueToTree(record.holders());
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
