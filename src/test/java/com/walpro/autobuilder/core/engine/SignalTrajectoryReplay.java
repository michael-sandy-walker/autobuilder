package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.deliberation.DefaultDeliberationValidator;
import com.walpro.autobuilder.core.deliberation.DeliberationDecision;
import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.core.deliberation.DeliberationService;
import com.walpro.autobuilder.core.deliberation.RuleBasedDeliberationProposer;
import com.walpro.autobuilder.core.engine.FrozenCycleObserver.StubbedServices;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.SignalType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * T4-J Phase 3d: multi-cycle replay with scripted agent effects.
 *
 * Runs N cycles of the deterministic selection machinery from a starting state and records the
 * decision trace, so second-order signal dynamics — reinforce/attenuate feedback, decay-driven
 * deaths, proposer re-injections — become observable. This is the instrument that can give
 * answers to the decayPerCycle questions: Phase 3b showed decay flips no same-cycle decision; its effects exist
 * only across ticks.
 *
 * Real per cycle: deliberation (production proposer + validator), gate, fitness ranking and the
 * engine's reinforce/attenuate on executed agents (jitter=0), eligibility (production explainer
 * over {@link StubbedServices}), end-of-cycle carrier tick (the
 * CycleExecutor placement).
 *
 * Scripted: what each agent's execute() does to the context — per-scenario
 * {@code role -> UnaryOperator<CycleContextData>} functions standing in for the LLM-driven
 * outcomes. Held constant, documented: CycleExecutor's post-cycle hooks (pattern analyzers,
 * scope guard, interpretation, completion/promotion, subgoal-scoped signal persistence) and the
 * coordinator's next-cycle hydration — the replay carries the context unchanged between cycles
 * except the tick and a minimal per-cycle reset of cycle-scoped fields (merged,
 * evolutionRanThisCycle, subgoalPromoted, projectDiff, mutationState) that the production
 * coordinator/lineage tracker would rebuild.
 */
public final class SignalTrajectoryReplay {

    private SignalTrajectoryReplay() {}

    /** One cycle's decisions and the post-tick signal state. */
    public record CyclePoint(
            int cycle,
            String mode,
            List<String> executedRoles,
            Map<String, Double> signalsAfterTick,
            List<String> deathsThisTick,
            boolean halted
    ) {
        public String decisionSummary() {
            return "mode=" + mode + " executed=" + executedRoles + (halted ? " HALTED" : "");
        }
    }

    public record Trajectory(List<CyclePoint> points) {

        /** First cycle whose mode or executed-role sequence differs; -1 = no divergence. */
        public int firstDecisionDivergence(Trajectory other) {
            int n = Math.min(points.size(), other.points.size());
            for (int i = 0; i < n; i++) {
                CyclePoint a = points.get(i);
                CyclePoint b = other.points.get(i);
                if (!Objects.equals(a.mode(), b.mode())
                        || !Objects.equals(a.executedRoles(), b.executedRoles())
                        || a.halted() != b.halted()) {
                    return a.cycle();
                }
            }
            return points.size() != other.points.size() ? n + 1 : -1;
        }

        /** Cycle at whose end the signal is gone (never re-emitted after); -1 = survives throughout. */
        public int deathCycle(SignalType type) {
            for (int i = points.size() - 1; i >= 0; i--) {
                if (points.get(i).signalsAfterTick().containsKey(type.name())) {
                    return i + 1 < points.size() ? points.get(i + 1).cycle() : -1;
                }
            }
            return points.isEmpty() ? -1 : points.get(0).cycle();
        }
    }

    public static Trajectory replay(CycleContextData start,
                                    StubbedServices world,
                                    Map<String, UnaryOperator<CycleContextData>> roleScripts,
                                    int cycles) {
        RuleBasedDeliberationProposer proposer = new RuleBasedDeliberationProposer();
        DefaultDeliberationValidator validator = new DefaultDeliberationValidator();

        List<String> executedThisCycle = new ArrayList<>();
        String[] modeThisCycle = new String[1];

        DeliberationService deliberation = mock(DeliberationService.class, withSettings().lenient());
        lenient().when(deliberation.deliberate(any(CycleContextData.class))).thenAnswer(inv -> {
            DeliberationResult dr = validator.validate(proposer.propose(inv.getArgument(0)));
            modeThisCycle[0] = dr.mode().name();
            return new DeliberationDecision(inv.getArgument(0), dr);
        });

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                scriptedAgents(roleScripts, executedThisCycle),
                deliberation,
                new DeliberationExecutionGate(),
                FrozenCycleObserver.realExplainer(world));

        List<CyclePoint> points = new ArrayList<>();
        CycleContextData ctx = start;

        for (int cycle = 1; cycle <= cycles; cycle++) {
            executedThisCycle.clear();
            modeThisCycle[0] = null;

            ctx = resetCycleScopedFields(ctx, cycle);

            boolean halted = com.walpro.autobuilder.core.lifecycle.DerivedFacts.goalExhausted(ctx)
                    || com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(ctx);

            ctx = engine.runCycle(ctx);

            Set<String> beforeTick = new java.util.HashSet<>();
            ctx.windows().active().keySet()
                    .forEach(role -> beforeTick.add(BasicSignals.roleSignalFor(role).name()));
            ctx.requests().open().keySet().forEach(t -> beforeTick.add(t.name()));
            // T4-BZ-4/5: windows and requests age once per cycle boundary (the CycleExecutor
            // placement; the replay has no grounding service, so horizons alone govern expiry).
            ctx = ctx.withWindows(ctx.windows().tick().windows());
            ctx = ctx.withRequests(ctx.requests().tick(null).channel());

            // Windows and requests render under their type names so trajectory pins and
            // deathCycle() keep one namespace across the carrier migration.
            Map<String, Double> after = new TreeMap<>();
            ctx.windows().active().forEach((role, w) ->
                    after.put(BasicSignals.roleSignalFor(role).name(), w.boost()));
            ctx.requests().open().forEach((type, r) -> after.put(type.name(), r.urgency()));
            List<String> deaths = beforeTick.stream()
                    .filter(name -> !after.containsKey(name)).sorted().toList();

            points.add(new CyclePoint(cycle, halted ? "HALTED" : modeThisCycle[0],
                    List.copyOf(executedThisCycle), after, deaths, halted));

            if (halted) break;
        }
        return new Trajectory(points);
    }

    /**
     * The production coordinator/lineage tracker rebuilds cycle-scoped state between cycles;
     * the replay resets the fields the selection layer and the scripts read.
     */
    private static CycleContextData resetCycleScopedFields(CycleContextData ctx, int cycle) {
        return ctx
                .withParentCycleName(ctx.cycleName())
                .withCycleName("replay-" + cycle)
                .withMerged(false)
                .withSubgoalPromoted(false)
                .withEvolutionRanThisCycle(false)
                .withProjectDiff(null)
                .withMutationState(null);
    }

    /** Same ranking-relevant surface as FrozenCycleObserver's agents, plus the scripted effect. */
    private static List<ExecutionAgent> scriptedAgents(
            Map<String, UnaryOperator<CycleContextData>> roleScripts, List<String> executed) {
        List<ExecutionAgent> agents = new ArrayList<>();
        record Spec(String role, Set<SignalType> supported, double priority) {}
        for (Spec spec : List.of(
                new Spec("planner", BasicSignals.PLANNER_SIGNALS, 1.0),
                new Spec("evolution", BasicSignals.EVOLUTION_SIGNALS, 3.0),
                new Spec("evaluation", BasicSignals.EVALUATION_SIGNALS, 2.0),
                new Spec("merge", BasicSignals.MERGE_SIGNALS, 4.0),
                new Spec("analysis", BasicSignals.ANALYSIS_SIGNALS, 1.5))) {
            UnaryOperator<CycleContextData> script =
                    roleScripts.getOrDefault(spec.role(), UnaryOperator.identity());
            agents.add(new ExecutionAgent() {
                @Override public String name() { return spec.role(); }
                @Override public String role() { return spec.role(); }
                @Override public Set<SignalType> getSupportedSignals() { return spec.supported(); }
                @Override public double priority(CycleContextData c) { return spec.priority(); }
                @Override public CycleContextData execute(CycleContextData c) {
                    executed.add(spec.role());
                    return script.apply(c);
                }
            });
        }
        return agents;
    }

    /** Renders a trajectory as compact per-cycle lines for the report. */
    public static String render(Trajectory t) {
        StringBuilder sb = new StringBuilder();
        for (CyclePoint p : t.points()) {
            sb.append(String.format("c%02d %s", p.cycle(), p.decisionSummary()));
            if (!p.deathsThisTick().isEmpty()) sb.append(" deaths=").append(p.deathsThisTick());
            sb.append("\n");
        }
        return sb.toString();
    }
}
