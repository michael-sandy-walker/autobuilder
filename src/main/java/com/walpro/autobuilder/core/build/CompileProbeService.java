package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.inject.CompileFixInjector;
import com.walpro.autobuilder.core.build.inject.FixProposal;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.merge.MergeRollbackPoint;
import com.walpro.autobuilder.core.merge.MergeRollbackService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * T1-EC: tentative-merge probe. Writes the proposed mutation files to {@code output/} and runs
 * the configured build command. On COMPILE_OK the files stay on disk so the LLM evaluator (and
 * later, MergeAgent's commit phase) sees real on-disk state without a redundant write+compile
 * round-trip. On COMPILE_FAIL the files are rolled back to their pre-probe content so the
 * working tree is clean for the next attempt.
 *
 * <p>Cheap-before-expensive: a local {@code mvn compile} is far cheaper than an LLM evaluation
 * call. Running the compile first lets us reject mutations that don't compile without ever
 * paying for an LLM call — and avoids an entire wasted merge-and-rollback cycle.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompileProbeService {

    private final FileService fileService;
    private final MergeRollbackService mergeRollbackService;
    private final BuildExecutor buildExecutor;
    private final CompileFixInjector compileFixInjector;
    private final PackageFragmentationCheck packageFragmentationCheck;

    @Value("${file.base-path:}")
    private String fileBasePath;

    /** T4-BH: optional behaviour trace logger — emits {@code package_fragmentation_detected} on probe-time gate. */
    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    /**
     * Outcome of one probe call. {@code appliedFixes} carries any deterministic compile fixes the
     * injector applied during this call (empty when none). On the re-probe path, the rollback point
     * is {@code null} if the second build still failed (files already rolled back), or set to the
     * extended snapshot covering both LLM and injector files when the second build cleared.
     */
    public record ProbeResult(BuildOutcome outcome, MergeRollbackPoint rollbackPoint, List<FixProposal> appliedFixes) {
        public ProbeResult(BuildOutcome outcome, MergeRollbackPoint rollbackPoint) {
            this(outcome, rollbackPoint, List.of());
        }
        public ProbeResult {
            appliedFixes = appliedFixes == null ? List.of() : List.copyOf(appliedFixes);
        }
        public boolean filesOnDisk() {
            return rollbackPoint != null;
        }
    }

    /**
     * Probes whether the proposed mutation in {@code context} compiles.
     *
     * <p>Returns:
     * <ul>
     *   <li>{@link BuildResult#SKIPPED} (no rollback point) when the build executor is disabled
     *       or there are no files to probe — caller should proceed as if no probe ran.</li>
     *   <li>{@link BuildResult#COMPILE_OK} (rollback point set) — files are on disk and ready
     *       for evaluation/merge. Caller is responsible for either keeping them (on accept) or
     *       passing the rollback point back to {@link #rollback} (on reject).</li>
     *   <li>{@link BuildResult#COMPILE_ERROR} (no rollback point — already restored) — files
     *       were rolled back to their pre-probe content. Caller should treat as REJECTED.</li>
     * </ul>
     */
    public ProbeResult probe(CycleContextData context) {
        return probe(context, true);
    }

    /**
     * Probe with explicit injector control. Pass {@code allowInjection = false} from contexts
     * where the injector must not fire — notably MergeAgent's deferred-mutation re-probe, where
     * a fix would leak into the current cycle and the deferred mutation should be rejected
     * cleanly rather than masked.
     */
    public ProbeResult probe(CycleContextData context, boolean allowInjection) {
        if (!buildExecutor.isEnabled()) {
            return new ProbeResult(BuildOutcome.skipped("build executor disabled"), null);
        }

        List<FileMutation> mutations = context.evolutionOutcome() != null
                ? context.evolutionOutcome().addedOrChangedFiles()
                : null;
        if (mutations == null || mutations.isEmpty()) {
            return new ProbeResult(BuildOutcome.skipped("no changed files in evolution outcome"), null);
        }

        String outputPath = context.outputPath();
        String cycleId = context.cycleName() + "-probe";

        // T4-BH: single write-time gate for the "no two .java files declare the same top-level
        // simple name under different packages" invariant. Runs BEFORE we write any file —
        // covers the normal evolution path (called from EvaluationAgent) and the deferred-mutation
        // re-stage path (called from DeferredMutationRestager) transitively. A conflict short-
        // circuits with FRAGMENTATION_REJECTED; no build runs, no rollback point is needed
        // because the working tree is unchanged.
        Map<String, String> proposedFiles = new LinkedHashMap<>();
        for (FileMutation mutation : mutations) {
            if (mutation == null || mutation.path() == null || mutation.content() == null) continue;
            proposedFiles.put(mutation.path(), mutation.content());
        }
        Optional<PackageFragmentationCheck.Conflict> fragmentation =
                packageFragmentationCheck.check(proposedFiles, outputPath);
        if (fragmentation.isPresent()) {
            PackageFragmentationCheck.Conflict c = fragmentation.get();
            String desc = String.format(
                    "T4-BH: package fragmentation — type %s already exists at %s; rejecting fragmenting file %s",
                    c.simpleName(), c.existingPath(), c.candidatePath());
            log.warn("{}", desc);
            if (behaviourLogger != null) {
                behaviourLogger.packageFragmentationDetected(
                        context.cycleName(), c.simpleName(), c.existingPath(), c.candidatePath());
            }
            BuildOutcome outcome = new BuildOutcome(
                    BuildResult.FRAGMENTATION_REJECTED, desc, 0L, "", context.cycleName());
            return new ProbeResult(outcome, null, List.of());
        }

        Map<String, String> originalContents = new LinkedHashMap<>();
        Set<String> newlyAddedPaths = new LinkedHashSet<>();

        try {
            for (FileMutation mutation : mutations) {
                if (mutation == null || mutation.path() == null || mutation.content() == null) continue;
                String relPath = outputPath + "/" + mutation.path();
                boolean existed = fileService.exists(relPath);
                String originalContent = existed ? fileService.readString(relPath) : "";
                originalContents.put(mutation.path(), originalContent);
                if (!existed) {
                    newlyAddedPaths.add(mutation.path());
                }
                Path outputFile = fileService.resolveSecurePath(relPath);
                fileService.writeFileResolved(outputFile, mutation.content());
            }
        } catch (IOException e) {
            log.warn("T1-EC: probe write failed: {}", e.getMessage());
            // Best-effort restore of any files we managed to write before the IOException.
            MergeRollbackPoint partial = mergeRollbackService.snapshot(cycleId, originalContents, newlyAddedPaths);
            try { mergeRollbackService.rollback(partial, outputPath, fileService); } catch (Exception ignored) {}
            return new ProbeResult(BuildOutcome.skipped("probe write failed: " + e.getMessage()), null);
        }

        MergeRollbackPoint rollbackPoint = mergeRollbackService.snapshot(cycleId, originalContents, newlyAddedPaths);
        BuildOutcome outcome = buildExecutor.execute(fileBasePath, cycleId);
        log.info("T1-EC: probe result={} ({} files written)", outcome.result(), originalContents.size());

        if (outcome.result() == BuildResult.COMPILE_ERROR) {
            ProbeResult injected = allowInjection
                    ? tryInjectAndReProbe(context, outcome, outputPath, cycleId, originalContents, newlyAddedPaths)
                    : null;
            if (injected != null) return injected;
            // No fix or fix-write failed — restore so the working tree is clean for the next attempt.
            mergeRollbackService.rollback(rollbackPoint, outputPath, fileService);
            return new ProbeResult(outcome, null, List.of());
        }

        // COMPILE_OK (or TIMEOUT/SKIPPED): leave files on disk; caller decides what to do next.
        return new ProbeResult(outcome, rollbackPoint, List.of());
    }

    /**
     * One-shot injector path: ask {@link CompileFixInjector} for a deterministic fix, write it to
     * {@code output/} and {@code mutations/&lt;cycleId&gt;/} (so MergeAgent and persistence see it),
     * re-run the build once. Returns:
     * <ul>
     *   <li>{@code COMPILE_OK} {@link ProbeResult} with extended rollback + applied fixes when the
     *       re-probe clears.</li>
     *   <li>{@code COMPILE_ERROR} {@link ProbeResult} with files already rolled back and applied
     *       fixes still recorded — caller knows what was tried.</li>
     *   <li>{@code null} when no fix was produced or the write failed — caller falls back to the
     *       original rollback-and-reject path.</li>
     * </ul>
     */
    private ProbeResult tryInjectAndReProbe(CycleContextData context,
                                            BuildOutcome firstOutcome,
                                            String outputPath,
                                            String cycleId,
                                            Map<String, String> originalContents,
                                            Set<String> newlyAddedPaths) {
        Optional<FixProposal> proposalOpt = compileFixInjector.inject(firstOutcome.stderrTail(), context);
        if (proposalOpt.isEmpty()) return null;
        FixProposal proposal = proposalOpt.get();

        // T1-EO: directory holding the LLM mutations this injector fix augments. When evolution
        // ran this cycle the mutations live in mutations/<cycleId>/; otherwise they were carried
        // over from a prior cycle and live at context.mutationPath(). This mirrors
        // MutationSnapshotService.persistMutationState's resolution. context.mutationPath() is
        // only refreshed post-cycle by CyclePersistenceService, so within an evolution cycle it
        // still points at the PREVIOUS cycle — trusting it unconditionally mirrors the fix into
        // the wrong dir, and MergeAgent then re-reads the LLM's pre-injector variant from disk
        // and clobbers the injector edit (observed 2026-05-21: symbol-import fix lost on merge).
        String mutationDir = context.evolutionRanThisCycle()
                || context.mutationPath() == null || context.mutationPath().isBlank()
                ? "mutations/" + context.cycleName()
                : context.mutationPath();

        try {
            for (FileMutation m : proposal.mutations()) {
                if (m == null || m.path() == null || m.content() == null) continue;
                String relPath = outputPath + "/" + m.path();
                // Capture original for rollback only the first time we touch a path this cycle.
                if (!originalContents.containsKey(m.path())) {
                    boolean existed = fileService.exists(relPath);
                    originalContents.put(m.path(), existed ? fileService.readString(relPath) : "");
                    if (!existed) newlyAddedPaths.add(m.path());
                }
                fileService.writeFileResolved(fileService.resolveSecurePath(relPath), m.content());
                // Mirror into the cycle's mutation dir so MergeAgent picks the file up alongside
                // the LLM-produced mutations and the meta.json provenance can reference it.
                fileService.writeFile(mutationDir + "/" + m.path(), m.content());
            }
        } catch (IOException e) {
            log.warn("CompileFixInjector: write failed: {}", e.getMessage());
            return null;
        }

        MergeRollbackPoint extendedRollback = mergeRollbackService.snapshot(cycleId, originalContents, newlyAddedPaths);
        BuildOutcome secondOutcome = buildExecutor.execute(fileBasePath, cycleId);
        log.info("CompileFixInjector: re-probe after rule={} result={}",
                proposal.record().rule(), secondOutcome.result());

        List<FixProposal> applied = new ArrayList<>(1);
        applied.add(proposal);

        if (secondOutcome.result() == BuildResult.COMPILE_OK) {
            return new ProbeResult(secondOutcome, extendedRollback, applied);
        }
        // Second build still failed — restore working tree and return the second (more informative)
        // outcome with applied-fix provenance so the evaluator/planner can see what was tried.
        mergeRollbackService.rollback(extendedRollback, outputPath, fileService);
        return new ProbeResult(secondOutcome, null, applied);
    }

    /** Restores files to the snapshot captured by {@link #probe}. Used on LLM rejection after a successful compile. */
    public void rollback(MergeRollbackPoint rollbackPoint, String outputPath) {
        if (rollbackPoint == null) return;
        mergeRollbackService.rollback(rollbackPoint, outputPath, fileService);
    }
}
