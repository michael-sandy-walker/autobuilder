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

    /**
     * T4-AN-6 / T3-BV: enables chaining deterministic fixes within one compile probe. When
     * disabled, a single injector attempt runs (the original one-shot behaviour). When enabled
     * the chain has no fixed pass ceiling — it terminates on its three natural bounds: no
     * resolver produces a proposal, the build goes green, or a pass fails to strictly reduce
     * the distinct-error count (a strictly decreasing non-negative count guarantees
     * termination in at most initial-error-count passes).
     */
    @Setter
    @Value("${autobuilder.build.inner-fix-loop.enabled:false}")
    private boolean fixChainEnabled = false;

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
    public record ProbeResult(BuildOutcome outcome, MergeRollbackPoint rollbackPoint, List<FixProposal> appliedFixes,
                              PackageFragmentationCheck.Conflict fragmentationConflict) {
        public ProbeResult(BuildOutcome outcome, MergeRollbackPoint rollbackPoint) {
            this(outcome, rollbackPoint, List.of(), null);
        }
        public ProbeResult(BuildOutcome outcome, MergeRollbackPoint rollbackPoint, List<FixProposal> appliedFixes) {
            this(outcome, rollbackPoint, appliedFixes, null);
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
            // T2-DZ: carry the structured conflict so EvaluationAgent can record planning-time
            // feedback — the probe veto alone leaves the planner re-proposing the same relocation.
            return new ProbeResult(outcome, null, List.of(), c);
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
     * T4-AN-6: inner build-fix chaining loop. Asks {@link CompileFixInjector} for a deterministic
     * fix, writes it to {@code output/} and {@code mutations/<cycleId>/} (so MergeAgent and
     * persistence see it), re-runs the build — and, while the build is still red and the chain
     * budget ({@code autobuilder.build.inner-fix-loop.max-fixes-per-probe}) allows, repeats
     * against the <i>new</i> stderr. With several independent error classes present no single
     * fix can turn the build green (observed 2026-06-10: missing BookService + missing local
     * exceptions + missing member — four classes, so every one-shot fix "failed" and was
     * blacklisted); chaining lets each pass target the next class. The T2-DY ledger keeps
     * known-failed proposals out of the chain, which is what makes the loop safe.
     *
     * <p>Returns:
     * <ul>
     *   <li>{@code COMPILE_OK} {@link ProbeResult} with extended rollback + applied fixes when
     *       the chain clears the build.</li>
     *   <li>{@code COMPILE_ERROR} {@link ProbeResult} with files already rolled back and all
     *       applied fixes recorded — caller knows what was tried.</li>
     *   <li>{@code null} when no fix was produced at all or the first write failed — caller
     *       falls back to the original rollback-and-reject path.</li>
     * </ul>
     */
    private ProbeResult tryInjectAndReProbe(CycleContextData context,
                                            BuildOutcome firstOutcome,
                                            String outputPath,
                                            String cycleId,
                                            Map<String, String> originalContents,
                                            Set<String> newlyAddedPaths) {
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

        List<FixProposal> applied = new ArrayList<>();
        // (proposal, the outcome of the re-probe right after it) — recorded post-rollback on the
        // failure path so the T2-DY ledger digests match what the next cycle reads from disk.
        List<Map.Entry<FixProposal, BuildOutcome>> attempts = new ArrayList<>();
        BuildOutcome currentOutcome = firstOutcome;
        MergeRollbackPoint extendedRollback = null;

        for (int pass = 0; pass == 0 || fixChainEnabled; pass++) {
            Optional<FixProposal> proposalOpt = compileFixInjector.inject(currentOutcome.stderrTail(), context);
            if (proposalOpt.isEmpty()) break;
            FixProposal proposal = proposalOpt.get();

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
                if (applied.isEmpty()) return null;
                break;
            }

            extendedRollback = mergeRollbackService.snapshot(cycleId, originalContents, newlyAddedPaths);
            BuildOutcome secondOutcome = buildExecutor.execute(fileBasePath, cycleId);
            log.info("CompileFixInjector: re-probe after rule={} result={} (pass {})",
                    proposal.record().rule(), secondOutcome.result(), pass + 1);

            applied.add(proposal);
            attempts.add(Map.entry(proposal, secondOutcome));

            if (secondOutcome.result() == BuildResult.COMPILE_OK) {
                // T2-DY: record the successes so any prior blacklist for these proposals clears.
                for (Map.Entry<FixProposal, BuildOutcome> a : attempts) {
                    recordReProbeSafely(a.getKey(), a.getValue(), outputPath, context.cycleName());
                }
                return new ProbeResult(secondOutcome, extendedRollback, applied);
            }

            // T3-BV: progress-based termination — the chain's binding rule. Progress means the
            // pass either removed its own targeted diagnostic (same measure the T2-DY ledger
            // uses — a closure walk legitimately reveals NEW errors while clearing old ones, so
            // total count alone is the wrong test) or strictly shrank the total error count
            // (covers token-less fixes like pom bootstraps). A pass that did neither
            // (wrong-package skeleton, hallucinated import) would spin on the same wall forever.
            // Termination: tokens are drawn from the finite set of symbols the project
            // references, the ledger blacklists repeat proposals, and resolvers return empty
            // when nothing matches — the chain cannot run unbounded.
            String targetedToken = proposal.record().missingToken();
            boolean targetedDiagnosticGone;
            if (targetedToken != null && !targetedToken.isBlank()) {
                targetedDiagnosticGone = secondOutcome.stderrTail() != null
                        && !secondOutcome.stderrTail().contains(targetedToken);
            } else {
                // Token-less (config-level) fix, e.g. bootstrap-pom: its diagnostic is the error
                // *kind*, not a symbol. Converting "no POM" into ordinary compile errors is
                // progress the count cannot see (observed 2026-06-11: bootstrap-pom chains
                // stopped after pass 1 although the failure class had changed).
                targetedDiagnosticGone = BuildErrorClassifier.classify(secondOutcome.stderrTail()).kind()
                        != proposal.record().kind();
            }
            boolean errorCountShrank = distinctErrorCount(secondOutcome) < distinctErrorCount(currentOutcome);
            currentOutcome = secondOutcome;
            if (!targetedDiagnosticGone && !errorCountShrank) {
                log.info("CompileFixInjector: chain stopped — no progress after rule={} (targeted diagnostic persists, error count not reduced)",
                        proposal.record().rule());
                break;
            }
        }

        if (applied.isEmpty()) return null;

        // Chain stopped (no proposal or no progress), build still failing — restore working tree and
        // return the last (most informative) outcome with applied-fix provenance so the
        // evaluator/planner can see what was tried.
        mergeRollbackService.rollback(extendedRollback, outputPath, fileService);
        // T2-DY: record AFTER the rollback so the ledger snapshots the stable post-rollback content
        // (the original bytes the proposals reacted to) rather than the failed applied content. The
        // ledger keys blacklist invalidation off this digest, so it must match what the next cycle's
        // shouldSkip will read from disk — otherwise the blacklist self-clears on the rollback.
        for (Map.Entry<FixProposal, BuildOutcome> a : attempts) {
            recordReProbeSafely(a.getKey(), a.getValue(), outputPath, context.cycleName());
        }
        return new ProbeResult(currentOutcome, null, applied);
    }

    /**
     * T3-BV: distinct-error measure for chain-progress decisions. Prefers the build tool's own
     * total ("N errors" — survives tail-capping because it prints at the end of the output);
     * falls back to counting distinct per-file diagnostic lines, then to 1 for any unparseable
     * failing output (so a no-progress comparison still terminates the chain).
     */
    static int distinctErrorCount(BuildOutcome outcome) {
        if (outcome == null || outcome.result() != BuildResult.COMPILE_ERROR) return 0;
        String stderr = outcome.stderrTail();
        if (stderr == null || stderr.isBlank()) return 1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s+errors?\\b").matcher(stderr);
        int reported = -1;
        while (m.find()) reported = Integer.parseInt(m.group(1));
        if (reported >= 0) return reported;
        long diagnosticLines = stderr.lines()
                .filter(l -> l.matches(".*\\.java:\\[\\d+,\\d+\\].*"))
                .distinct()
                .count();
        return diagnosticLines > 0 ? (int) diagnosticLines : 1;
    }

    /** T2-DY: records the re-probe outcome on the ledger, swallowing ledger failures. */
    private void recordReProbeSafely(FixProposal proposal, BuildOutcome reProbeOutcome,
                                     String outputPath, String cycleId) {
        try {
            compileFixInjector.recordReProbeOutcome(proposal, reProbeOutcome.result(),
                    reProbeOutcome.stderrTail(), outputPath, cycleId);
        } catch (RuntimeException e) {
            log.debug("T2-DY: ledger recordAttempt failed: {}", e.getMessage());
        }
    }

    /** Restores files to the snapshot captured by {@link #probe}. Used on LLM rejection after a successful compile. */
    public void rollback(MergeRollbackPoint rollbackPoint, String outputPath) {
        if (rollbackPoint == null) return;
        mergeRollbackService.rollback(rollbackPoint, outputPath, fileService);
    }
}
