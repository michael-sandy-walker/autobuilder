package com.walpro.autobuilder.core.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalTransitionLog;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * T4-DQ: returning the loop to a state it was in.
 *
 * <p>The capability, not a harness. Us being able to reproduce a run is a side effect; the point
 * is that a run which has gone down a bad path can back out of it and take a different one. Run
 * {@code 2026-09-01_23-11-25} spent 53 cycles and 25 rollbacks grinding on one compile error and
 * shipped three merges and no controllers; two earlier runs died the same way. A loop that can
 * return to its last good checkpoint and go somewhere else saves those runs.
 *
 * <p>A rewind moves four things, and the fourth is the one that was missing:
 * <ol>
 *   <li><b>the tree</b> — {@code output/} replaced from {@code snapshots/&lt;cycleId&gt;/}, which
 *       the loop has always written every cycle;</li>
 *   <li><b>the goal state</b> — folded back out of the transition log, which is what blocker 1
 *       made possible at all;</li>
 *   <li><b>the cache</b> — restored or cleared, per {@link CacheMode}, and the difference between
 *       those two outcomes is itself a measurement nobody has made;</li>
 *   <li><b>the decision-bearing holders</b> — the brakes, ledgers and streak counters that gate
 *       what the loop is allowed to do next. These never crossed a boundary of any kind before,
 *       and a rewind without them is a rewind that silently keeps deciding as though the undone
 *       cycles had happened.</li>
 * </ol>
 *
 * <p>Refuses rather than half-restores: a checkpoint with no tree snapshot, or whose snapshot has
 * since been cleared, is not rewound to at all. A restore that is subtly wrong is worse than no
 * restore.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RewindService {

    private final FileService fileService;
    private final ObjectMapper objectMapper;
    private final CheckpointService checkpointService;
    private final StateManifest stateManifest;
    private final GoalTransitionLog goalTransitionLog;
    private final GoalPlannerService goalPlannerService;
    private final StateFingerprintService stateFingerprintService;
    private final com.walpro.autobuilder.core.init.RunIdentityService runIdentityService;

    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    /**
     * Return the loop to the state captured at {@code toCycleId}.
     *
     * @param toCycleId the checkpoint to return to
     * @param mode      whether the resolution cache comes back with it
     * @param trigger   what asked for the rewind — a policy id, or {@code "operator"}
     * @param atCycleId the cycle the rewind is being performed in, for the trace
     */
    public Result rewindTo(String toCycleId, CacheMode mode, String trigger, String atCycleId) {
        List<String> failures = new ArrayList<>();
        Optional<CheckpointRecord> found = checkpointService.readAnyRun(toCycleId);
        if (found.isEmpty()) {
            return Result.refused(toCycleId, "no checkpoint for that cycle");
        }
        CheckpointRecord record = found.get();
        if (!record.restorable()) {
            return Result.refused(toCycleId, "checkpoint has no tree snapshot to restore");
        }
        // The run's own snapshot when it survives, the archived source-only copy otherwise. The
        // second is what lets a rewind reach a point from an earlier run, whose snapshots/ was
        // cleared at that run's boundary by design.
        String treeSource = checkpointService.treeStillPresent(record)
                ? record.snapshotPath()
                : checkpointService.archivePathOf(toCycleId).orElse(null);
        if (treeSource == null) {
            return Result.refused(toCycleId,
                    "neither the checkpoint's tree snapshot nor an archived copy is on disk");
        }
        if (!record.complete()) {
            // Not a refusal — an operator may deliberately want a point that is the best available.
            // But it is carried into the result so the rewind never reads as clean: a holder that
            // refused capture comes back as its start-of-run value, not the value it actually had.
            failures.add("checkpoint was incomplete when taken: " + record.captureFailures());
        }

        boolean treeRestored = restoreTree(treeSource, failures);
        if (!treeRestored) {
            return Result.refused(toCycleId, "the tree could not be restored: " + failures);
        }

        // One read of the log for all three views — statuses, existence and how much is undone —
        // so a rewind cannot restore a goal it also believes never existed.
        GoalTransitionLog.Fold fold =
                goalTransitionLog.foldAtForLineage(record.goalTransitionSeq(), record.lineageId(),
                        record.at() != null ? record.at().toString() : null);
        if (!fold.historyAvailable()) {
            // Refuse rather than half-restore, as everywhere else here. A point from a lineage a
            // reset severed carries a cut point into a history that no longer exists; folding it
            // against the CURRENT log would restore this run's goals beside that run's tree and
            // report it clean.
            return Result.refused(toCycleId, "this point belongs to lineage " + record.lineageId()
                    + ", whose goal history is not on record — its tree could be restored but its "
                    + "goal state could not, and a tree without its goals is not a state to continue from");
        }
        int undone = fold.transitionsUndone();
        // A point taken after goal history existed, folding to nothing, means the history is gone.
        // The restore then touches no goal state at all — and would otherwise report success, which
        // is the silent partial restore this whole design exists to make impossible.
        if (record.goalTransitionSeq() > 0 && fold.statuses().isEmpty()) {
            failures.add("goal history is unavailable — the fold at seq " + record.goalTransitionSeq()
                    + " yielded nothing, so no goal state was restored");
        }
        GoalPlannerService.RewindResult goals;
        try {
            goals = goalPlannerService.restoreGoalStateAsOf(
                    fold.statuses(), fold.existingGoalIds(), record.currentSubGoalId(),
                    record.rootGoalId());
        } catch (Exception e) {
            failures.add("goal state: " + e.getMessage());
            goals = new GoalPlannerService.RewindResult(0, 0, record.currentSubGoalId());
        }

        StateManifest.Restore holders =
                stateManifest.restore(checkpointService.holdersNode(record), objectMapper);
        failures.addAll(holders.failures());

        applyCacheMode(record, mode, failures, treeSource);

        // T4-DQ decision 4: the completeness check, run at the restore rather than asserted at
        // design time. Anything the checkpoint failed to capture, and that actually matters, shows
        // up here as a named diverging component — and the caller is told, rather than continuing
        // on a state that only looks restored.
        List<String> divergences = verifyRestoredState(record);
        for (String component : divergences) {
            failures.add("state diverged after restore: " + component);
        }

        // The loop's state is now that of the target, whatever the checkpoint listing says is newest.
        checkpointService.notePositionAfterRewind(toCycleId, atCycleId);

        Result result = new Result(true, toCycleId, null, mode, holders.restored().size(),
                holders.reset().size(), goals.statusesRestored(), goals.goalsRewoundOut(), undone,
                true, List.copyOf(failures));

        log.warn("T4-DQ: REWOUND to {} (trigger={}, cache={}) — holders restored={} reset={}, "
                        + "goal statuses restored={}, goals rewound out={}, goal transitions undone={}{}",
                toCycleId, trigger, mode, result.holdersRestored(), result.holdersReset(),
                result.goalStatusesRestored(), result.goalsRewoundOut(), undone,
                failures.isEmpty() ? "" : ", failures=" + failures);
        if (behaviourLogger != null) {
            behaviourLogger.rewindPerformed(atCycleId, toCycleId, trigger, mode.name(),
                    result.holdersRestored(), result.holdersReset(), result.goalStatusesRestored(),
                    undone, true, result.failures());
        }
        return result;
    }

    /**
     * Recompute the fingerprint and name every component that does not match what the checkpoint
     * recorded. The cache is deliberately outside the fingerprint: FRESH mode clears it ON PURPOSE,
     * so a mode difference is not a divergence.
     */
    private List<String> verifyRestoredState(CheckpointRecord record) {
        if (record.fingerprint() == null) {
            // Not silence: a rewind nobody could check is a different thing from a clean one.
            return List.of("not verified — this checkpoint carries no readable fingerprint");
        }
        try {
            return stateFingerprintService.current().divergencesFrom(record.fingerprint());
        } catch (Exception e) {
            return List.of("fingerprint could not be recomputed: " + e.getMessage());
        }
    }

    /**
     * The points an OPERATOR could return to right now, newest first: this run's, every earlier
     * run's, and points from before a reset severed the lineage. Everything whose tree is still on
     * disk or in the archive.
     *
     * <p>Deliberately wider than what the loop may choose on its own — see
     * {@link CheckpointService#autoRewindCandidates()}. Reaching back across a run, or across a
     * reset, is exactly the thing that stays a person's call.
     */
    public List<CheckpointRecord> available() {
        return checkpointService.allRestorablePoints(CheckpointService.ACROSS_RUN_LIMIT);
    }

    /**
     * The newest checkpoint strictly older than {@code beforeCycleId} that an automatic rewind may
     * target, or empty. What a policy asks for when it wants "back up one point" without naming
     * one. See {@link CheckpointService#autoRewindCandidates()} for what that excludes.
     */
    public Optional<CheckpointRecord> lastGoodBefore(String beforeCycleId) {
        for (CheckpointRecord record : checkpointService.autoRewindCandidates()) {
            if (beforeCycleId == null || record.cycleId().compareTo(beforeCycleId) < 0) {
                return Optional.of(record);
            }
        }
        return Optional.empty();
    }

    /**
     * The newest checkpoint strictly older than {@code beforeCycleId} whose cycle LANDED WORK, out
     * of those an automatic rewind may target — the last point at which the run was still merging
     * AND still had something to merge toward. Both halves were learned in the field: see
     * {@link CheckpointService#autoRewindCandidates()}, where dropping either one sent a stalled
     * run onto a finished earlier one.
     */
    public Optional<CheckpointRecord> lastMergedBefore(String beforeCycleId) {
        for (CheckpointRecord record : checkpointService.autoRewindCandidates()) {
            if (!record.merged()) continue;
            if (beforeCycleId == null || record.cycleId().compareTo(beforeCycleId) < 0) {
                return Optional.of(record);
            }
        }
        return Optional.empty();
    }

    /**
     * Replace {@code output/} with the checkpoint's tree — staged, then swapped.
     *
     * <p>This is the one destructive step in a rewind, and it operates on the only copy of the
     * artifact the run is building. A delete-then-copy that fails halfway leaves no tree at all,
     * which turns a recoverable bad path into a dead run — the exact outcome the capability exists
     * to prevent. So the snapshot is assembled into a staging directory first and the live tree is
     * only removed once there is a complete replacement to put in its place.
     */
    private boolean restoreTree(String treeSource, List<String> failures) {
        String staging = "output.rewind-staging";
        try {
            Path stagingPath = fileService.resolveSecurePath(staging);
            if (Files.exists(stagingPath)) deleteRecursive(stagingPath);
            fileService.ensureFolderExists(staging);
            fileService.copyFolder(treeSource, staging);
            // The snapshot directory carries T4-CA's state stamp, which belongs to the snapshot
            // rather than to the project — restoring it would plant a stray file in the tree the
            // loop then reads back as source.
            fileService.deleteFile(staging + "/" + MutationSnapshotService.SNAPSHOT_STATE_FILE);
            // An archived point keeps its cache alongside the tree; it is state, not source, and
            // must not be planted into output/ where the loop would read it back as project files.
            fileService.deleteRecursively(staging + "/" + CheckpointArchive.CACHE_SUBDIR);

            if (isEmptyTree(stagingPath)) {
                failures.add("tree: the checkpoint's snapshot staged empty — refusing to replace a "
                        + "live tree with nothing");
                deleteRecursive(stagingPath);
                return false;
            }

            Path output = fileService.resolveSecurePath("output");
            if (Files.exists(output)) deleteRecursive(output);
            Files.move(stagingPath, output);
            return true;
        } catch (IOException | RuntimeException e) {
            failures.add("tree: " + e.getMessage());
            try {
                Path stagingPath = fileService.resolveSecurePath(staging);
                if (Files.exists(stagingPath)) deleteRecursive(stagingPath);
            } catch (IOException | RuntimeException cleanup) {
                log.debug("T4-DQ: could not clear the rewind staging directory: {}", cleanup.getMessage());
            }
            return false;
        }
    }

    private boolean isEmptyTree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.noneMatch(Files::isRegularFile);
        }
    }

    /**
     * FAITHFUL restores the cache copy taken with the checkpoint; FRESH clears it. Running the
     * same rewind both ways and comparing what the loop then does is how we find out whether the
     * cache is a pure optimisation or is quietly steering decisions — which nobody knows today.
     */
    private void applyCacheMode(CheckpointRecord record, CacheMode mode, List<String> failures,
                                String treeSource) {
        try {
            Path cache = fileService.resolveSecurePath(CheckpointService.CACHE_DIR);
            if (Files.exists(cache)) deleteRecursive(cache);
            fileService.ensureFolderExists(CheckpointService.CACHE_DIR);
            if (mode == CacheMode.FAITHFUL) {
                // The checkpoint's own copy when this run took it; the archived copy when the point
                // belongs to an earlier run, whose checkpoints/ went at that run's boundary.
                String copy = checkpointService.cacheCopyPath(record.cycleId());
                boolean fromCheckpoint = record.cacheCaptured() && fileService.exists(copy);
                String archived = treeSource + "/" + CheckpointArchive.CACHE_SUBDIR;
                if (fromCheckpoint) {
                    fileService.copyFolder(copy, CheckpointService.CACHE_DIR);
                } else if (fileService.exists(archived)) {
                    fileService.copyFolder(archived, CheckpointService.CACHE_DIR);
                } else {
                    failures.add("cache: FAITHFUL requested but no cache copy was kept for this point");
                }
            }
        } catch (IOException | RuntimeException e) {
            failures.add("cache: " + e.getMessage());
        }
    }

    private void deleteRecursive(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to delete: " + p, e);
                }
            });
        }
    }

    /**
     * What a rewind did, or why it did not happen. A refusal is a first-class outcome: the caller
     * must be able to tell "returned to that state" from "could not, carry on forward".
     */
    public record Result(boolean performed,
                         String toCycleId,
                         String refusedBecause,
                         CacheMode cacheMode,
                         int holdersRestored,
                         int holdersReset,
                         int goalStatusesRestored,
                         int goalsRewoundOut,
                         int goalTransitionsUndone,
                         boolean treeRestored,
                         List<String> failures) {

        static Result refused(String toCycleId, String why) {
            return new Result(false, toCycleId, why, null, 0, 0, 0, 0, 0, false, List.of());
        }

        /** True when the rewind happened and nothing inside it reported a problem. */
        public boolean clean() {
            return performed && (failures == null || failures.isEmpty());
        }
    }
}
