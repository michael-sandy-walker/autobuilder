package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.deliberation.DefaultDeliberationValidator;
import com.walpro.autobuilder.core.deliberation.DeliberationMode;
import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.core.deliberation.RuleBasedDeliberationProposer;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-BZ-0 (Q-a): preview of the slice-4 selection rule against the frozen-state library —
 * the sanity check the design note requires BEFORE T4-BZ-4 writes production code.
 *
 * The rule under test (as committed to the T4-BZ design note):
 *   0. halt: pause toggle set or terminal fact true → no selection pass;
 *   1. candidate roles = deliberation-mode gate (unchanged, I4);
 *   2. eligibility predicates per role (today's explainer semantics, I3);
 *   3. order eligible candidates descending by
 *        (explicit constraint, open-request urgency for the role, window boost, base priority),
 *      deterministic tie-break by pipeline order planner→analysis→evolution→evaluation→merge;
 *   4. first role in that order executes first (walk-down continues as today, I2/I10).
 *
 * "Requests" are the slice-3 channel types (10 requests + 2 staleness carriers); "window boost"
 * is the ROLE_* strength (slice-4 windows are 1:1 with today's role signals per Q-c); derived
 * facts (BUILD_BROKEN, EVOLUTION_OCCURRED, ...) deliberately do NOT participate in ordering —
 * their selection influence must be carried by explicit window grants (A26-A30 in the site
 * table) or eligibility, and this preview measures exactly where that assumption breaks.
 *
 * The test prints the full per-state comparison and pins the agreement counts documented in the
 * design note. Slice 4 replaces this preview with the real equivalence gate.
 */
class SelectionRulePreviewTest {

    /** Slice-3 request channel types incl. the two expiry carriers. */
    private static final Set<SignalType> REQUEST_FAMILY = Set.of(
            BasicSignals.REPLAN_REQUESTED,
            BasicSignals.DIVERGENCE_REQUEST,
            BasicSignals.LOW_CONFIDENCE,
            BasicSignals.STAGNATION,
            BasicSignals.REPETITION_DETECTED,
            BasicSignals.SCORE_PLATEAU,
            BasicSignals.REPLAN_THRASH_DETECTED,
            BasicSignals.SUBGOAL_EXHAUSTED,
            BasicSignals.EVOLUTION_NOOP,
            BasicSignals.POTENTIALLY_COMPLETE,
            BasicSignals.MISSING_SYMBOL,
            BasicSignals.PLANNING_THROTTLED
    );

    private static final Map<String, Set<SignalType>> ROLE_SETS = Map.of(
            "planner", BasicSignals.PLANNER_SIGNALS,
            "evolution", BasicSignals.EVOLUTION_SIGNALS,
            "evaluation", BasicSignals.EVALUATION_SIGNALS,
            "merge", BasicSignals.MERGE_SIGNALS,
            "analysis", BasicSignals.ANALYSIS_SIGNALS
    );

    private static final Map<String, SignalType> ROLE_WINDOW = Map.of(
            "planner", BasicSignals.ROLE_PLANNER,
            "evolution", BasicSignals.ROLE_EVOLUTION,
            "evaluation", BasicSignals.ROLE_EVALUATION,
            "merge", BasicSignals.ROLE_MERGE,
            "analysis", BasicSignals.ROLE_ANALYSIS
    );

    /** ExecutionAgent default priorities with AnalysisAgent's 1.5 override. */
    private static final Map<String, Double> BASE_PRIORITY = Map.of(
            "planner", 1.0, "analysis", 1.5, "evaluation", 2.0, "evolution", 3.0, "merge", 4.0);

    private static final List<String> PIPELINE_ORDER =
            List.of("planner", "analysis", "evolution", "evaluation", "merge");

    record StateResult(String name, String mode, String todayPick, String rulePick,
                       List<String> todayOrder, List<String> ruleOrder, boolean halted) {
        boolean agrees() {
            return halted || Objects.equals(todayPick, rulePick);
        }
    }

    @Test
    void proposedSelectionRule_reproducesRecordedFirstExecutedRole_acrossTheStateLibrary() {
        List<StateResult> results = new ArrayList<>();
        for (FrozenStateFixtures.FrozenState state : FrozenStateFixtures.all()) {
            results.add(evaluate(state));
        }

        StringBuilder report = new StringBuilder("\n=== T4-BZ-0 Q-a selection-rule preview ===\n");
        List<String> divergences = new ArrayList<>();
        for (StateResult r : results) {
            String outcome = r.halted() ? "HALT" : (r.agrees() ? "match" : "DIVERGE");
            report.append(String.format("%-52s mode=%-10s today=%-10s rule=%-10s %s%n",
                    r.name(), r.mode(), r.todayPick(), r.rulePick(), outcome));
            if (!r.agrees()) {
                divergences.add(r.name() + ": today=" + r.todayPick() + " (order " + r.todayOrder()
                        + ") vs rule=" + r.rulePick() + " (order " + r.ruleOrder() + ")");
            }
        }
        report.append("states=").append(results.size())
                .append(" divergences=").append(divergences.size()).append('\n');
        divergences.forEach(d -> report.append("  DIVERGE ").append(d).append('\n'));
        System.out.println(report);

        // F4 pin: wherever SCORE_PLATEAU >= 1.0 and both roles are gate-allowed, merge orders
        // before evaluation (the coupled-pair statement, one place instead of two constants).
        for (StateResult r : results) {
            if (r.ruleOrder().contains("merge") && r.ruleOrder().contains("evaluation")
                    && r.name().contains("plateau")) {
                assertThat(r.ruleOrder().indexOf("merge"))
                        .as("F4: plateau ⇒ merge before evaluation in " + r.name())
                        .isLessThan(r.ruleOrder().indexOf("evaluation"));
            }
        }

        // Pin: agreement across the whole library. Any divergence must be individually
        // justified in the design note and added to the accepted list below, never ignored.
        //
        // log-11 (accepted, justification in the design note's Q-a section): today's planner-
        // first pick arises from fitness SUMMING five co-present near-cap signals — the exact
        // F1 arithmetic this redesign deletes; the rule serves the strongest open request
        // (REPETITION_DETECTED at the 3.0 cap → evolution). The field cycle behind this state
        // actually selected analysis (recordedFirstSelected=analysis, mode UNDIRECTED), so the
        // observer's planner-pick is itself a fidelity-limited reconstruction, not ground truth.
        // The slice-4 equivalence gate re-adjudicates this state with the full world.
        List<String> acceptedDivergences = List.of("log-11: frequent-set-6 (n=18)");
        List<String> unexplained = divergences.stream()
                .filter(d -> acceptedDivergences.stream().noneMatch(d::startsWith))
                .toList();
        assertThat(unexplained).as("selection-rule divergences not documented in the design note")
                .isEmpty();
    }

    private StateResult evaluate(FrozenStateFixtures.FrozenState state) {
        FrozenCycleObserver.Observation obs = FrozenCycleObserver.observe(state.ctx(), state.world());
        if (obs.haltedBeforeRanking()) {
            return new StateResult(state.name(), obs.mode(), null, null, List.of(), List.of(), true);
        }

        // Post-proposal context — the exact carriers production ranking and eligibility read.
        RuleBasedDeliberationProposer proposer = new RuleBasedDeliberationProposer();
        DefaultDeliberationValidator validator = new DefaultDeliberationValidator();
        DeliberationResult dr = validator.validate(proposer.propose(state.ctx()));
        CycleContextData ctxAfter = state.ctx();
        for (Signal s : dr.proposedSignals()) {
            if (s != null) ctxAfter = ctxAfter.withSignal(s);
        }
        for (com.walpro.autobuilder.core.lifecycle.ChannelRequest r : dr.proposedRequests()) {
            if (r != null) ctxAfter = ctxAfter.issueRequest(r);
        }
        for (com.walpro.autobuilder.core.lifecycle.ParticipationWindow w : dr.proposedWindows()) {
            if (w != null) ctxAfter = ctxAfter.grantWindow(w);
        }

        Set<String> gateAllowed = new DeliberationExecutionGate()
                .allowedRoles(DeliberationMode.valueOf(obs.mode()));
        final CycleContextData effective = ctxAfter;

        List<String> ruleOrder = PIPELINE_ORDER.stream()
                .filter(gateAllowed::contains)
                .sorted(Comparator
                        .comparingInt((String role) -> constraintRank(role, effective)).reversed()
                        .thenComparing(Comparator.comparingDouble(
                                (String role) -> urgency(role, effective)).reversed())
                        .thenComparing(Comparator.comparingDouble(
                                (String role) -> windowBoost(role, effective)).reversed())
                        .thenComparing(Comparator.comparingDouble(BASE_PRIORITY::get).reversed())
                        .thenComparing(PIPELINE_ORDER::indexOf))
                .toList();

        String rulePick = ruleOrder.stream()
                .filter(role -> obs.blockersByRole().getOrDefault(role, List.of("absent")).isEmpty())
                .findFirst().orElse(null);

        return new StateResult(state.name(), obs.mode(), obs.firstEligibleRole(), rulePick,
                obs.rankedRoles(), ruleOrder, false);
    }

    /**
     * Explicit constraints — each one a stated production intent, not derived arithmetic:
     * build broken ⇒ analysis first (T2-DE: "the sole diagnostic escape hatch ... must
     * outrank all other agents that remain eligible under the build-block gate") AND
     * planner/evaluation last (T1-DU: a stale strong planner participation must not outrank
     * the build-fix work — the old adjustment was signed, and T4-BZ-4 carries both halves).
     * F4's plateau statement is deliberately NOT a constraint: it only pins the merge-before-
     * evaluation PAIR (asserted separately); the plateau's merge lift is the window grant (2.0),
     * which the preview's first run showed does not outrank an active evolution today.
     */
    private static int constraintRank(String role, CycleContextData ctx) {
        boolean buildBroken = com.walpro.autobuilder.core.lifecycle.DerivedFacts.buildBroken(ctx);
        if (!buildBroken) return 0;
        return switch (role) {
            case "analysis" -> 1;
            case "planner", "evaluation" -> -1;
            default -> 0;
        };
    }

    /** Max urgency over open requests addressed to the role (today's attention-set membership). */
    private static double urgency(String role, CycleContextData ctx) {
        double max = 0.0;
        for (SignalType t : ROLE_SETS.get(role)) {
            if (!REQUEST_FAMILY.contains(t)) continue;
            max = Math.max(max, ctx.requestUrgency(t));
        }
        return max;
    }

    private static double windowBoost(String role, CycleContextData ctx) {
        return ctx.windowBoost(role);
    }
}
