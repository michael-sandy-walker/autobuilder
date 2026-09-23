package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.build.BuildErrorClassifier;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Category;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifierAgent;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Orchestrates deterministic compile-fix injection. Given the stderr of a failed compile probe
 * and the current cycle context, classifies the error and walks {@link CompileFixResolver}s in
 * declared order; returns the first {@link FixProposal} produced, or empty if none apply.
 *
 * <p>Stateless and cheap. The "one attempt per cycle" guarantee lives at the call site
 * ({@code CompileProbeService}), not here. It is a pure function from (stderr, context) to
 * (fix, provenance) on the default path; see the adaptive-classifier note below for the one
 * exception.
 *
 * <p>The injector covers the high-confidence head of the build-error distribution; the
 * LLM-driven {@code [BUILD_FIX]} path stays as the safety net for everything else
 * ({@link BuildErrorClassifier.Category#UNKNOWN}, symbols without a known fix, etc.).
 *
 * <p>T4-AN-7: classification goes through {@link BuildErrorClassifierAgent}, not the static
 * {@link BuildErrorClassifier#classify(String)} matcher. When the adaptive classifier is enabled
 * the resolvers receive an LLM-produced {@code category} + {@code missingSymbol} that is
 * independent of the regex {@code kind}; when it is disabled the agent degrades to the static
 * matcher, so the default path is unchanged. This means the injector is no longer a pure
 * function of (stderr, context) when adaptive mode is on — but it stays cheap (cache + circuit
 * breaker on the agent), so the "one cheap attempt per cycle" guarantee at the call site holds.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompileFixInjector {

    private final List<CompileFixResolver> resolvers;
    private final FixAttemptLedger ledger;
    private final BuildErrorClassifierAgent classifierAgent;
    private final SymbolImportLookupService symbolImportLookupService;
    private final WellKnownPackageRegistry wellKnownPackageRegistry;

    /**
     * T2-EZ: relays the outcome of a chain-stopped rollback to the run-level ledger. Same
     * separation of concerns as {@link #recordReProbeOutcome}: the probe knows what happened to the
     * files, this class owns the ledger.
     */
    public void noteInjectorFileOutcome(int created, int preserved) {
        ledger.noteInjectorFileOutcome(created, preserved);
    }

    public Optional<FixProposal> inject(String stderr, CycleContextData context) {
        if (stderr == null || stderr.isBlank()) return Optional.empty();

        // T4-AN-7: classify via the agent (adaptive when enabled, static matcher otherwise) so
        // resolvers can branch on the LLM-set category/missingSymbol when the adaptive path runs.
        Classification classification = classifierAgent.classify(stderr);
        // Gate on the universal Category, not the ecosystem-specific Kind. UNKNOWN == "no
        // deterministic handler" regardless of build-tool wording.
        if (classification.category() == Category.UNKNOWN) {
            log.debug("CompileFixInjector: unknown error category — deferring to LLM");
            return Optional.empty();
        }

        String token = BuildErrorClassifier.extractToken(stderr, classification.kind()).orElse(null);
        String outputPath = context != null ? context.outputPath() : null;
        String cycleId = context != null ? context.cycleName() : null;

        for (CompileFixResolver resolver : resolvers) {
            Optional<FixProposal> proposal = resolver.resolve(classification, token, stderr, context);
            if (proposal.isEmpty()) continue;
            // T2-DY: ledger short-circuit — same (rule, paths, content) already failed re-probe
            // ≥ BLACKLIST_AFTER_FAILURES times with no target-file mutation since. Skip this
            // resolver's proposal and let the chain fall through; if every resolver is blacklisted
            // we return empty and the caller drops to the original rollback-and-reject path.
            if (ledger.shouldSkip(proposal.get(), outputPath, cycleId)) {
                continue;
            }
            return proposal;
        }
        log.debug("CompileFixInjector: no resolver matched category={} token={}",
                classification.category(), token);
        return Optional.empty();
    }

    /**
     * T2-DY: writes the post-re-probe outcome for {@code proposal} into the ledger. Called from
     * {@code CompileProbeService.tryInjectAndReProbe} once the second build completes. Same-cycle
     * separation of concerns: this class produces the proposal; the probe records the outcome.
     *
     * <p>Progress-aware (2026-06-10): a fix is assessed by whether <i>its targeted diagnostic</i>
     * disappeared from the re-probe stderr, not by whether the whole build went green. With
     * several independent error classes present, no single fix can turn the build green — the
     * old all-or-nothing outcome recording blacklisted correct partial fixes (observed: the BookService
     * skeleton). A fix that removed its own diagnostic is recorded as a success; a fix whose
     * diagnostic persists is recorded as a failure AND, for {@code symbol-import:*} rules, the
     * failure propagates to the lookup cache / registry so a hallucinated resolution is killed
     * at the source instead of refiring (observed: BookNotFoundException resolved to a wrong
     * Spring import 4x).
     */
    public boolean recordReProbeOutcome(FixProposal proposal, BuildResult reProbeResult, String reProbeStderr,
                                        String outputPath, String cycleId) {
        return recordReProbeOutcome(proposal, reProbeResult, reProbeStderr, outputPath, cycleId, List.of());
    }

    /**
     * T1-GE: as above, with the files this proposal CREATED that the build still reports a
     * diagnostic in. Clearing the targeted diagnostic is progress only if the fix did not bring a
     * defect of its own: a created file that is itself an error location means the diagnostic was
     * moved, not removed, so the attempt is recorded as the failure it is — without that, a
     * skeleton importing an undeclared package was logged as progress while its own error went
     * unattributed. Edited pre-existing files are not charged: they may carry errors the fix
     * never touched.
     */
    public boolean recordReProbeOutcome(FixProposal proposal, BuildResult reProbeResult, String reProbeStderr,
                                        String outputPath, String cycleId,
                                        List<String> createdFilesCarryingDiagnostics) {
        String token = proposal.record().missingToken();
        // T2-EN: the run-level tally sees the RAW result — the progress-aware translation below
        // records a creation rule as success forever (creating X always removes "cannot find
        // symbol X"), which is how the 2026-07-16 12-cycle skeleton recurrence escaped every brake.
        boolean runLevelCapped = ledger.recordRunLevelFire(proposal, reProbeResult, cycleId);
        // T1-FC/T3-BV: same diagnostic-shape test the chain uses — bare substring presence would
        // blacklist a class skeleton whose member-access errors still mention the class name, and a
        // token-less infrastructure-bootstrap (bootstrap-pom) whose own error kind cleared must be
        // recorded as a success even when the build stays red for unrelated missing-class reasons
        // (otherwise the bootstrap accrues failed attempts and gets blacklisted — the wedge).
        boolean targetedDiagnosticGone = reProbeResult == BuildResult.COMPILE_OK
                || com.walpro.autobuilder.core.build.CompileProbeService.fixTargetCleared(proposal, reProbeStderr);

        boolean introducedDefect = createdFilesCarryingDiagnostics != null && !createdFilesCarryingDiagnostics.isEmpty();
        if (reProbeResult == BuildResult.COMPILE_ERROR && targetedDiagnosticGone && introducedDefect) {
            log.info("CompileFixInjector: rule={} removed its targeted diagnostic ({}) but a file it created is itself "
                            + "an error location {} — NOT recording as progress",
                    proposal.record().rule(), token, createdFilesCarryingDiagnostics);
        } else if (reProbeResult == BuildResult.COMPILE_ERROR && targetedDiagnosticGone) {
            log.info("CompileFixInjector: rule={} removed its targeted diagnostic ({}) — recording as progress despite overall COMPILE_ERROR",
                    proposal.record().rule(), token);
            ledger.recordAttempt(proposal, BuildResult.COMPILE_OK, outputPath, cycleId);
            return runLevelCapped;
        }

        ledger.recordAttempt(proposal, reProbeResult, outputPath, cycleId);

        if (reProbeResult == BuildResult.COMPILE_ERROR
                && proposal.record().rule() != null
                && proposal.record().rule().startsWith("symbol-import:")
                && token != null && !token.isBlank()) {
            try {
                symbolImportLookupService.markResolutionFailed(token);
                wellKnownPackageRegistry.markResolutionFailed(token);
            } catch (RuntimeException e) {
                log.debug("CompileFixInjector: markResolutionFailed({}) failed: {}", token, e.getMessage());
            }
        }
        return runLevelCapped;
    }
}
