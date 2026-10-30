package com.walpro.autobuilder.core.init;

import com.walpro.autobuilder.config.AutobuilderConfigProperties;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.core.service.FileService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class SystemResetConfig {

    private final AutobuilderConfigProperties config;
    private final FileService fileService;
    private final GoalPlannerService goalPlannerService;
    private final com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService criteriaVerificationService;
    private final RunIdentityService runIdentityService;

    /**
     * T4-DQ: the goal-transition log carries an in-memory sequence counter mirroring its file.
     * A reset in a LIVE JVM (the graph browser's Reset button) deletes {@code meta/} underneath
     * it, so the counter must be dropped too or the next append resumes numbering above a file
     * that starts empty.
     */
    private final com.walpro.autobuilder.goal.GoalTransitionLog goalTransitionLog;

    /**
     * Startup-time entry point: only resets when {@code autobuilder.service.reset-on-start=true}.
     * Other call sites that need to force a reset (e.g. the manual "Reset" button in the graph
     * browser) should invoke {@link #resetSystemForce()} directly.
     */
    public void resetSystem() throws IOException {
        if (!config.isResetOnStart()) return;
        resetSystemForce();
    }

    /**
     * Performs the reset — lineage severance, filesystem cleanup, root-goal re-seed — regardless
     * of the {@code reset-on-start} flag. Exposed so the graph browser can offer a manual reset
     * action without needing to restart the JVM.
     *
     * <p>Deliberately NOT a graph purge (T4-CJ/T1-D): earlier lineages survive as history and are
     * told apart by their origin {@code lineageId}. Use {@code GoalGraphService.purgeAll()} when an
     * actual wipe is wanted.
     */
    public void resetSystemForce() throws IOException {
        log.warn("Autobuilder system reset triggered (early phase)");

        // T4-BP step 3: clear the completion-verification result cache + call counter so a manual
        // reset starts a clean cost measurement (a fresh JVM already starts at zero).
        criteriaVerificationService.resetCostMetrics();

        // T4-CJ/T1-D: a reset NO LONGER WIPES THE GRAPH. It used to call purgeAll(), which is
        // `MATCH (n) DETACH DELETE n` — every node of every label — so a fresh start destroyed all
        // record of what earlier lineages did. Now the lineage is severed instead (renewLineage()
        // below) and the old nodes stay as history: they carry their own origin lineageId, the
        // reads that drive this run's decisions are lineage/run-scoped, and the SystemState pointer
        // is a singleton that the re-seed overwrites. purgeAll() remains on the interface as an
        // explicit, deliberate wipe (test isolation, manual admin) — it is simply not what
        // "start fresh" means any more.
        //
        // ORDER MATTERS: keeping history is only safe because the cross-lineage reads were scoped
        // first (goal dedup + the GIVEN_UP guard in particular). Reversing that order would let a
        // previous lineage's goals suppress a new one's.

        fileService.deleteFile("meta/bootstrap.done");
        String seedContent = fileService.readFile("bootstrap/seed.txt");

        // T4-CD slice 3: "cache" LEFT OUT of the wipe again — resolutions are provenance-stamped
        // ecosystem facts, not run facts, and the T4-BM step-6 dependency-fingerprint keying
        // (shipped after the step-6a wipe) makes staleness self-correcting: entries are valid
        // only for their recorded dependency context, and the first differing real fingerprint
        // drops them. The pollution classes that motivated the 6a wipe (bare-simple-name keying,
        // domain-type NONE rows, stale version pins) are each closed by later mechanisms —
        // member-token gate at write, BOM-managed version omission at the pom-write home,
        // discovery-first ordering (precondition analysis on the T4-CD ticket, 2026-07-17).
        // T4-DQ: "checkpoints" is cleared with the rest — those hold the unpruned per-cycle build
        // output. "checkpoint-archive" is deliberately NOT in this list: it holds the source-only
        // tree copies that let a rewind reach a point from an earlier run, and it is bounded by
        // count rather than by the run boundary. The graph is not purged either, so the index and
        // the goal-status transitions survive with it.
        for (String dir : List.of("output", "mutations", "snapshots", "meta", "checkpoints", "llm-recordings", "branches")) {
            Path dirPath = fileService.resolveSecurePath(dir);
            if (dirPath.toFile().exists()) {
                deleteRecursive(dirPath);
                log.info("Cleaned: {}", dir);
            }
        }
        
        // Recreate base folders after clean reset
        for (String dir : List.of("output", "mutations", "snapshots", "meta", "checkpoints", "responses/bootstrap")) {
            fileService.ensureFolderExists(dir);
            log.info("Created folder: {}", dir);
        }

        // T4-CJ: a reset severs the lineage — renew BEFORE the root goal is re-seeded so the
        // seed goals' graph writes already carry the new lineage id.
        goalTransitionLog.forgetCachedSeq();
        runIdentityService.renewLineage();

        String goalId = "goal-" + java.util.UUID.randomUUID();
        goalPlannerService.resetInitialGoal(goalId, seedContent, "reset");
        log.info("Goal stack reset with root goal: {}", seedContent);
    }

    private void deleteRecursive(Path dir) throws IOException {
        try (Stream<Path> walk = java.nio.file.Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            java.nio.file.Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to delete: " + path, e);
                        }
                    });
        }
    }
}
