package com.walpro.autobuilder.agent.analysis;

import com.walpro.autobuilder.agent.analysis.model.AnalysisOutcome;
import com.walpro.autobuilder.core.engine.ExecutionAgent;
import com.walpro.autobuilder.core.grounding.SymbolGroundingService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalKindClassifier;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalSimilarityService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisAgent implements ExecutionAgent {

    private final AnalysisLLMAdapter adapter;
    private final GoalPlannerService goalPlannerService;
    private final GoalKindClassifier kindClassifier;
    private final GoalSimilarityService similarityService;
    private final SymbolGroundingService groundingService;

    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    @Override
    public CycleContextData execute(CycleContextData context) {
        log.info("Executing agent (role={})", role());

        AnalysisOutcome prev = context.analysisOutcome();
        String prevGoal = context.analysisScopeGoalId();
        String prevSub  = context.analysisScopeSubGoalId();

        AnalysisOutcome rawOutcome = adapter.analyze(context);
        // T2-DC: rewrite hallucinated paths/symbols before they propagate into goal injection.
        Grounded grounded = applyGrounding(rawOutcome);
        AnalysisOutcome outcome = grounded.outcome();
        persistAnalysisClassifiedKind(context, outcome);
        autoInjectExecutableSubGoals(context, outcome, grounded.undefinedPerDescription());

        CycleContextData result = context
                .withLastAnalysisOutcome(prev)
                .withLastAnalysisScopeGoalId(prevGoal)
                .withLastAnalysisScopeSubGoalId(prevSub)
                .withAnalysisOutcome(outcome)
                .withAnalysisScopeGoalId(context.currentGoalId())
                .withAnalysisScopeSubGoalId(context.currentSubGoalId());

        if (outcome != null && outcome.possiblyComplete()) {
            log.warn("AnalysisAgent: subgoal {} may already be complete — emitting POTENTIALLY_COMPLETE signal.", context.currentSubGoalId());
            result = result.withSignal(Signal.of(BasicSignals.POTENTIALLY_COMPLETE, 1.0, 0.0, "analysis-possibly-complete"));
        }

        // T2-DC: emit one MISSING_SYMBOL signal per unique undefined identifier so the planner
        // and downstream agents can react. Source string carries the symbol name.
        for (String sym : grounded.undefinedSymbols()) {
            result = result.withSignal(Signal.of(BasicSignals.MISSING_SYMBOL, 1.0, 0.25, "analysis-grounding:" + sym));
        }

        return result;
    }

    /**
     * T2-DC: result of grounding LLM citations against the on-disk output tree.
     *
     * @param undefinedSymbols       all undefined identifiers across the outcome (signal source)
     * @param undefinedPerDescription per proposed-subgoal-description undefined set, keyed by the
     *                                grounded description (what eventually reaches addSubGoal)
     */
    private record Grounded(
            AnalysisOutcome outcome,
            Set<String> undefinedSymbols,
            Map<String, Set<String>> undefinedPerDescription) {}

    private Grounded applyGrounding(AnalysisOutcome in) {
        if (in == null) return new Grounded(null, Set.of(), Map.of());
        Set<String> undefinedAll = new LinkedHashSet<>();
        Map<String, Set<String>> undefinedPerDesc = new LinkedHashMap<>();

        List<AnalysisOutcome.Finding> groundedFindings = new ArrayList<>(in.findings().size());
        for (AnalysisOutcome.Finding f : in.findings()) {
            String observation = groundingService.groundSymbolReferences(f.observation(), undefinedAll);
            String path = (f.path() == null || "(n/a)".equals(f.path()))
                    ? f.path()
                    : groundingService.groundPath(f.path());
            groundedFindings.add(new AnalysisOutcome.Finding(f.severity(), f.type(), observation, path));
        }

        List<String> proposed = new ArrayList<>(in.proposedExecutableSubGoals().size());
        Map<String, List<String>> rewrittenCriteria = new LinkedHashMap<>();
        for (String desc : in.proposedExecutableSubGoals()) {
            Set<String> perDescUndefined = new LinkedHashSet<>();
            // Ground only to DETECT undefined symbols (populates perDescUndefined); persist the
            // marker-free text. The [UNDEFINED] marker is a render-time prompt hint, re-applied to
            // descriptions by refreshGoalAnnotations on the prompt copy — it must never be baked
            // into the stored description/criteria. Baking it in made criteria verify literally
            // ("…ProductRepository [UNDEFINED] interface") so they could never pass, manufacturing
            // permanent root-closure blockers. The clean "Create X.java" criterion below carries
            // the creation-framing intent; the marker is redundant for it and harmful for verification.
            groundingService.groundSymbolReferences(desc, perDescUndefined);
            proposed.add(desc);

            List<String> origCriteria = in.goalCriteria() != null ? in.goalCriteria().get(desc) : null;
            List<String> newCriteria = new ArrayList<>();
            // Prepend a creation criterion per undefined symbol so the next subgoal is framed
            // as "Create X.java", not "Add import for X".
            for (String sym : perDescUndefined) {
                newCriteria.add("Create " + sym + ".java");
            }
            if (origCriteria != null) {
                for (String c : origCriteria) {
                    // Symbols cited only in criteria text also count as undefined for this desc.
                    groundingService.groundSymbolReferences(c, perDescUndefined);
                    newCriteria.add(c);
                }
            }
            if (!newCriteria.isEmpty()) {
                rewrittenCriteria.put(desc, newCriteria);
            }
            undefinedAll.addAll(perDescUndefined);
            if (!perDescUndefined.isEmpty()) {
                undefinedPerDesc.put(desc, perDescUndefined);
            }
        }

        // No grounding needed — pass the original outcome through unchanged. Preserves object
        // identity for healthy LLM output (no hallucinated symbols, no rewriting).
        if (undefinedAll.isEmpty()) {
            return new Grounded(in, Set.of(), Map.of());
        }

        AnalysisOutcome rewritten = new AnalysisOutcome(
                groundedFindings,
                in.hypotheses(),
                proposed,
                rewrittenCriteria.isEmpty() ? null : rewrittenCriteria,
                in.message(),
                in.goalKind(),
                in.possiblyComplete(),
                in.injectionThrottle());
        return new Grounded(rewritten, undefinedAll, undefinedPerDesc);
    }

    @Override
    public String name() {
        return "analysis";
    }

    @Override
    public String role() {
        return "analysis";
    }

    @Override
    public Set<SignalType> getSupportedSignals() {
        return BasicSignals.ANALYSIS_SIGNALS;
    }

    @Override
    public double priority(CycleContextData context) {
        return 1.5; // between planner and evaluation by default
    }

    private void autoInjectExecutableSubGoals(CycleContextData ctx, AnalysisOutcome outcome,
                                              Map<String, Set<String>> undefinedPerDescription) {
        if (outcome == null || outcome.proposedExecutableSubGoals() == null || outcome.proposedExecutableSubGoals().isEmpty()) {
            return;
        }

        // T2-BW: LLM-directed de-escalation — agent voluntarily suppresses injection this cycle
        if (outcome.injectionThrottle()) {
            log.info("AnalysisAgent: #InjectionThrottle active — skipping all injection this cycle");
            return;
        }

        String parentId = ctx.currentSubGoalId();
        if (parentId == null || parentId.isBlank()) {
            return; // nothing to attach to
        }

        // T2-BW Tier 1: per-subgoal budget = max(1, countUnmetCriteria)
        int alreadyInjected = goalPlannerService.getGoalMetadataInt(parentId, "analysisInjectionCount");
        int tier1Budget = Math.max(1, goalPlannerService.countUnmetCriteria(parentId));
        int tier1Remaining = tier1Budget - alreadyInjected;
        if (tier1Remaining <= 0) {
            log.info("AnalysisAgent: per-subgoal injection budget exhausted for {} ({}/{}) — skipping",
                    parentId, alreadyInjected, tier1Budget);
            return;
        }

        // T2-BW Tier 2: global session budget
        int globalRemaining = goalPlannerService.getGlobalAnalysisInjectionBudget();
        if (globalRemaining <= 0) {
            log.info("AnalysisAgent: global injection budget exhausted — skipping");
            return;
        }

        int effectiveBudget = Math.min(tier1Remaining, globalRemaining);

        List<GoalEntity> existingChildren = goalPlannerService.getAllGoals().stream()
                .filter(g -> parentId.equals(g.getParentId()))
                .toList();

        boolean parentHasActiveChildren = existingChildren.stream().anyMatch(g -> g.getStatus() == GoalStatus.ACTIVE);

        boolean setFirstAsCurrent = !parentHasActiveChildren;
        int injectedThisCycle = 0;

        // T2-DO: snapshot the full goal set (including GIVEN_UP) once before the loop so
        // duplicate analysis-injected descriptions are caught regardless of the matched goal's
        // status. The existing `isDuplicate` graph query excludes GIVEN_UP, which is why prior
        // sessions accreted 4–5× literal-duplicate GIVEN_UP siblings under the same root.
        List<GoalEntity> allGoalsForDedup = goalPlannerService.getAllGoals();

        for (String raw : outcome.proposedExecutableSubGoals()) {
            if (injectedThisCycle >= effectiveBudget) break;
            if (raw == null) continue;
            String desc = raw.trim();
            if (desc.isEmpty()) continue;

            GoalKindClassifier.GoalKind kind = kindClassifier.classify(desc);
            if (kind != GoalKindClassifier.GoalKind.EXECUTABLE) {
                continue; // only auto-inject executable ones
            }

            // T2-DO: status-aware dedup — ACTIVE/COMPLETED match blocks silently;
            // GIVEN_UP match blocks AND registers a T1-EI rehabilitation vote so repeated
            // re-proposals eventually flip the original goal back to ACTIVE.
            GoalEntity match = similarityService.findEquivalent(desc, allGoalsForDedup);
            if (match != null) {
                if (match.getStatus() == GoalStatus.GIVEN_UP) {
                    int votes = goalPlannerService.registerRehabilitationVote(
                            match.getId(),
                            ctx != null ? ctx.cycleName() : null);
                    log.info("T2-DO/T1-EI: analysis-side dedup vote +1 for GIVEN_UP goalId={} (total={}, candidate='{}')",
                            match.getId(), votes, desc);
                    if (behaviourLogger != null) {
                        behaviourLogger.subgoalDedupVote(
                                ctx != null ? ctx.cycleName() : null,
                                "analysis",
                                match.getId(),
                                "GIVEN_UP",
                                desc);
                    }
                } else {
                    log.warn("AnalysisAgent skipped similar subgoal (match status={}): '{}'",
                            match.getStatus(), desc);
                }
                continue;
            }

            String id = "goal-" + UUID.randomUUID();
            boolean makeCurrent = setFirstAsCurrent && injectedThisCycle == 0;

            goalPlannerService.addSubGoal(id, desc, makeCurrent, parentId, "analysis", kind);

            List<String> criteria = outcome.goalCriteria() != null ? outcome.goalCriteria().get(desc) : null;
            if (criteria != null && !criteria.isEmpty()) {
                goalPlannerService.setAcceptanceCriteria(id, criteria);
                log.info("AnalysisAgent stored {} acceptance criteria for subgoal [{}]", criteria.size(), id);
            } else {
                log.warn("AnalysisAgent injected subgoal [{}] with no acceptance criteria: '{}'", id, desc);
            }

            // T2-DC: persist the undefined-symbol set on this goal's metadata so future
            // heuristics (T2-DD) can query repeated hallucinations cross-cycle.
            Set<String> undefHere = undefinedPerDescription != null ? undefinedPerDescription.get(desc) : null;
            if (undefHere != null && !undefHere.isEmpty()) {
                goalPlannerService.setUndefinedSymbols(id, undefHere);
            }

            goalPlannerService.incrementGoalMetadataInt(parentId, "analysisInjectionCount");
            goalPlannerService.incrementTotalAnalysisInjections();
            injectedThisCycle++;
        }
    }

    private void persistAnalysisClassifiedKind(CycleContextData ctx, AnalysisOutcome outcome) {
        if (outcome == null || outcome.goalKind() == null || outcome.goalKind().isBlank()) return;

        String subGoalId = ctx.currentSubGoalId();
        if (subGoalId == null || subGoalId.isBlank()) return;

        GoalEntity sg = goalPlannerService.getGoalById(subGoalId);
        if (sg == null) return;

        String kind = outcome.goalKind().trim().toUpperCase();
        if (!kind.equals("COGNITIVE") && !kind.equals("EXECUTABLE") && !kind.equals("HYBRID")) {
            log.warn("AnalysisAgent returned unrecognised GoalKind '{}' for subgoal {}, ignoring", outcome.goalKind(), subGoalId);
            return;
        }

        sg.getMetadata().put("analysisClassifiedKind", kind);
        goalPlannerService.saveGoalMetadata(sg);
        log.info("AnalysisAgent classified subgoal {} as {}", subGoalId, kind);
    }

}