package com.walpro.autobuilder.agent.evolution;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.ProjectDiffService;
import com.walpro.autobuilder.core.engine.ExecutionAgent;
import com.walpro.autobuilder.core.engine.ExecutionEligibilityExplainer;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.core.service.FingerprintService;
import com.walpro.autobuilder.core.service.MutationApplier;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshot;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshotService;
import com.walpro.autobuilder.graph.evolution.EvolutionAttemptNode;
import com.walpro.autobuilder.graph.evolution.EvolutionGraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class EvolutionAgent implements ExecutionAgent {

    private static final int MAX_RECENT_FINGERPRINTS = 10;
    private static final int MAX_PATHS_RECORDED = 25;

    private final EvolutionLLMAdapter evolutionAdapter;
    private final MutationApplier mutationApplier;
    private final FingerprintService fingerprintService;
    private final ProjectDiffService projectDiffService;
    private final EvolutionGraphService evolutionGraphService;
    private final OrchestrationSnapshotService orchestrationSnapshotService;
    private final ExecutionEligibilityExplainer executionEligibilityExplainer;

    @Override
    public CycleContextData execute(CycleContextData context) {
        log.info("Executing agent (role={})", role());

        if (isAcceptedNotMergedAttempt(context)) {
            log.info("Skipping evolution: current attempt is ACCEPTED but not merged yet (attemptId={}).",
                    context != null ? context.lastAttemptId() : null);
            return context;
        }

        boolean recombinationFromContext = context.hasSignal(BasicSignals.RECOMBINATION_REQUEST);
        boolean divergenceFromContext = context.hasSignal(BasicSignals.DIVERGENCE_REQUEST);
        boolean stagnationDivergence = context.hasSignal(BasicSignals.STAGNATION);
        boolean regressingDivergence = isRegressing(context);

        boolean useDivergent =
                recombinationFromContext ||
                        divergenceFromContext ||
                        stagnationDivergence ||
                        regressingDivergence;

        // One-shot consumption for signals that should not outlive a single evolution attempt.
        if (recombinationFromContext) {
            context = context.withoutSignal(BasicSignals.RECOMBINATION_REQUEST);
        }
        // T2-AO: DIVERGENCE_REQUEST is consumed only after evolution actually produces a new
        // fingerprint. If the LLM ignores the divergence hint and repeats the same mutation,
        // the signal survives into the next cycle and retries. See post-evolution check below.
        if (stagnationDivergence) {
            log.info("EvolutionExecutionAgent: using divergent evolution due to STAGNATION signal.");
            context = context.withoutSignal(BasicSignals.STAGNATION);
        }

        String fpBeforeEvolution = context.lastEvolutionFingerprint();

        try {
            context = runEvolution(context, useDivergent, useDivergent ? "Recombination" : "Evolution");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // T2-AO: consume DIVERGENCE_REQUEST only if the LLM actually produced a different fingerprint.
        if (divergenceFromContext) {
            boolean actuallyDiverged = !Objects.equals(fpBeforeEvolution, context.lastEvolutionFingerprint());
            if (actuallyDiverged) {
                context = context.withoutSignal(BasicSignals.DIVERGENCE_REQUEST);
                log.debug("DIVERGENCE_REQUEST consumed — fingerprint changed after divergent evolution.");
            } else {
                log.debug("DIVERGENCE_REQUEST retained — evolution produced the same fingerprint despite divergence prompt.");
            }
        }

        return context;
    }

    private CycleContextData runEvolution(CycleContextData context,
                                          boolean divergent,
                                          String logPrefix) throws IOException {
        context = ensureAttemptId(context);

        context = divergent
                ? evolutionAdapter.evolveDivergent(context)
                : evolutionAdapter.evolve(context);

        context = updateFingerprint(context);
        logEvolution(logPrefix, context);

        var outcome = context.evolutionOutcome();
        boolean changed = outcome != null && (!outcome.addedOrChangedFiles().isEmpty()
                || !outcome.deletedFiles().isEmpty());

        if (changed) {
            context = context
                    .withEvolutionRanThisCycle(true)
                    .withSignal(Signal.of(BasicSignals.EVOLUTION_OCCURRED, 1.0, 0.1, "evolution"));
        }

        try {
            mutationApplier.applyRegularMutations(context);
            ProjectDiffResult diff = computeProjectDiff(context);
            context = context.withProjectDiff(diff);

            recordEvolutionAttempt(context, divergent);
        } catch (IOException e) {
            log.error("Failed to apply mutations", e);
            return context;
        }

        return context.withLastEvolutionOutcome(outcome); // TODO: should withMerged(!changed) be added here?
    }

    private void logEvolution(String prefix, CycleContextData context) {
        var outcome = context.evolutionOutcome();
        if (outcome == null) {
            log.info("{} produced no outcome.", prefix);
            return;
        }
        log.info("{} produced {} file changes and {} deletions.",
                prefix,
                outcome.addedOrChangedFiles().size(),
                outcome.deletedFiles().size()
        );
    }

    public ProjectDiffResult computeProjectDiff(CycleContextData context) throws IOException {
        Path previous;
        Path current;

        boolean isBootstrapPhase = context.cycleName() == null || context.cycleName().startsWith("bootstrap");
        boolean isFirstMutationAfterBootstrap = context.previousAcceptedCycleName() == null
                && context.cycleName() != null && context.cycleName().startsWith("cycle-");

        if (isBootstrapPhase) {
            previous = null;
            current = mutationApplier.resolveOutputPath();
        } else if (isFirstMutationAfterBootstrap) {
            previous = mutationApplier.resolveOutputPath();
            current = mutationApplier.resolveMutationPath(context.cycleName());
        } else {
            previous = mutationApplier.resolveMutationPath(context.previousAcceptedCycleName());
            current = mutationApplier.resolveMutationPath(context.cycleName());
        }

        return projectDiffService.computeDiff(current, previous);
    }

    private CycleContextData updateFingerprint(CycleContextData context) {
        EvolutionOutcome outcome = context.evolutionOutcome();
        if (outcome == null) return context;

        String newFp = fingerprintService.computeEvolutionFingerprint(outcome);
        if (newFp == null || newFp.isBlank()) return context;

        String lastFp = context.lastEvolutionFingerprint();
        List<String> recent = context.recentEvolutionFingerprints() == null
                ? List.of()
                : context.recentEvolutionFingerprints();

        boolean isRepeat = Objects.equals(newFp, lastFp) || recent.contains(newFp);

        // maintain rolling window (newest first; remove duplicates)
        List<String> updatedRecent = new ArrayList<>(MAX_RECENT_FINGERPRINTS);
        updatedRecent.add(newFp);
        for (String fp : recent) {
            if (fp == null || fp.isBlank()) continue;
            if (Objects.equals(fp, newFp)) continue;
            updatedRecent.add(fp);
            if (updatedRecent.size() >= MAX_RECENT_FINGERPRINTS) break;
        }

        CycleContextData out = context
                .withLastEvolutionFingerprint(newFp)
                .withRecentEvolutionFingerprints(updatedRecent);

        if (isRepeat) {
            out = out
                    .withSignal(Signal.of(BasicSignals.REPETITION_DETECTED, 1.0, 0.05, "EvolutionExecutionAgent"))
                    .withSignal(Signal.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, 0.10, "repetition"));
        } else if (!Objects.equals(newFp, lastFp)) {
            out = out
                    .withSignal(Signal.of(BasicSignals.EVOLUTION_FINGERPRINT_CHANGED, 1.0, 0.05, "EvolutionExecutionAgent"))
                    .withoutSignal(BasicSignals.REPETITION_DETECTED);
        }

        return out;
    }

    /**
     * Coordinator previously used an evaluation RESPONSE message to request divergence if goalProgress=REGRESSING.
     * Without a bus, use evaluation data directly from context.
     */
    private boolean isRegressing(CycleContextData ctx) {
        var interpretation = ctx.evaluationInterpretation();
        if (interpretation != null && interpretation.goalProgress() != null) {
            boolean regressing = interpretation.goalProgress().name().equals("REGRESSING");
            if (regressing) {
                log.info("EvolutionExecutionAgent: divergence requested due to evaluationInterpretation.goalProgress=REGRESSING.");
            }
            return regressing;
        }

        var outcome = ctx.lastEvaluationOutcome();
        if (outcome != null && outcome.goalProgress() != null) {
            boolean regressing = outcome.goalProgress().name().equals("REGRESSING");
            if (regressing) {
                log.info("EvolutionExecutionAgent: divergence requested due to lastEvaluationOutcome.goalProgress=REGRESSING.");
            }
            return regressing;
        }

        return false;
    }

    private void recordEvolutionAttempt(CycleContextData ctx, boolean divergent) {
        if (ctx == null || ctx.cycleName() == null || ctx.cycleName().isBlank()) return;

        String attemptId = ctx.lastAttemptId();
        if (attemptId == null || attemptId.isBlank()) {
            return;
        }

        var out = ctx.evolutionOutcome();
        var diff = ctx.projectDiff();

        int changedCount = out != null && out.addedOrChangedFiles() != null ? out.addedOrChangedFiles().size() : 0;
        int deletedCount = out != null && out.deletedFiles() != null ? out.deletedFiles().size() : 0;

        List<String> changedPaths = out != null && out.addedOrChangedFiles() != null
                ? out.addedOrChangedFiles().stream().map(FileMutation::path).limit(MAX_PATHS_RECORDED).toList()
                : List.of();

        List<String> deletedPaths = out != null && out.deletedFiles() != null
                ? out.deletedFiles().stream().limit(MAX_PATHS_RECORDED).toList()
                : List.of();

        var node = EvolutionAttemptNode.of(
                attemptId,
                ctx.cycleName(),
                divergent,
                ctx.lastEvolutionFingerprint(),
                changedCount,
                deletedCount,
                changedPaths,
                deletedPaths,
                ctx.currentGoalId(),
                ctx.currentSubGoalId(),
                Map.of(
                        "hasProjectDiff", diff != null,
                        "goalDepth", ctx.goalDepth() != null ? ctx.goalDepth() : -1
                )
        );

        evolutionGraphService.saveAttempt(node);
    }

    private CycleContextData ensureAttemptId(CycleContextData ctx) {
        if (ctx == null) return null;

        // Rule: 1 attempt per cycle => attemptId == cycleName.
        // Always overwrite so each cycle gets a fresh ID and deliberation (which runs
        // before evolution) inherits the PREVIOUS cycle's name via previous.toBuilder().
        String attemptId = (ctx.cycleName() != null && !ctx.cycleName().isBlank())
                ? ctx.cycleName()
                : UUID.randomUUID().toString();

        return ctx.withLastAttemptId(attemptId);
    }

    private boolean isAcceptedNotMergedAttempt(CycleContextData ctx) {
        if (ctx == null) return false;

        String attemptId = ctx.lastAttemptId();
        if (attemptId == null || attemptId.isBlank()) return false;

        OrchestrationSnapshot snap = orchestrationSnapshotService.fetchSnapshot(
                ctx.currentGoalId(),
                ctx.currentSubGoalId(),
                attemptId,
                10
        );

        return executionEligibilityExplainer.isAcceptedNotMergedAttempt(snap);
    }

    @Override
    public String name() {
        return "evolution";
    }

    @Override
    public String role() {
        return "evolution";
    }

    @Override
    public Set<SignalType> getSupportedSignals() {
        return BasicSignals.EVOLUTION_SIGNALS;
    }
}
