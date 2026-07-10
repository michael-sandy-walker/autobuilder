package com.walpro.autobuilder.agent.evolution;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.graph.evaluation.EvaluationGraphFacade;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.ProjectDiffService;
import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.protocol.evolution.CompileDiagnosticPromptBlock;
import com.walpro.autobuilder.protocol.evolution.EvolutionProtocolContract;
import com.walpro.autobuilder.core.engine.ExecutionAgent;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.ChannelRequest;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.core.service.FingerprintService;
import com.walpro.autobuilder.core.service.MutationApplier;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.graph.evolution.EvolutionAttemptNode;
import com.walpro.autobuilder.graph.evolution.EvolutionGraphService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
    private final EvaluationGraphFacade evaluationGraphFacade;
    private final GoalPlannerService goalPlannerService;
    private final com.walpro.autobuilder.protocol.PromptTokenBudget promptTokenBudget;

    /** T5-H: optional behaviour trace logger. */
    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    @Override
    public CycleContextData execute(CycleContextData context) {
        log.info("Executing agent (role={})", role());

        boolean divergenceFromContext = context.hasOpenRequest(BasicSignals.DIVERGENCE_REQUEST);
        boolean stagnationDivergence = context.hasOpenRequest(BasicSignals.STAGNATION);
        boolean regressingDivergence = isRegressing(context);

        boolean useDivergent =
                divergenceFromContext ||
                        stagnationDivergence ||
                        regressingDivergence;

        // T2-AO: DIVERGENCE_REQUEST is consumed only after evolution actually produces a new
        // fingerprint. If the LLM ignores the divergence hint and repeats the same mutation,
        // the signal survives into the next cycle and retries. See post-evolution check below.
        if (stagnationDivergence) {
            log.info("EvolutionExecutionAgent: using divergent evolution due to STAGNATION signal.");
            context = context.consumeRequest(BasicSignals.STAGNATION);
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
                context = context.consumeRequest(BasicSignals.DIVERGENCE_REQUEST);
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

        // T2-DT: evaluate the compile-diagnostic steering block against the pre-evolve context
        // so the signal/event reflect what the prompt actually contained. Both the block render
        // (inside evolutionAdapter.evolve) and the emit below read the same suppression state.
        // T2-DV: full configured headroom here — the in-prompt render sees headroom minus the
        // sections assembled before it, so `rendered` can differ near overflow; suppression
        // state (the part the signal/event depend on) is headroom-independent.
        CompileDiagnosticPromptBlock.RenderResult steering =
                CompileDiagnosticPromptBlock.evaluate(context, promptTokenBudget.promptHeadroomTokens());

        context = divergent
                ? evolutionAdapter.evolveDivergent(context)
                : evolutionAdapter.evolve(context);

        context = applyCompileDiagnosticSteering(context, steering);

        // T2-CC: LLM responded with #NoDiff — verify against the graph before trusting it.
        // If the graph shows a prior ACCEPTED evaluation: output confirmed → emit EVOLUTION_NOOP.
        // If the graph shows a prior non-ACCEPTED evaluation (e.g. REJECTED): force divergence so
        //   the LLM produces a substantively different mutation.
        // If the graph has NO prior evaluation (null): cross-subgoal deferred-merge case — the
        //   implementation was already delivered by another subgoal's merge and this subgoal never
        //   had its own evaluation cycle. No contradicting evidence exists, so trust the LLM and
        //   emit EVOLUTION_NOOP directly (single-cycle promotion via AutobuilderService).
        var rawOutcome = context.evolutionOutcome();
        if (rawOutcome != null && rawOutcome.noOp()) {
            String subGoalId = context.currentSubGoalId();

            // T1-DN: inner gate — build failure is concrete evidence; refuse noOp regardless of
            // graph state. BUILD_FIX subgoals are always lastEvalStatus=null on first encounter
            // (freshly injected), which would otherwise take the trust path unconditionally.
            if (isActiveBuildFixSubgoal(subGoalId)) {
                log.warn("{}: LLM declared #NoDiff on active BUILD_FIX subgoal {} — forcing divergence (noDiff-on-blocker)", logPrefix, subGoalId);
                return context
                        .issueRequest(ChannelRequest.of(BasicSignals.REPETITION_DETECTED, 1.0, "noDiff-on-blocker", ChannelRequest.horizonFromDecay(1.0, 0.05)))
                        .issueRequest(ChannelRequest.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, "noDiff-on-blocker", ChannelRequest.horizonFromDecay(1.0, 0.1)));
            }

            String lastEvalStatus = subGoalId != null
                    ? evaluationGraphFacade.findLatestEvaluationStatusForSubgoal(subGoalId)
                    : null;
            if (lastEvalStatus == null || "ACCEPTED".equals(lastEvalStatus)) {
                // T1-CS: for the null-eval case, verify that previousOutput contains at least one
                // file plausibly related to this subgoal before trusting #NoDiff. If the output
                // has no evidence (brand-new subgoal, code never written), the LLM is claiming
                // "already done" for code that was never written. Emit DIVERGENCE_REQUEST so that
                // the next cycle runs a real (divergent) evolution instead of silently promoting.
                if (lastEvalStatus == null && !hasOutputMatchingSubgoal(context)) {
                    log.warn("{}: LLM declared #NoDiff but previousOutput has no evidence for subgoal {}. Injecting DIVERGENCE_REQUEST for next cycle.", logPrefix, subGoalId);
                    return context
                            .issueRequest(ChannelRequest.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, "T1-CS-no-evidence", ChannelRequest.horizonFromDecay(1.0, 0.1)));
                } else {
                    String reason = lastEvalStatus == null ? "deferred-merge noOp" : "evolution-noop";
                    log.info("{}: LLM declared #NoDiff — {} for subgoal {}. Signalling noOp.", logPrefix, reason, subGoalId);
                    // T1-EE: stamp the request with originating subGoalId so AutobuilderService can
                    // reject it if evaluation promotes the subgoal mid-cycle and the pointer moves.
                    ChannelRequest noOpRequest = ChannelRequest.of(BasicSignals.EVOLUTION_NOOP, 1.0, reason, null);
                    if (subGoalId != null) {
                        noOpRequest = noOpRequest.withMeta("subGoalId", subGoalId);
                    }
                    return context.withLastEvolutionOutcome(rawOutcome).issueRequest(noOpRequest);
                }
            } else {
                // A prior evaluation exists but was not ACCEPTED (e.g. REJECTED).
                // Force divergence so the LLM produces a substantively different mutation.
                log.warn("{}: LLM declared #NoDiff but last evaluation for subgoal {} was {}. Forcing divergence.", logPrefix, subGoalId, lastEvalStatus);
                return context
                        .issueRequest(ChannelRequest.of(BasicSignals.REPETITION_DETECTED, 1.0, "unverified-noDiff", ChannelRequest.horizonFromDecay(1.0, 0.05)))
                        .issueRequest(ChannelRequest.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, "unverified-noDiff", ChannelRequest.horizonFromDecay(1.0, 0.1)));
            }
        }

        // T2-DN/T2-DU/T2-DW/T2-DX coverage-guard family deleted 2026-05-29 after the
        // off-switch experiment (runs 20260528 + 20260529 in backup/) confirmed the guard
        // is net-negative: probe-derived diagnostics drive learning, guard rejections
        // contaminate the next prompt with REPETITION_DETECTED + DIVERGENCE_REQUEST. See
        // memory/project_coverage_guard_experiment.md.

        context = updateFingerprint(context);
        logEvolution(logPrefix, context);

        var outcome = context.evolutionOutcome();
        boolean changed = outcome != null && (!outcome.addedOrChangedFiles().isEmpty()
                || !outcome.deletedFiles().isEmpty());

        if (changed) {
            // T1-EF: reinstate ROLE_EVALUATION when evolution writes files. Meta-step-only cycles
            // (after a compile-probe rejection) reinforce ROLE_EVOLUTION but never ROLE_EVALUATION,
            // letting the latter drain below the eligibility threshold and permanently block
            // evaluation. Emitting it here keeps evaluation eligible whenever a mutation exists.
            // T4-BZ-2: EVOLUTION_OCCURRED is derived — DerivedFacts.evolutionOccurred reads
            // unmergedEvolutionWork, set here exactly where the deleted emitter fired and
            // cleared by lineage on merge/subgoal change.
            context = context
                    .withEvolutionRanThisCycle(true)
                    .withUnmergedEvolutionWork(true)
                    .withSignal(Signal.of(BasicSignals.ROLE_EVALUATION, 1.0, 0.05, "evolution-wrote-files"));
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

        if (behaviourLogger != null && changed) {
            List<String> files = new ArrayList<>();
            if (outcome.addedOrChangedFiles() != null) {
                outcome.addedOrChangedFiles().forEach(f -> files.add(f.path()));
            }
            if (outcome.deletedFiles() != null) {
                files.addAll(outcome.deletedFiles());
            }
            behaviourLogger.mutationProposed(context.cycleName(), context.lastEvolutionFingerprint(), files);
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
                    .issueRequest(ChannelRequest.of(BasicSignals.REPETITION_DETECTED, 1.0, "EvolutionExecutionAgent", ChannelRequest.horizonFromDecay(1.0, 0.05)))
                    .issueRequest(ChannelRequest.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, "repetition", ChannelRequest.horizonFromDecay(1.0, 0.10)));
        } else if (!Objects.equals(newFp, lastFp)) {
            // T4-BZ-2: per-cycle fact (formerly the stored EVOLUTION_FINGERPRINT_CHANGED
            // signal, whose multi-cycle decay lingering was accidental).
            out = out
                    .withEvolutionFingerprintChangedThisCycle(true)
                    .consumeRequest(BasicSignals.REPETITION_DETECTED);
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

    private boolean isActiveBuildFixSubgoal(String subGoalId) {
        if (subGoalId == null) return false;
        GoalEntity g = goalPlannerService.getGoalById(subGoalId);
        if (g == null || g.getMetadata() == null) return false;
        if (g.getStatus() != GoalStatus.ACTIVE) return false;
        return BlockerTypes.BUILD_FIX.equals(g.getMetadata().get(BlockerTypes.METADATA_KEY));
    }

    /**
     * T1-CS / T1-DO: Returns true when previousOutput contains at least one file plausibly
     * related to this subgoal. Uses {@link EvolutionProtocolContract#extractSurfaceScanTerms}
     * (HTTP paths, camelCase, PascalCase after stop-word filtering) on both description and
     * criteria. Fail-closed: returns false when no anchor terms can be extracted so that a
     * generic description cannot produce a false-positive match.
     */
    private boolean hasOutputMatchingSubgoal(CycleContextData context) {
        Map<String, String> previousOutput = context.previousOutput();
        if (previousOutput == null || previousOutput.isEmpty()) return false;

        Set<String> terms = new HashSet<>();
        String desc = context.currentSubGoalDescription();
        if (desc != null) {
            terms.addAll(EvolutionProtocolContract.extractSurfaceScanTerms(desc));
        }
        List<String> criteria = context.currentSubGoalCriteria();
        if (criteria != null) {
            for (String criterion : criteria) {
                terms.addAll(EvolutionProtocolContract.extractSurfaceScanTerms(criterion));
            }
        }
        if (terms.isEmpty()) return false; // T1-DO: fail-closed — no anchor terms, refuse trust

        for (Map.Entry<String, String> entry : previousOutput.entrySet()) {
            String pathLower = entry.getKey() != null ? entry.getKey().toLowerCase() : "";
            String contentLower = entry.getValue() != null ? entry.getValue().toLowerCase() : "";
            for (String term : terms) {
                String termLower = term.toLowerCase();
                if (pathLower.contains(termLower) || contentLower.contains(termLower)) {
                    return true;
                }
            }
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

    /**
     * T2-DT: emit the behaviour event when the steering block fires, or
     * evolution_steering_suppressed when an operator has vetoed via STEERING_SUPPRESS.
     * Called after evolve so the suppression state seen here matches the state the prompt block
     * saw inside the adapter. (T4-J: the COMPILE_DIAGNOSTIC_FED_BACK signal formerly emitted
     * alongside was deleted — nothing read it, and the event here carries strictly more.)
     */
    private CycleContextData applyCompileDiagnosticSteering(CycleContextData context,
                                                            CompileDiagnosticPromptBlock.RenderResult result) {
        if (result.fires()) {
            if (behaviourLogger != null) {
                behaviourLogger.evolutionSteeringCompileDiagnosticsFedBack(
                        context.cycleName(),
                        context.currentSubGoalId(),
                        result.errorCount(),
                        result.rendered(),
                        result.hintsMatched());
            }
        } else if (result.suppressed() && behaviourLogger != null) {
            behaviourLogger.evolutionSteeringSuppressed(
                    context.cycleName(),
                    context.currentSubGoalId(),
                    result.suppressionReason());
        }
        return context;
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
