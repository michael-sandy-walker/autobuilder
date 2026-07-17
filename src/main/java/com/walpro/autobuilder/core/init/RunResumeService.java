package com.walpro.autobuilder.core.init;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.config.AutobuilderConfigProperties;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.JavacDirectProbe;
import com.walpro.autobuilder.core.persistence.PreservedArtifactService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * T4-CD slice 1: resume-from-preserved (single lineage). Instead of resetting to an empty
 * target, boot restores {@code output/} from the preserved artifact the parent run's T4-CA
 * pause-transition hook selected and compile-verified, keeps the loaded goal state, and
 * re-grounds it — so a run continues the lineage's best known state rather than being an
 * independent draw that re-scaffolds from zero.
 *
 * <p><b>Loaded state is hypothesis, not fact.</b> Nothing crosses the run boundary as trusted
 * truth: the restored tree is compile-probed at restore time (recorded, never gating), the
 * prior run's banked criteria reads are dropped on ACTIVE subgoals so the loop's own
 * verification re-derives them against what is actually on disk, and a GIVEN_UP root is
 * reactivated — its give-up was a run-relative brake decision of the parent run, not a fact
 * about the artifact. The completion edge (T4-BP-3) re-verifies every ledger criterion before
 * any closure, so kept terminal statuses cannot complete a root unchecked.
 *
 * <p><b>Per-mechanism reset decision table</b> (default RESET — brakes are deliberately
 * run-relative):
 * <ul>
 *   <li>goal tree (graph + {@code meta/GoalStack.json}): KEPT — the point of resume; re-grounded
 *       as above</li>
 *   <li>{@code output/}: REPLACED by the preserved artifact</li>
 *   <li>{@code snapshots/}, {@code mutations/}: CLEARED — per-run lineage segments; the next
 *       T4-CA preserve selects among this run's own snapshots</li>
 *   <li>in-memory brakes (RecurringDefectTracker, FixAttemptLedger, T2-EN tallies, injection
 *       budgets, stagnation counters): RESET naturally by the fresh JVM; {@code planningRunId}
 *       already advances per boot (T2-BW), separating the parent run's GIVEN_UP tracking</li>
 *   <li>{@code cache/} (SymbolImportCache, discovery): KEPT — dependency-fingerprint-keyed and
 *       self-correcting (T4-BM); formal cross-run ecosystem-knowledge policy is slice 3</li>
 *   <li>{@code meta/} flags ({@code bootstrap.done}, {@code build-bootstrap.done}): KEPT — no
 *       re-seed over the restored artifact</li>
 * </ul>
 *
 * <p>Never throws: any failure logs and returns {@code false}, and the caller falls through to
 * the configured fresh-start path ({@code reset-on-start}), so passing both flags on every
 * launch means "resume if possible, else fresh".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunResumeService {

    private static final long VERIFY_TIMEOUT_MS = 120_000L;
    private static final List<String> RUN_SCOPED_DIRS = List.of("mutations", "snapshots");

    private final AutobuilderConfigProperties config;
    private final FileService fileService;
    private final GoalPlannerService goalPlannerService;
    private final ObjectMapper objectMapper;

    /**
     * T4-CD slice 2: the parent run's testament, rendered for the first prompts. Loaded during
     * {@link #attemptResume()} (ownership-checked against the loaded root goal id); null on a
     * fresh start, a foreign testament, or when the parent left none.
     */
    private volatile String priorTestamentPromptBlock;

    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.persistence.RunTestamentService runTestamentService;

    /** The rendered prior-run testament block for the coordinator to seed into cycle 1, or null. */
    public String priorTestamentPromptBlock() {
        return priorTestamentPromptBlock;
    }

    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    /** Restore-time compile verification of the restored tree (recorded, never gating). */
    @Setter
    @Autowired(required = false)
    private JavacDirectProbe buildProbe;

    /**
     * Attempts the lineage resume. Returns {@code true} only when {@code output/} was restored
     * and the goal state re-grounded — the caller must then skip {@code reset-on-start}.
     */
    public boolean attemptResume() {
        if (!config.isResumeFromPreserved()) return false;
        try {
            if (!hasPreservedArtifact()) {
                log.info("T4-CD: resume requested but no preserved artifact at {} — falling through to fresh start.",
                        PreservedArtifactService.PRESERVED_DIR);
                return false;
            }
            GoalEntity root = goalPlannerService.getRootGoal();
            if (root == null) {
                log.info("T4-CD: resume requested but no goal state was loaded — falling through to fresh start.");
                return false;
            }
            if (root.getStatus() == GoalStatus.COMPLETED) {
                log.info("T4-CD: resume requested but the lineage's root goal is COMPLETED — nothing to continue, falling through to fresh start.");
                return false;
            }

            restoreOutputFromPreserved();
            clearRunScopedDirs();
            String verified = verifyRestoredTree();

            boolean rootReactivated = false;
            if (root.getStatus() != GoalStatus.ACTIVE) {
                rootReactivated = !goalPlannerService.reactivateGoal(root.getId()).isEmpty();
            }
            int regrounded = goalPlannerService.regroundActiveSubgoalsForResume(
                    "resume — banked reads re-derive against the restored artifact; run-scoped entity state reset");

            // T4-CD slice 2: consult the parent's testament — ownership-checked (a testament
            // written under a different root goal is a foreign seed's diagnosis, not ours).
            boolean testamentLoaded = false;
            if (runTestamentService != null) {
                priorTestamentPromptBlock = runTestamentService.readForLineage(root.getId())
                        .map(runTestamentService::renderPromptBlock)
                        .orElse(null);
                testamentLoaded = priorTestamentPromptBlock != null;
            }

            PreservedArtifactService.Provenance provenance = readProvenance();
            String fromCycle = provenance != null ? provenance.fromCycle() : null;
            log.info("T4-CD: resumed from preserved artifact (fromCycle={}, verifiedAtRestore={}, rootReactivated={}, regroundedGoals={}, testamentLoaded={}).",
                    fromCycle, verified, rootReactivated, regrounded, testamentLoaded);
            if (behaviourLogger != null) {
                behaviourLogger.runResumed(fromCycle, verified, rootReactivated, regrounded, testamentLoaded);
            }
            return true;
        } catch (Exception e) {
            log.warn("T4-CD: resume failed — falling through to the configured fresh-start path: {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean hasPreservedArtifact() throws IOException {
        if (!fileService.exists(PreservedArtifactService.PRESERVED_DIR)) return false;
        try (Stream<Path> entries = fileService.list(PreservedArtifactService.PRESERVED_DIR)) {
            return entries.findAny().isPresent();
        }
    }

    private void restoreOutputFromPreserved() throws IOException {
        Path output = fileService.resolveSecurePath("output");
        if (Files.exists(output)) {
            deleteRecursive(output);
        }
        fileService.ensureFolderExists("output");
        fileService.copyFolder(PreservedArtifactService.PRESERVED_DIR, "output");
        log.info("T4-CD: restored output/ from {}.", PreservedArtifactService.PRESERVED_DIR);
    }

    private void clearRunScopedDirs() throws IOException {
        for (String dir : RUN_SCOPED_DIRS) {
            Path path = fileService.resolveSecurePath(dir);
            if (Files.exists(path)) {
                deleteRecursive(path);
            }
            fileService.ensureFolderExists(dir);
        }
    }

    private String verifyRestoredTree() throws IOException {
        if (buildProbe == null) return "UNVERIFIED";
        BuildOutcome outcome = buildProbe.execute(
                fileService.resolveSecurePath("output").toString(), "resume-verify", VERIFY_TIMEOUT_MS);
        String result = outcome != null ? String.valueOf(outcome.result()) : "UNVERIFIED";
        if (outcome != null && outcome.failed()) {
            log.warn("T4-CD: restored tree fails the restore-time compile probe ({}) — resuming anyway; the loop's build gates own repair.",
                    result);
        }
        return result;
    }

    private PreservedArtifactService.Provenance readProvenance() {
        try {
            return objectMapper.readValue(
                    fileService.readString(PreservedArtifactService.PROVENANCE_FILE),
                    PreservedArtifactService.Provenance.class);
        } catch (IOException | RuntimeException e) {
            log.debug("T4-CD: preserved-artifact provenance unreadable (resuming without it): {}", e.getMessage());
            return null;
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
}
