package com.walpro.autobuilder.core.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.walpro.autobuilder.core.build.BuildBlockGate;
import com.walpro.autobuilder.core.deliberation.DeliberationMode;
import com.walpro.autobuilder.core.service.merge.FileVariantCollector;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalKindClassifier;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshot;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionEligibilityExplainer {

    // T3-BR: cap on consecutive evaluator re-runs against the same evolution fingerprint
    // when criteria remain unbanked. Beyond this cap, evaluation is blocked and the agent
    // emits REPLAN_REQUESTED so the planner can re-decompose.
    public static final int EVALUATOR_REPEAT_RETRY_CAP = 3;

    private final FileVariantCollector fileVariantCollector;
    private final OrchestrationSnapshotService orchestrationSnapshotService;

    private final GoalPlannerService goalPlannerService;
    private final GoalKindClassifier kindClassifier;

    /** T1-DT: optional — only present when {@code autobuilder.build.enabled=true}. */
    @Setter
    @Autowired(required = false)
    private BuildBlockGate buildBlockGate;

    public List<String> explainBlockers(String role, CycleContextData ctx) {
        List<String> blockers = new ArrayList<>();
        if (ctx == null) {
            blockers.add("context is null");
            return blockers;
        }

        // T1-DT: hard build-block — while COMPILE_ERROR is carried, only build-fix-related
        // roles (analysis always; planner if no BUILD_FIX subgoal active; evolution/merge only
        // when current subgoal is BUILD_FIX) are eligible. Pre-empts all role-specific gating
        // below to ensure the system cannot advance feature work on a broken build.
        if (buildBlockGate != null && buildBlockGate.isActive(ctx)
                && !buildBlockGate.isEligible(role, ctx)) {
            blockers.add("build_blocked");
            return blockers;
        }

        OrchestrationSnapshot snapshot = safeFetchSnapshot(ctx);

        // Role-signal expectations
        switch (role == null ? "" : role.toLowerCase()) {

            case "evolution" -> {
                if (ctx.merged()) {
                    blockers.add("merge already occurred in this cycle — post-merge evolution blocked");
                    return blockers;
                }

                if (!ctx.hasStrongSignal(BasicSignals.ROLE_EVOLUTION, 0.10)) {
                    blockers.add("missing strong ROLE_EVOLUTION (>=0.10)");
                    return blockers;
                }

                boolean hasSubGoal = ctx.currentSubGoalId() != null && !ctx.currentSubGoalId().isBlank();
                GoalEntity currentSubGoalEntity = goalPlannerService.getCurrentSubGoal();
                boolean hasCurrentSubGoalEntity = false;
                if (hasSubGoal && currentSubGoalEntity != null) {
                    hasCurrentSubGoalEntity = currentSubGoalEntity.getId().equals(ctx.currentSubGoalId());
                }
                if (!hasCurrentSubGoalEntity) {
                    blockers.add("missing subgoal");
                    return blockers;
                }

                if (isAcceptedNotMergedAttempt(snapshot)) {
                    // Only block evolution when there are ACCEPTED variants actually pending
                    // merge on disk. If the filesystem shows no such variants, the graph state
                    // is stale/phantom (e.g. the attempt ID was reused across subgoals, or merge
                    // already consumed all variants from a prior cycle). Allow evolution to proceed
                    // so the system can produce a new mutation and self-correct.
                    boolean hasVariants = false;
                    try {
                        hasVariants = fileVariantCollector.hasAnyVariant(ctx);
                    } catch (Exception ignored) {
                        log.debug("hasAnyVariant check failed during evolution eligibility: {}", ignored.getMessage());
                    }
                    if (hasVariants) {
                        blockers.add("latest evaluation already ACCEPTED, but merge still pending");
                    }
                }
                return blockers;
            }

            case "evaluation" -> {
                if (!ctx.hasStrongSignal(BasicSignals.ROLE_EVALUATION, 0.10)) {
                    blockers.add("missing strong ROLE_EVALUATION (>=0.10)");
                }

                if (ctx.projectDiff() == null) {
                    blockers.add("missing project diff");
                }

                String lastEvalFp = ctx.lastEvaluatedEvolutionFingerprint();
                String nowFp = ctx.lastEvolutionFingerprint();
                boolean alreadyEvaluatedThisFingerprint = lastEvalFp != null && Objects.equals(nowFp, lastEvalFp);
                if (alreadyEvaluatedThisFingerprint) {
                    // When the system is stagnating or stuck in repetition, allow re-evaluation even on
                    // the same fingerprint — otherwise the evaluator never gets a chance to report the
                    // stagnation signal that drives exhaustion detection.
                    boolean stagnationPresent = ctx.hasSignal(BasicSignals.STAGNATION)
                            || ctx.hasSignal(BasicSignals.REPETITION_DETECTED);

                    // T3-BR: criteria-aware fingerprint-repeat guard. The original boolean guard
                    // (block whenever fingerprint repeats) was too coarse — fingerprint identity is
                    // not a sufficient cache key when criteria can be added mid-subgoal (T3-BE) or
                    // when the prior evaluation returned partial assessments. Refined logic:
                    //   - subgoal has no defined criteria → preserve the original block (T2-CD path).
                    //   - all defined criteria are banked → skip re-evaluation (fast path).
                    //   - criteria unbanked but retries < cap → allow re-evaluation.
                    //   - criteria unbanked and retries >= cap → block; EvaluationAgent has emitted
                    //     REPLAN_REQUESTED so the planner can re-decompose.
                    String sgId = ctx.currentSubGoalId();
                    boolean hasCriteriaDefined = sgId != null && goalPlannerService.hasAcceptanceCriteria(sgId);

                    if (!hasCriteriaDefined) {
                        if (!stagnationPresent) {
                            blockers.add("already evaluated current evolution fingerprint");
                        }
                    } else {
                        boolean allCriteriaBanked = goalPlannerService.allCriteriaDefinedAndBanked(sgId);
                        boolean retriesExhausted = ctx.evaluatorRepeatRetries() >= EVALUATOR_REPEAT_RETRY_CAP;

                        if (allCriteriaBanked) {
                            if (!stagnationPresent) {
                                blockers.add("already evaluated current evolution fingerprint and all criteria banked");
                            }
                        } else if (retriesExhausted) {
                            if (!stagnationPresent) {
                                blockers.add("evaluator retry cap reached on repeated fingerprint with unbanked criteria");
                            }
                        }
                        // else: criteria still unbanked and retries below cap — allow re-evaluation.
                    }
                }

                // Evaluation must not run before evolution in the same cycle, otherwise the
                // fitness ranking may put evaluation first and it re-evaluates a stale prior diff.
                // EVOLUTION_NOOP is an unverified LLM claim that the current output already
                // satisfies the subgoal; evaluation must run to verify before downstream
                // promotes the subgoal to FULFILLED.
                if (!ctx.evolutionRanThisCycle() && !ctx.hasSignal(BasicSignals.EVOLUTION_NOOP)) {
                    boolean hasUnevaluatedPriorEvolution = nowFp != null && !Objects.equals(nowFp, lastEvalFp);
                    if (!hasUnevaluatedPriorEvolution) {
                        blockers.add("evolution has not run this cycle and no unevaluated prior evolution exists");
                    }
                }

                return blockers;
            }


            case "merge" -> {
                if (!ctx.hasSignal(BasicSignals.ROLE_MERGE)) {
                    blockers.add("missing ROLE_MERGE");
                    return blockers;
                }

                // T1-BP: no active subgoal => no merge (safety net mirrors the evolution guard)
                if (ctx.currentSubGoalId() == null || ctx.currentSubGoalId().isBlank()) {
                    blockers.add("no active subgoal — merge blocked");
                    return blockers;
                }

                boolean hasAnyVariant = false;
                try {
                    hasAnyVariant = fileVariantCollector.hasAnyVariant(ctx);
                } catch (Exception ignored) {
                    log.warn("Ignore message: " + ignored.getMessage(), ignored);
                }

                if (!hasAnyVariant) {
                    blockers.add("missing variant");
                }

                // T1-CD: hasAnyVariant() now returns true only when ACCEPTED variants exist on
                // disk, so checking ctx.mutationState() here is redundant and would incorrectly
                // block cross-cycle merge recovery (where the current cycle has mutationState=null
                // but a prior cycle's accepted variant is still pending merge).

                if (ctx.hasSignal(BasicSignals.SUBGOAL_SCOPE_MISMATCH)) { // TODO: this is currently not possible, because the signal does not survive a following cycle. Change logi accordingly.
                    blockers.add("subgoal-scope mismatch");
                }

                return blockers;
            }


            case "analysis" -> {
                if (!ctx.hasStrongSignal(BasicSignals.ROLE_ANALYSIS, 0.10)) {
                    blockers.add("missing strong ROLE_ANALYSIS (>=0.10)");
                    return blockers;
                }

                GoalEntity sg = (ctx.currentSubGoalId() != null && !ctx.currentSubGoalId().isBlank())
                        ? goalPlannerService.getGoalById(ctx.currentSubGoalId())
                        : null;

                String desc = sg != null ? sg.getDescription() : ctx.currentSubGoalDescription(); // TODO: think about better syncing or if we need to fields at all
                if (desc == null || desc.isBlank()) {
                    blockers.add("missing subgoal description");
                    return blockers;
                }

                // T2-DE: when the build is broken, analysis is the diagnostic escape hatch and is
                // unconditionally eligible regardless of subgoal kind or trigger signals. The COGNITIVE
                // and trigger checks below only apply in the normal (non-broken-build) flow.
                if (buildBlockGate != null && buildBlockGate.isActive(ctx)) {
                    return blockers; // empty — analysis may run
                }

                GoalKindClassifier.GoalKind kind = sg != null
                        ? kindClassifier.classify(sg)
                        : kindClassifier.classify(desc);

                boolean cognitive = kind == GoalKindClassifier.GoalKind.COGNITIVE
                        || kind == GoalKindClassifier.GoalKind.HYBRID;

                boolean replanRequested = ctx.hasStrongSignal(BasicSignals.REPLAN_REQUESTED, 0.10);

                if (!cognitive && !replanRequested) {
                    blockers.add("subgoal is not COGNITIVE/HYBRID and REPLAN_REQUESTED is absent");
                    return blockers;
                }

                boolean triggered = ctx.hasSignal(BasicSignals.LOW_CONFIDENCE)
                        || ctx.hasSignal(BasicSignals.STAGNATION)
                        || ctx.hasStrongSignal(BasicSignals.REPLAN_REQUESTED, 0.10);

                if (!triggered) {
                    blockers.add("no trigger present (LOW_CONFIDENCE/STAGNATION/REPLAN_REQUESTED)");
                }

                return blockers;
            }

            case "planner" -> {
                if (ctx.hasSignal(BasicSignals.SCOPE_OUT_OF_SCOPE)) {
                    return blockers; // planner should be able to run
                }

                if (!ctx.hasStrongSignal(BasicSignals.ROLE_PLANNER, 0.10)) {
                    blockers.add("missing strong ROLE_PLANNER (>=0.10)");
                    return blockers;
                }

                if (ctx.hasSignal(BasicSignals.PLANNING_THROTTLED)) { // TODO: (not here) - also prune (sub)goals if necessary
                    if (!ctx.hasStrongSignal(BasicSignals.REPLAN_REQUESTED, 1.0)) {
                        blockers.add("planning throttled (disproportional inflow vs completion outflow)");
                        return blockers;
                    }
                    // REPLAN_REQUESTED >= 1.0 overrides PLANNING_THROTTLED
                }

                GoalEntity sg = goalPlannerService.getCurrentSubGoal();
                if (sg != null && sg.getKind() == GoalKindClassifier.GoalKind.COGNITIVE) {
                    boolean sameScope = Objects.equals(ctx.currentSubGoalId(), ctx.analysisScopeSubGoalId());
                    boolean hasProposed = ctx.analysisOutcome() != null
                            && ctx.analysisOutcome().proposedExecutableSubGoals() != null
                            && !ctx.analysisOutcome().proposedExecutableSubGoals().isEmpty();

                    if (sameScope && hasProposed) {
                        blockers.add("analysis already proposed executable subgoals for this cognitive subgoal scope");
                        return blockers;
                    }
                }

                boolean hasSubGoal = ctx.currentSubGoalId() != null && !ctx.currentSubGoalId().isBlank();

                // Planner can bootstrap/repair goals
                boolean noGoalState = !hasSubGoal;

                boolean stagnating = ctx.hasSignal(BasicSignals.STAGNATION)
                        || (ctx.goalProgress() == com.walpro.autobuilder.model.GoalProgressState.STAGNATING);

                boolean bootstrap = ctx.deliberationResult() != null
                        && ctx.deliberationResult().mode() == DeliberationMode.BOOTSTRAP;

                boolean needsInitialDecomposition = needsInitialRootDecomposition(ctx);

                boolean replanRequested = ctx.hasStrongSignal(BasicSignals.REPLAN_REQUESTED, 0.10);

                if (!noGoalState && !stagnating && !bootstrap && !needsInitialDecomposition && !replanRequested) {
                    blockers.add("neither noGoalState nor STAGNATION/BOOTSTRAP/needsInitialDecomposition/REPLAN_REQUESTED present");
                }

                return blockers;
            }

            default -> {
                blockers.add("unknown role gating");
                return blockers;
            }
        }
    }

    public boolean canExecute(String role, CycleContextData ctx) {
        return explainBlockers(role, ctx).isEmpty();
    }

    public boolean isAcceptedNotMergedAttempt(OrchestrationSnapshot snapshot) {
        if (snapshot == null) return false;

        try {
            // Require that evolution actually ran under this attempt. Without a corresponding
            // evolution, an ACCEPTED evaluation is a phantom state (e.g. a meta-step re-evaluation
            // of a stale diff) and must not block the next evolution attempt.
            boolean evolved = snapshot.lastEvolution() != null;

            boolean accepted = snapshot.lastEvaluation() != null
                    && "ACCEPTED".equalsIgnoreCase(snapshot.lastEvaluation().status());

            boolean merged = (snapshot.lastMerge() != null)
                    || Boolean.TRUE.equals(snapshot.mergedViaGraph());

            return evolved && accepted && !merged;
        } catch (Exception ignored) {
            // fail open: do not block if graph lookup fails
            log.debug("isAcceptedNotMergedAttempt lookup failed: {}", ignored.getMessage());
            return false;
        }
    }

    private OrchestrationSnapshot safeFetchSnapshot(CycleContextData ctx) {
        if (ctx == null) return null;

        String goalId = ctx.currentGoalId();
        String subGoalId = ctx.currentSubGoalId();
        String attemptId = ctx.lastAttemptId();

        if ((goalId == null || goalId.isBlank()) && (subGoalId == null || subGoalId.isBlank())) {
            return null;
        }

        // Use the current attempt's scoped snapshot to avoid cross-subgoal leakage:
        // fetchLatestSnapshot resolves the latest Action across (scopeGoalId OR rootGoalId),
        // which after subgoal promotion can return an attempt from the previous subgoal,
        // causing isAcceptedNotMergedAttempt to block evolution on the new subgoal.
        if (attemptId == null || attemptId.isBlank()) {
            return null;
        }

        try {
            return orchestrationSnapshotService.fetchSnapshot(goalId, subGoalId, attemptId, 10);
        } catch (Exception ignored) {
            return null; // fail open
        }
    }

    public boolean needsInitialRootDecomposition(CycleContextData ctx) {
        if (ctx == null) return false;

        String goalId = ctx.currentGoalId();
        String subGoalId = ctx.currentSubGoalId();

        if (goalId == null || goalId.isBlank()) return false;
        if (subGoalId == null || subGoalId.isBlank()) return false;

        // only when "we are sitting on the root"
        if (!goalId.equals(subGoalId)) return false;

        GoalEntity root = goalPlannerService.getRootGoal();
        if (root == null) return false;

        Object plannedOnce = root.getMetadata() != null ? root.getMetadata().get("plannedOnce") : null;
        if (Boolean.TRUE.equals(plannedOnce)) return false;

        return true;
    }
}
