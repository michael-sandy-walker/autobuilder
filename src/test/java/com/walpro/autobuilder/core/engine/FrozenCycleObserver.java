package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.build.BuildBlockGate;
import com.walpro.autobuilder.core.deliberation.DefaultDeliberationValidator;
import com.walpro.autobuilder.core.deliberation.DeliberationDecision;
import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.core.deliberation.DeliberationService;
import com.walpro.autobuilder.core.deliberation.RuleBasedDeliberationProposer;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.CycleExecutor;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.core.service.merge.FileVariantCollector;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalKindClassifier;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshotService;
import com.walpro.autobuilder.observability.behaviour.BehaviourEvent;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * T4-J Phase 3: frozen-cycle decision observer (T4-J Phase 3).
 *
 * Recomputes every deterministic decision the signal layer drives for a single frozen
 * {@link CycleContextData} — deliberation mode (real {@link RuleBasedDeliberationProposer} +
 * {@link DefaultDeliberationValidator}), gate-allowed roles, the full fitness ranking (via the
 * real {@link AdaptiveExecutionEngine} — deterministic by construction since T4-BZ-4, captured through the
 * BehaviourLogger callback before any agent executes), per-role eligibility (real
 * {@link ExecutionEligibilityExplainer} against a configurable {@link StubbedServices} service backdrop),
 * and per-signal ticks-to-death (real {@link Signal#tick()} arithmetic).
 *
 * Held constant by design (documented fidelity limits): GoalExhaustionDetector (graph-driven —
 * express exhaustion by placing SUBGOAL_EXHAUSTED in the state directly), the deliberation
 * registrar, and everything agents would emit during execution.
 */
public final class FrozenCycleObserver {

    private FrozenCycleObserver() {}

    /** Service-layer backdrop for a frozen state — everything eligibility reads beside the context. */
    public record StubbedServices(
            GoalEntity currentSubGoal,
            GoalEntity rootGoal,
            boolean hasAnyVariant,
            boolean variantsTrackAcceptance,
            boolean hasAcceptanceCriteria,
            boolean allCriteriaBanked,
            GoalKindClassifier.GoalKind classifiedKind,
            boolean buildBlockActive,
            Set<String> buildEligibleRoles
    ) {
        public static StubbedServices defaults() {
            GoalEntity sub = new GoalEntity("sg-1", "implement the current subgoal", "planner",
                    com.walpro.autobuilder.goal.GoalType.SUBGOAL, "root-1");
            sub.setKind(GoalKindClassifier.GoalKind.EXECUTABLE);
            GoalEntity root = new GoalEntity("root-1", "root goal", "planner",
                    com.walpro.autobuilder.goal.GoalType.GOAL, null);
            root.getMetadata().put("plannedOnce", true);
            return new StubbedServices(sub, root, false, false, false, false,
                    GoalKindClassifier.GoalKind.EXECUTABLE, false, Set.of());
        }

        public StubbedServices withVariants() {
            return new StubbedServices(currentSubGoal, rootGoal, true, variantsTrackAcceptance,
                    hasAcceptanceCriteria, allCriteriaBanked, classifiedKind, buildBlockActive, buildEligibleRoles);
        }

        /**
         * Replay-grade variant model: an unmerged variant exists exactly while the current
         * context carries an ACCEPTED, not-yet-merged mutation — a merge consumes it. The
         * static withVariants() flag models a single frozen instant; over multiple cycles it
         * would keep a phantom variant alive after every merge (observed effect before this
         * existed: merge executes first each cycle, ends it, and the replay livelocks).
         */
        public StubbedServices withVariantsFollowingAcceptance() {
            return new StubbedServices(currentSubGoal, rootGoal, hasAnyVariant, true,
                    hasAcceptanceCriteria, allCriteriaBanked, classifiedKind, buildBlockActive, buildEligibleRoles);
        }

        public StubbedServices withBuildBlock(Set<String> eligibleRoles) {
            return new StubbedServices(currentSubGoal, rootGoal, hasAnyVariant, variantsTrackAcceptance,
                    hasAcceptanceCriteria, allCriteriaBanked, classifiedKind, true, eligibleRoles);
        }

        public StubbedServices withKind(GoalKindClassifier.GoalKind kind) {
            GoalEntity sub = currentSubGoal;
            if (sub != null) sub.setKind(kind);
            return new StubbedServices(sub, rootGoal, hasAnyVariant, variantsTrackAcceptance,
                    hasAcceptanceCriteria, allCriteriaBanked, kind, buildBlockActive, buildEligibleRoles);
        }
    }

    /** A named single-value change to a frozen state. */
    public record Perturbation(String name, SignalType touches, UnaryOperator<CycleContextData> apply) {

        public static Perturbation ablate(SignalType type) {
            return new Perturbation("ablate " + type.name(), type, ctx -> ctx.withoutSignal(type));
        }

        public static Perturbation strength(SignalType type, double v) {
            return new Perturbation("strength " + type.name() + "=" + v, type,
                    ctx -> mutate(ctx, type, s -> new Signal(s.type(), v, s.decayPerCycle(), s.age(), s.source(), s.meta())));
        }

        public static Perturbation decay(SignalType type, double v) {
            return new Perturbation("decay " + type.name() + "=" + v, type,
                    ctx -> mutate(ctx, type, s -> new Signal(s.type(), s.strength(), v, s.age(), s.source(), s.meta())));
        }

        public static Perturbation age(SignalType type, int v) {
            return new Perturbation("age " + type.name() + "=" + v, type,
                    ctx -> mutate(ctx, type, s -> new Signal(s.type(), s.strength(), s.decayPerCycle(), v, s.source(), s.meta())));
        }

        private static CycleContextData mutate(CycleContextData ctx, SignalType type, UnaryOperator<Signal> f) {
            Signal s = ctx.getSignal(type);
            if (s == null) return ctx;
            Map<SignalType, Signal> updated = new LinkedHashMap<>(ctx.signals());
            updated.put(type, f.apply(s));
            return ctx.withSignals(updated);
        }
    }

    /**
     * Exact ties happen structurally: role signals saturate at MAX_STRENGTH and the role fitness
     * sets overlap heavily, so two roles' sums can be mathematically identical — differing only
     * in floating-point summation order (last ULP). Ranked order is therefore canonicalized into
     * epsilon-tiers (alphabetical within a tier) so ULP noise never reads as a decision flip.
     */
    public static final double TIE_EPSILON = 1e-6;

    /** Production's exploration jitter: fitness gaps below this are coin-flips in the field. */
    public static final double JITTER = 0.01;

    /** Every deterministic decision the signal layer produced for one frozen state. */
    public record Observation(
            String mode,
            List<String> proposedSignals,
            boolean haltedBeforeRanking,
            List<String> rankedRoles,
            Map<String, Double> fitness,
            Map<String, List<String>> blockersByRole,
            String firstEligibleRole,
            Map<String, Integer> ticksToDeath
    ) {
        public String topRankedRole() {
            return rankedRoles.isEmpty() ? null : rankedRoles.get(0);
        }

        /** Decision-level differences vs a baseline; "ticks[...]" entries are dynamics, the rest are decisions. */
        public List<String> deltasVs(Observation base) {
            List<String> out = new ArrayList<>();
            if (!Objects.equals(mode, base.mode)) out.add("mode: " + base.mode + " -> " + mode);
            if (haltedBeforeRanking != base.haltedBeforeRanking)
                out.add("halted: " + base.haltedBeforeRanking + " -> " + haltedBeforeRanking);
            if (!Objects.equals(topRankedRole(), base.topRankedRole()))
                out.add("topRanked: " + base.topRankedRole() + " -> " + topRankedRole());
            else if (!rankedRoles.equals(base.rankedRoles))
                out.add("order: " + base.rankedRoles + " -> " + rankedRoles);
            if (!Objects.equals(firstEligibleRole, base.firstEligibleRole))
                out.add("firstEligible: " + base.firstEligibleRole + " -> " + firstEligibleRole);
            for (String role : blockersByRole.keySet()) {
                boolean was = base.blockersByRole.getOrDefault(role, List.of("(absent)")).isEmpty();
                boolean is = blockersByRole.get(role).isEmpty();
                if (was != is) out.add("eligible[" + role + "]: " + was + " -> " + is);
            }
            for (String sig : ticksToDeath.keySet()) {
                Integer was = base.ticksToDeath.get(sig);
                Integer is = ticksToDeath.get(sig);
                if (was != null && !Objects.equals(was, is))
                    out.add("ticks[" + sig + "]: " + fmtTicks(was) + " -> " + fmtTicks(is));
            }
            return out;
        }

        public List<String> decisionDeltasVs(Observation base) {
            return deltasVs(base).stream().filter(d -> !d.startsWith("ticks[")).toList();
        }

        private static String fmtTicks(Integer t) {
            return t == null ? "?" : (t < 0 ? "never" : String.valueOf(t));
        }
    }

    public static Observation observe(CycleContextData state, StubbedServices world) {
        // 1) Mode: the production proposer + validator, minus the graph-driven exhaustion
        //    detector (exhaustion is expressed by placing SUBGOAL_EXHAUSTED in the state).
        RuleBasedDeliberationProposer proposer = new RuleBasedDeliberationProposer();
        DefaultDeliberationValidator validator = new DefaultDeliberationValidator();
        DeliberationResult dr = validator.validate(proposer.propose(state));

        boolean halted = state.hasSignal(BasicSignals.GOAL_EXHAUSTED) || state.hasSignal(BasicSignals.SYSTEM_PAUSED);

        // Post-proposal context: what ranking and eligibility actually see in production.
        CycleContextData ctxAfter = state;
        for (Signal s : dr.proposedSignals()) {
            if (s != null) ctxAfter = ctxAfter.withSignal(s);
        }
        // T4-BZ-3: the proposer's request-family emissions ride the typed carrier now;
        // production's applyProposals enqueues them — mirror that here.
        for (com.walpro.autobuilder.core.lifecycle.ChannelRequest r : dr.proposedRequests()) {
            if (r != null) ctxAfter = ctxAfter.issueRequest(r);
        }
        // T4-BZ-4: role-participation proposals grant windows.
        for (com.walpro.autobuilder.core.lifecycle.ParticipationWindow w : dr.proposedWindows()) {
            if (w != null) ctxAfter = ctxAfter.grantWindow(w);
        }
        ctxAfter = ctxAfter.withDeliberationResult(dr);

        // 2) Ranking: the real engine, jitter=0, rankings captured before any agent executes.
        List<BehaviourEvent.AgentRanking> captured = new ArrayList<>();
        BehaviourLogger logger = mock(BehaviourLogger.class, withSettings().lenient());
        doAnswer(inv -> {
            captured.addAll(inv.getArgument(1));
            return null;
        }).when(logger).agentFitnessRanked(any(), any());

        DeliberationService deliberation = mock(DeliberationService.class, withSettings().lenient());
        lenient().when(deliberation.deliberate(any(CycleContextData.class)))
                .thenAnswer(inv -> new DeliberationDecision(inv.getArgument(0),
                        validator.validate(proposer.propose(inv.getArgument(0)))));

        ExecutionEligibilityExplainer neverEligible =
                mock(ExecutionEligibilityExplainer.class, withSettings().lenient());
        lenient().when(neverEligible.canExecute(any(), any())).thenReturn(false);
        lenient().when(neverEligible.explainBlockers(any(), any())).thenReturn(List.of("observer-stub"));

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                stubAgents(), deliberation, new DeliberationExecutionGate(), neverEligible);
        engine.setBehaviourLogger(logger);
        if (world.buildBlockActive()) {
            engine.setBuildBlockGate(mock(BuildBlockGate.class, withSettings().lenient()));
        }
        engine.runCycle(state);

        List<BehaviourEvent.AgentRanking> ranked = captured.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .toList();
        List<String> rankedRoles = canonicalOrder(ranked);
        Map<String, Double> fitness = new LinkedHashMap<>();
        ranked.forEach(r -> fitness.put(r.role(), r.score()));

        // 3) Eligibility: the real explainer against the configured service backdrop.
        ExecutionEligibilityExplainer explainer = realExplainer(world);
        Map<String, List<String>> blockers = new LinkedHashMap<>();
        for (String role : List.of("planner", "evolution", "evaluation", "merge", "analysis")) {
            blockers.put(role, explainer.explainBlockers(role, ctxAfter));
        }
        String firstEligible = rankedRoles.stream()
                .filter(r -> blockers.getOrDefault(r, List.of("x")).isEmpty())
                .findFirst().orElse(null);

        // 4) Decay dynamics: production tick arithmetic on the post-proposal signal set.
        Map<String, Integer> ticks = new LinkedHashMap<>();
        ctxAfter.signals().values().stream()
                .sorted((a, b) -> a.type().name().compareTo(b.type().name()))
                .forEach(s -> ticks.put(s.type().name(), ticksToDeath(s)));

        // T4-BZ-3: proposed request-family entries render in the same NAME=value shape so the
        // sweep reports keep one list; requests follow the signals in the rendered order.
        List<String> proposed = new ArrayList<>();
        dr.proposedSignals().stream()
                .filter(Objects::nonNull)
                .map(s -> s.type().name() + "=" + s.strength())
                .forEach(proposed::add);
        dr.proposedRequests().stream()
                .filter(Objects::nonNull)
                .map(r -> r.kind().name() + "=" + r.urgency())
                .forEach(proposed::add);
        // T4-BZ-4: window grants render under their ROLE_* names — one list, one namespace.
        dr.proposedWindows().stream()
                .filter(Objects::nonNull)
                .map(w -> BasicSignals.roleSignalFor(w.role()).name() + "=" + w.boost())
                .forEach(proposed::add);

        return new Observation(dr.mode().name(), proposed, halted,
                rankedRoles, fitness, blockers, firstEligible, ticks);
    }

    /** Fitness-descending order with epsilon-tied roles resolved alphabetically (see TIE_EPSILON). */
    private static List<String> canonicalOrder(List<BehaviourEvent.AgentRanking> rankedDesc) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < rankedDesc.size()) {
            double head = rankedDesc.get(i).score();
            List<String> tier = new ArrayList<>();
            while (i < rankedDesc.size() && head - rankedDesc.get(i).score() < TIE_EPSILON) {
                tier.add(rankedDesc.get(i).role());
                i++;
            }
            tier.sort(String::compareTo);
            out.addAll(tier);
        }
        return out;
    }

    /**
     * Role pairs whose fitness gap is below production's exploration jitter — decisions the
     * field system makes by coin-flip. Rendered in the report so boundary readings are honest.
     */
    public static List<String> subJitterPairs(Observation obs) {
        List<Map.Entry<String, Double>> entries = new ArrayList<>(obs.fitness().entrySet());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            for (int j = i + 1; j < entries.size(); j++) {
                if (Math.abs(entries.get(i).getValue() - entries.get(j).getValue()) < JITTER) {
                    out.add(entries.get(i).getKey() + "~" + entries.get(j).getKey());
                }
            }
        }
        return out;
    }

    /** Cycles until the signal dies at SIGNAL_SURVIVAL_THRESHOLD; -1 = never (within 60 cycles). */
    public static int ticksToDeath(Signal s) {
        Signal cur = s;
        for (int t = 1; t <= 60; t++) {
            cur = cur.tick();
            if (!cur.isAlive(CycleExecutor.SIGNAL_SURVIVAL_THRESHOLD)) return t;
        }
        return -1;
    }

    /**
     * Stub agents mirroring the production fleet's ranking-relevant surface: role, the
     * BasicSignals fitness sets, and each agent's priority (AnalysisAgent overrides to 1.5;
     * the rest use the ExecutionAgent interface defaults).
     */
    private static List<ExecutionAgent> stubAgents() {
        return List.of(
                stub("planner", BasicSignals.PLANNER_SIGNALS, 1.0),
                stub("evolution", BasicSignals.EVOLUTION_SIGNALS, 3.0),
                stub("evaluation", BasicSignals.EVALUATION_SIGNALS, 2.0),
                stub("merge", BasicSignals.MERGE_SIGNALS, 4.0),
                stub("analysis", BasicSignals.ANALYSIS_SIGNALS, 1.5)
        );
    }

    private static ExecutionAgent stub(String role, Set<SignalType> supported, double priority) {
        return new ExecutionAgent() {
            @Override public String name() { return role; }
            @Override public String role() { return role; }
            @Override public Set<SignalType> getSupportedSignals() { return supported; }
            @Override public double priority(CycleContextData ctx) { return priority; }
            @Override public CycleContextData execute(CycleContextData ctx) { return ctx; }
        };
    }

    /** Package-visible: SignalTrajectoryReplay drives the same explainer over the same stubs. */
    static ExecutionEligibilityExplainer realExplainer(StubbedServices world) {
        GoalPlannerService goals = mock(GoalPlannerService.class, withSettings().lenient());
        lenient().when(goals.getCurrentSubGoal()).thenReturn(world.currentSubGoal());
        lenient().when(goals.getGoalById(anyString())).thenReturn(world.currentSubGoal());
        lenient().when(goals.getRootGoal()).thenReturn(world.rootGoal());
        lenient().when(goals.hasAcceptanceCriteria(anyString())).thenReturn(world.hasAcceptanceCriteria());
        lenient().when(goals.allCriteriaDefinedAndBanked(anyString())).thenReturn(world.allCriteriaBanked());

        FileVariantCollector variants = mock(FileVariantCollector.class, withSettings().lenient());
        try {
            lenient().when(variants.hasAnyVariant(any())).thenAnswer(inv -> {
                if (world.hasAnyVariant()) return true;
                if (!world.variantsTrackAcceptance()) return false;
                CycleContextData ctx = inv.getArgument(0);
                return ctx != null && !ctx.merged()
                        && ctx.mutationState() == com.walpro.autobuilder.model.MutationAcceptanceState.ACCEPTED;
            });
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e); // unreachable: stubbing a mock cannot throw
        }

        OrchestrationSnapshotService snapshots =
                mock(OrchestrationSnapshotService.class, withSettings().lenient());

        GoalKindClassifier classifier = mock(GoalKindClassifier.class, withSettings().lenient());
        lenient().when(classifier.classify(any(GoalEntity.class))).thenReturn(world.classifiedKind());
        lenient().when(classifier.classify(anyString())).thenReturn(world.classifiedKind());

        ExecutionEligibilityExplainer explainer =
                new ExecutionEligibilityExplainer(variants, snapshots, goals, classifier);

        BuildBlockGate gate = mock(BuildBlockGate.class, withSettings().lenient());
        lenient().when(gate.isActive(any())).thenReturn(world.buildBlockActive());
        lenient().when(gate.isEligible(anyString(), any()))
                .thenAnswer(inv -> world.buildEligibleRoles().contains((String) inv.getArgument(0)));
        explainer.setBuildBlockGate(gate);

        return explainer;
    }
}
