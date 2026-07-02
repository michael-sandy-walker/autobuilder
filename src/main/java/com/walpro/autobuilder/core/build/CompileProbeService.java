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
    private final com.walpro.autobuilder.core.build.inject.TestDependencyEnsurer testDependencyEnsurer;

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

    /**
     * T4-BN-2 follow-up (2026-06-23): when false (default), a compile failure confined to TEST
     * sources does NOT reject + roll back the mutation — the main work is kept and the test break is
     * surfaced as a steering diagnostic for the next cycle (the completion-edge gates remain the
     * backstop that tests must actually compile + pass). Set true to restore the legacy behaviour
     * where a test break gates the mutation exactly like a main break — the main-evolution freeze
     * that drove the 06-17→06-23 test-seed cliff (0/15 fulfilled vs 3/3 on plain seeds).
     */
    @Setter
    @Value("${autobuilder.build.test-compile-blocks-merge:false}")
    private boolean testCompileBlocksMerge = false;

    /** T4-BH: optional behaviour trace logger — emits {@code package_fragmentation_detected} on probe-time gate. */
    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    /** T2-EF: optional run-level recurring-defect tracker, fed the post-inner-loop residual. */
    @Setter
    @Autowired(required = false)
    private RecurringDefectTracker recurringDefectTracker;

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
                // T1-FE: capture the original only on FIRST touch — the LLM emits the same path
                // twice in one response often enough that the second iteration would otherwise
                // read back the just-staged content and record IT as "original"; the rollback then
                // writes the rejected mutation into the baseline permanently (2026-07-03 field
                // wedge: poisoned BookController referenced a deleted BookService, every later
                // probe failed on the baseline, zero merges for 45 min). Same guard as the
                // injector inner loop below.
                if (!originalContents.containsKey(mutation.path())) {
                    boolean existed = fileService.exists(relPath);
                    originalContents.put(mutation.path(), existed ? fileService.readString(relPath) : "");
                    if (!existed) {
                        newlyAddedPaths.add(mutation.path());
                    }
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
        emitBuildFixAttempt(context.cycleName(), "probe", 0, null, outcome);

        // T4-BN-2 follow-up: a compile failure confined to test sources (main compiles clean) must
        // STEER, not GATE. Keep the mutation on disk (like COMPILE_OK) so main evolution proceeds and
        // tests can iterate; the outcome still carries the test stderr, which BuildResultPromptBlock
        // surfaces next cycle. The completion-edge gates are the backstop that tests really pass.
        if (!testCompileBlocksMerge
                && outcome.result() == BuildResult.COMPILE_ERROR
                && TestOnlyCompileFailure.isTestOnly(outcome)) {
            log.info("T4-BN-2: probe failure is TEST-ONLY (main compiles) — keeping mutation, surfacing as steering");
            // T4-BN-3 deterministic slice: the dominant test-only break is missing test deps
            // (cannot find symbol: Test/Mock/ExtendWith). Add the project's test starter to the pom
            // deterministically so the next probe can resolve them. Idempotent; persists on disk.
            testDependencyEnsurer.ensure();
            observeResidual(context, outcome, List.of());
            return new ProbeResult(outcome, rollbackPoint, List.of());
        }

        if (outcome.result() == BuildResult.COMPILE_ERROR) {
            ProbeResult injected = allowInjection
                    ? tryInjectAndReProbe(context, outcome, outputPath, cycleId, originalContents, newlyAddedPaths)
                    : null;
            if (injected != null) {
                // T2-EF: feed the run-level recurring-defect tracker with the POST-inner-loop
                // residual — a solved-but-recurring fix the inner loop handles does not count as
                // the dominant defect, and a green re-probe records its fixes as standing knowledge.
                observeResidual(context, injected.outcome(), injected.appliedFixes());
                return injected;
            }
            // No fix or fix-write failed — restore so the working tree is clean for the next attempt.
            mergeRollbackService.rollback(rollbackPoint, outputPath, fileService);
            observeResidual(context, outcome, List.of());
            return new ProbeResult(outcome, null, List.of());
        }

        // COMPILE_OK (or TIMEOUT/SKIPPED): leave files on disk; caller decides what to do next.
        observeResidual(context, outcome, List.of());
        return new ProbeResult(outcome, rollbackPoint, List.of());
    }

    /** T2-EF: hand the residual outcome to the recurring-defect tracker when one is wired. */
    private void observeResidual(CycleContextData context, BuildOutcome outcome, List<FixProposal> appliedFixes) {
        if (recurringDefectTracker == null) return;
        try {
            recurringDefectTracker.observe(outcome, appliedFixes, context.currentSubGoalId(), context.cycleName());
        } catch (Exception e) {
            log.debug("T2-EF: recurring-defect observe failed: {}", e.getMessage());
        }
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
            emitBuildFixAttempt(context.cycleName(), "inner-loop", pass + 1, proposal, secondOutcome);

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
            boolean targetedDiagnosticGone = fixTargetCleared(proposal, secondOutcome.stderrTail());
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
     * T5-N: attempt-level build trace. {@code proposal} is null for the initial probe
     * execution (no fix involved); errorKind then comes from classifying the failing stderr.
     */
    private void emitBuildFixAttempt(String cycleName, String source, int attemptNumber,
                                     FixProposal proposal, BuildOutcome outcome) {
        if (behaviourLogger == null) return;
        String rule = proposal != null ? proposal.record().rule() : null;
        String missingToken = proposal != null ? proposal.record().missingToken() : null;
        List<String> mutatedFiles = proposal != null ? proposal.record().touchedPaths() : List.of();
        String errorKind = null;
        if (proposal != null) {
            errorKind = proposal.record().kind() != null ? proposal.record().kind().name() : null;
        } else if (outcome.result() == BuildResult.COMPILE_ERROR) {
            BuildErrorClassifier.Classification c = BuildErrorClassifier.classify(outcome.stderrTail());
            errorKind = c != null && c.kind() != null ? c.kind().name() : null;
        }
        behaviourLogger.buildFixAttempt(cycleName, source, attemptNumber, rule, errorKind,
                missingToken, mutatedFiles, outcome.result().name(), outcome.durationMs());
    }

    /**
     * T3-BV: a token's diagnostic is gone when no remaining error names the token in a
     * diagnostic-bearing position — a {@code symbol: <kind> <token>} line or a
     * {@code package <token> does not exist} line. Bare substring presence is NOT the test:
     * after a class skeleton lands, every member-access error mentions the class in
     * {@code location:} lines ({@code symbol: method getBookId() location: class Loan}), which
     * proves the class reference RESOLVED — rolling the skeleton back on that evidence
     * re-creates the starvation the fix exists for (observed 2026-06-11 22:27: Loan skeleton
     * rolled back although its {@code symbol: class Loan} diagnostic had cleared).
     */
    /**
     * T1-FC: did the fix clear its OWN targeted diagnostic? A token-bearing fix succeeds when its
     * symbol line is gone ({@link #targetedDiagnosticGone(String, String)}). A token-less
     * infrastructure-bootstrap fix (e.g. {@code bootstrap-pom} for {@code MISSING_POM}) succeeds when
     * its error <em>kind</em> is gone from the re-probe — the build staying red for unrelated
     * missing-class reasons does NOT make the bootstrap a failure. One home for the "did this fix
     * resolve its target?" question: the inner-loop chain-progress test and the T2-DY ledger's
     * success/failure recording both call it, so they can no longer disagree (the disagreement was
     * the bootstrap-pom-blacklist wedge — the chain treated a kind-change as progress while the
     * ledger recorded the same re-probe as a failure and blacklisted the bootstrap).
     */
    public static boolean fixTargetCleared(com.walpro.autobuilder.core.build.inject.FixProposal proposal,
                                           String reProbeStderr) {
        if (proposal == null) return false;
        String token = proposal.record().missingToken();
        if (token != null && !token.isBlank()) {
            return targetedDiagnosticGone(reProbeStderr, token);
        }
        // Token-less (config-level) fix: its diagnostic is the error *kind*, not a symbol.
        return BuildErrorClassifier.classify(reProbeStderr == null ? "" : reProbeStderr).kind()
                != proposal.record().kind();
    }

    public static boolean targetedDiagnosticGone(String stderr, String token) {
        if (stderr == null || stderr.isBlank()) return true;
        String quoted = java.util.regex.Pattern.quote(token);
        java.util.regex.Pattern symbolLine = java.util.regex.Pattern.compile(
                "symbol:\\s*(?:class|interface|enum|variable|method|static)?\\s+" + quoted + "\\b");
        java.util.regex.Pattern packageLine = java.util.regex.Pattern.compile(
                "package\\s+" + quoted + "\\s+does not exist");
        return !symbolLine.matcher(stderr).find() && !packageLine.matcher(stderr).find();
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
