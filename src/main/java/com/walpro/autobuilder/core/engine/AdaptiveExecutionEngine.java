package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.coordinator.AgentCoordinator;
import com.walpro.autobuilder.core.deliberation.DeliberationDecision;
import com.walpro.autobuilder.core.deliberation.DeliberationMode;
import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.core.deliberation.DeliberationService;
import com.walpro.autobuilder.core.engine.meta.MetaOverrideCategory;
import com.walpro.autobuilder.core.engine.meta.MetaOverrideEvent;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import com.walpro.autobuilder.core.messaging.AgentMessage;
import com.walpro.autobuilder.core.messaging.AgentMessageType;
import com.walpro.autobuilder.core.messaging.AgentRoles;
import com.walpro.autobuilder.core.messaging.MessageBus;
import com.walpro.autobuilder.protocol.meta.MetaIntention;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * Signal-driven execution:
 * - Rank coordinators by fitness (signal strength * recency + base priority + eval bias + jitter)
 * - Execute if canExecute()
 * - Reinforce/attenuate signal based on observed progress
 */
@Slf4j
@RequiredArgsConstructor
public class AdaptiveExecutionEngine {

    private static final double W_SIGNAL = 2.0;     // weight for signal strength
    private static final double W_RECENCY = 1.0;    // weight for signal recency (age penalty)
    private static final double W_PRIORITY = 1.0;   // weight for coordinator's base priority(context)
    private static final double W_EVALBIAS = 0.1;   // mild global steering from evaluation bias
    private static final double EXPLORATION_JITTER = 0.01;

    private static final double ROLE_MIN_EXEC = 0.10;
    private static final double DEADLOCK_INJECT_STRENGTH = 0.11;

    private static final double SUCCESS_REINFORCE = 1.0;
    private static final double FAIL_ATTENUATION = 0.90;

    private static final int META_MAX_STEPS_PER_CYCLE = 5;

    private final List<ExecutionAgent> executionAgents;

    private final DeliberationService deliberationService;
    private final DeliberationExecutionGate deliberationGate;

    private final MessageBus messageBus;

    private final Random rnd = new Random();

    public CycleContextData runCycle(CycleContextData context) {
        // 0) Pass-header: Deliberate once per pass and apply proposed signals
        DeliberationDecision dd = deliberationService.deliberate(context);
        context = dd.context();
        DeliberationResult dr = dd.result();
        context = applyProposedSignals(context, dr);
        context = context.withDeliberationResult(dr);

        DeliberationMode mode = dr != null ? dr.mode() : DeliberationMode.NOOP;

        // one execution per role per cycle
        final Set<String> executedRoles = new HashSet<>();

        // 1) If meta provided steps, execute them directly in-cycle (LLM orchestration)
        List<MetaStep> metaSteps = extractMetaSteps(dr);
        if (!metaSteps.isEmpty()) {
            CycleContextData beforeMeta = context;
            MetaExecutionResult metaResult = executeMetaSteps(context, metaSteps, mode, executedRoles);
            CycleContextData afterMeta = metaResult.context();

            // If meta did not execute anything, it will usually produce no progress at all.
            // In that case: fall back to normal signal-driven execution.
            boolean metaExecuted = metaResult.executedCount() > 0;
            boolean metaProgressed = hasProgressed(beforeMeta, afterMeta);

            if (metaExecuted && metaProgressed) {
                return afterMeta;
            }

            if (!metaExecuted) {
                publishMetaOverride(beforeMeta, afterMeta, mode, metaSteps, metaResult);
                log.info("Meta steps present but none executable → falling back to signal-driven ordering.");
                afterMeta = injectRequiredRoleSignalForModeIfDeadlocked(context, mode);
            } else {
                // optional but recommended; otherwise we’ll mislabel logs + events
                // publishMetaOverride with a different reasonCode if we want
                log.info("Meta steps executed but no progress detected → falling back to signal-driven ordering.");
            }

            context = afterMeta; // carry forward anyway
        }


        // 2) Otherwise fall back to signal-driven ordering
        List<ExecutionAgent> eligibleByMode = executionAgents.stream()
                .filter(a -> deliberationGate.allows(mode, a.role()))
                .toList();

        if (eligibleByMode.isEmpty()) {
            log.info("DeliberationMode={} → no eligible roles. Ending pass.", mode);
            return context;
        }

        final CycleContextData snapshot = context;

        List<ExecutionAgent> order = eligibleByMode.stream()
                .sorted(Comparator.comparingDouble(
                        (ExecutionAgent agent) -> -fitnessFor(agent, snapshot))) // descending fitness
                .toList();

        log.debug("Signal-driven order this pass: {}", order.stream().map(ExecutionAgent::role).toList());

        // 3) Execute in fitness order with signal reinforcement/attenuation
        for (ExecutionAgent agent : order) {
            String role = agent.role();
            if (!executedRoles.add(role)) {
                log.debug("Skipping role={} (already executed this cycle)", role);
                continue;
            }

            if (!agent.canExecute(context)) continue;

            log.info("Executing coordinator (role={})", agent.coordinator().role());
            CycleContextData before = context;
            context = agent.execute(context);

            SignalType type = signalKeyFor(agent.coordinator());

            if (hasProgressed(before, context)) {
                context = reinforceSignal(context, type);
            } else {
                context = attenuateSignal(context, type);
            }
        }

        // 3) Exploration: if everything is weak, seed a small random nudge
        context = exploreIfNeeded(context);

        return context;
    }

    /** Simple notion of "progress": any change in signals map or known deltas the context tracks. */
    private boolean hasProgressed(CycleContextData before, CycleContextData after) {
        // Keep it conservative: any signal set change or strength change counts as progress.
        Map<SignalType, Signal> a = before.signals();
        Map<SignalType, Signal> b = after.signals();
        if (!a.keySet().equals(b.keySet())) return true;
        for (SignalType k : a.keySet()) {
            Signal sa = a.get(k);
            Signal sb = b.get(k);
            if (sb == null) return true;
            if (Double.compare(sa.strength(), sb.strength()) != 0) return true;
            if (sa.age() != sb.age()) return true;
        }
        return false;
    }

    private double fitnessFor(ExecutionAgent agent, CycleContextData ctx) {
        // Base “identity” signal (ROLE_*) stays meaningful
        SignalType roleKey = signalKeyFor(agent.coordinator());

        // Sum contributions from supported signals
        Set<SignalType> supported = agent.getSupportedSignals();
        if (supported == null || supported.isEmpty()) {
            supported = Set.of(roleKey);
        } else if (!supported.contains(roleKey)) {
            // ensure roleKey always participates
            supported = new java.util.HashSet<>(supported);
            supported.add(roleKey);
        }

        double signalScore = 0.0;
        for (SignalType t : supported) {
            Signal s = ctx.signals().get(t);
            if (s == null) continue;

            double strength = s.strength();
            double recency = Math.exp(-(s.age() / 5.0));

            signalScore += (W_SIGNAL * strength) + (W_RECENCY * recency);
        }

        double basePriority = safePriority(agent.coordinator(), ctx);
        double evalBias = safeEvalBias(ctx);
        double jitter = (rnd.nextDouble() - 0.5) * EXPLORATION_JITTER;

        return signalScore
                + W_PRIORITY * basePriority
                + W_EVALBIAS * evalBias
                + jitter;
    }

    private double safePriority(AgentCoordinator c, CycleContextData ctx) {
        try { return c.priority(ctx); } catch (Throwable t) { return 0.0; }
    }

    private double safeEvalBias(CycleContextData ctx) {
        try {
            Double b = ctx.evaluationBias(); // filled by strategy.adaptAfterCycle
            return b == null ? 0.0 : b;
        } catch (Throwable t) {
            return 0.0;
        }
    }

    // Role signals are identity-stable: ROLE_* SignalType is the identity,
    // strength/decay/age/meta are the mutable state. We never mutate the type
    // for role signals; new types are reserved for future trait/pattern signals.
    private SignalType signalKeyFor(AgentCoordinator c) {
        return switch (c.role()) {
            case "planner"   -> BasicSignals.ROLE_PLANNER;
            case "evolution" -> BasicSignals.ROLE_EVOLUTION;
            case "evaluation"-> BasicSignals.ROLE_EVALUATION;
            case "merge"     -> BasicSignals.ROLE_MERGE;
            default          -> SignalType.of(c.role());
        };
    }

    private CycleContextData reinforceSignal(CycleContextData ctx, SignalType key) {
        Signal current = ctx.signals().getOrDefault(key, Signal.of(key, 0.3, 0.05, key.name()));
        Signal reinforced = current.reinforce(Signal.of(key, SUCCESS_REINFORCE, current.decayPerCycle(), "reinforce"));
        return isRoleSignal(key)
                ? ctx.withSignalReplaceRoleSignals(reinforced)
                : ctx.withSignal(reinforced);
    }

    private CycleContextData attenuateSignal(CycleContextData ctx, SignalType key) {
        Signal current = ctx.signals().get(key);
        if (current == null) return ctx;
        // small genetic-style drift (slight mutation) to escape local optima
        Signal drifted = new Signal(current.type(), current.strength() * FAIL_ATTENUATION,
                current.decayPerCycle(), current.age(), current.source(), Map.of())/*.mutate("~")*/; // TODO: potentially use mutate to spawn *new* trait/pattern signal types (e.g., “EVOLUTION_PATTERN_X”), not for core ROLE_* signals (MW; 2025-DEC-06)
        return isRoleSignal(key)
                ? ctx.withSignalReplaceRoleSignals(drifted)
                : ctx.withSignal(drifted);
    }

    private CycleContextData exploreIfNeeded(CycleContextData ctx) {
        if (ctx.signals().isEmpty()) return ctx;

        double avg = ctx.signals().values().stream().mapToDouble(Signal::strength).average().orElse(0.0);
        if (avg > 0.08) return ctx;

        // Find the weakest signal and give it a tiny nudge (exploration)
        SignalType weakest = ctx.signals().entrySet().stream()
                .min(Comparator.comparingDouble(e -> e.getValue().strength()))
                .map(Map.Entry::getKey)
                .orElse(null);

        if (weakest == null) return ctx;

        Signal s = ctx.signals().get(weakest);
        Signal nudged = Signal.of(s.type(), Math.min(0.12, s.strength() + 0.04), s.decayPerCycle(), "explore");
        return ctx.withSignal(nudged);
    }

    private CycleContextData applyProposedSignals(CycleContextData ctx, DeliberationResult dr) {
        if (ctx == null || dr == null || dr.proposedSignals() == null || dr.proposedSignals().isEmpty()) {
            return ctx;
        }
        CycleContextData out = ctx;
        for (Signal s : dr.proposedSignals()) {
            if (s != null) out = out.withSignal(s);
        }
        return out;
    }

    private boolean isRoleSignal(SignalType type) {
        return type.name().startsWith("ROLE_");
    }

    private MetaExecutionResult executeMetaSteps(CycleContextData context,
                                                 List<MetaStep> steps,
                                                 DeliberationMode mode,
                                                 Set<String> executedRoles) {

        int executed = 0;
        List<SkippedMetaStep> skipped = new ArrayList<>();

        for (MetaStep step : steps) {
            if (executed >= META_MAX_STEPS_PER_CYCLE) {
                skipped.add(new SkippedMetaStep(step.index(), step.targetRole(), step.request(), "STEP_CAP_REACHED"));
                log.info("Meta step cap reached ({}).", META_MAX_STEPS_PER_CYCLE);
                break;
            }

            if (!executedRoles.add(step.targetRole())) {
                skipped.add(new SkippedMetaStep(step.index(), step.targetRole(), step.request(), "ROLE_ALREADY_EXECUTED"));
                log.debug("Skipping role={} (already executed this cycle)", step.targetRole());
                continue;
            }

            ExecutionAgent agent = findAgentForTargetRole(step.targetRole());
            if (agent == null) {
                skipped.add(new SkippedMetaStep(step.index(), step.targetRole(), step.request(), "NO_AGENT_FOR_ROLE"));
                log.warn("Meta step {} skipped: no agent found for TargetRole={}", step.index(), step.targetRole());
                continue;
            }

            if (!deliberationGate.allows(mode, agent.role())) {
                skipped.add(new SkippedMetaStep(step.index(), agent.role(), step.request(), "MODE_BLOCKS_ROLE"));
                log.info("Meta step {} skipped: DeliberationMode={} blocks role={}", step.index(), mode, agent.role());
                continue;
            }

            if (!agent.canExecute(context)) {
                skipped.add(new SkippedMetaStep(step.index(), agent.role(), step.request(), "CAN_EXECUTE_FALSE"));
                log.info("Meta step {} skipped: canExecute=false for role={}", step.index(), agent.role());
                continue;
            }

            log.info("Meta step {} executing role={} request='{}'", step.index(), agent.role(), step.request());

            CycleContextData before = context;
            MetaIntention metaIntention = new MetaIntention(agent.role(), step.request);
            context = agent.execute(context.withMetaIntention(metaIntention));
            executed++;

            SignalType type = signalKeyFor(agent.coordinator());
            if (hasProgressed(before, context)) {
                context = reinforceSignal(context, type);
            } else {
                context = attenuateSignal(context, type);
            }

//            if (shouldStopEarly(context, step.stopIf())) { // TODO: Think about this, currently it's blocking further steps like merge
//                log.info("Meta step {} stop condition met: {}", step.index(), step.stopIf());
//                break;
//            }
        }

        context = exploreIfNeeded(context);
        return new MetaExecutionResult(context, executed, skipped);
    }

    private ExecutionAgent findAgentForTargetRole(String targetRole) {
        if (targetRole == null || targetRole.isBlank()) return null;
        String t = targetRole.trim().toLowerCase();

        // coordinator roles are lowercase: planner/evolution/evaluation/merge
        return executionAgents.stream()
                .filter(a -> a.role() != null && a.role().equalsIgnoreCase(t))
                .findFirst()
                .orElse(null);
    }

    private boolean shouldStopEarly(CycleContextData ctx, String stopIf) {
        if (stopIf == null || stopIf.isBlank()) return false;
        String s = stopIf.toLowerCase();

        // minimal, intentionally dumb but useful guards (upgrade later if desired)
        if (s.contains("mutationacceptancestate") && ctx.mutationState() != null) {
            if (s.contains("accepted") && ctx.mutationState() == MutationAcceptanceState.ACCEPTED) return true;
            if (s.contains("rejected") && ctx.mutationState() == MutationAcceptanceState.REJECTED) return true;
        }

        if (s.contains("goalprogressstate") && ctx.goalProgress() != null) {
            if (s.contains("progressing") && ctx.goalProgress() == GoalProgressState.PROGRESSING) return true;
            if (s.contains("stagnating") && ctx.goalProgress() == GoalProgressState.STAGNATING) return true;
        }

        if (s.contains("hasplanneroutcome") && ctx.plannerOutcome() != null) return true;
        if (s.contains("hasevolutionoutcome") && ctx.evolutionOutcome() != null) return true;
        if (s.contains("hasevaluationoutcome") && ctx.evaluationOutcome() != null) return true;

        return false;
    }

    private List<MetaStep> extractMetaSteps(DeliberationResult dr) {
        if (dr == null || dr.meta() == null) return List.of();
        Object o = dr.meta().get("metaControl.steps");
        if (!(o instanceof List<?> list)) return List.of();

        return list.stream()
                .filter(x -> x instanceof String)
                .map(x -> (String) x)
                .map(this::parseMetaStepLine)
                .filter(x -> x != null && x.targetRole() != null && !x.targetRole().isBlank())
                .sorted(Comparator.comparingInt(MetaStep::index))
                .toList();
    }

    private MetaStep parseMetaStepLine(String line) {
        // index|Need|TargetRole|Request|StopIf
        if (line == null) return null;
        String[] parts = line.split("\\|", 5);
        if (parts.length < 4) return null;

        int idx;
        try { idx = Integer.parseInt(parts[0].trim()); }
        catch (Exception e) { idx = 0; }

        String need = parts[1].trim();
        String targetRole = parts[2].trim();
        String request = parts[3].trim();
        String stopIf = (parts.length >= 5) ? parts[4].trim() : "";

        // ignore NONE steps (no execution)
        if ("NONE".equalsIgnoreCase(need)) return null;

        return new MetaStep(idx, need, targetRole, request, stopIf);
    }

    private void publishMetaOverride(
            CycleContextData beforeMeta,
            CycleContextData afterMeta,
            DeliberationMode mode,
            List<MetaStep> requestedSteps,
            MetaExecutionResult result
    ) {

        String ts = Instant.now().toString();

        MetaOverrideEvent event = new MetaOverrideEvent(
                afterMeta.cycleName(),
                "META_STEPS_ALL_SKIPPED",                 // keep emergent string
                MetaOverrideCategory.EXECUTION_BLOCKED,   // Variant A stays
                mode != null ? mode.name() : "NOOP",
                afterMeta.currentGoalId(),
                afterMeta.currentSubGoalId(),
                requestedSteps != null ? requestedSteps.size() : 0,
                result.executedCount(),
                result.skippedSteps().stream()
                        .map(s -> {
                            Map<String, Object> m = new java.util.HashMap<>();
                            m.put("index", s.index());
                            m.put("role", s.role());
                            m.put("reason", s.reason());
                            return m;
                        })
                        .toList(),
                extractSignalSnapshot(afterMeta),
                ts
        );

        Map<String, Object> metaDelta = buildMetaDelta(beforeMeta, afterMeta);

        messageBus.publish(new AgentMessage(
                AgentRoles.SYSTEM,
                AgentRoles.META,
                AgentMessageType.INSIGHT,
                "meta-override",
                null,
                Map.of(
                        "event", event,
                        "metaDelta", metaDelta
                )
        ));
    }

    private Map<String, Object> buildMetaDelta(CycleContextData beforeMeta, CycleContextData afterMeta) {
        Map<String, Object> delta = new HashMap<>();

        if (beforeMeta == null || afterMeta == null) {
            delta.put("available", false);
            return delta;
        }

        delta.put("available", true);

        // 1) Goal/subgoal boundary changes (rare, but crucial)
        putIfChanged(delta, "currentGoalId", beforeMeta.currentGoalId(), afterMeta.currentGoalId());
        putIfChanged(delta, "currentSubGoalId", beforeMeta.currentSubGoalId(), afterMeta.currentSubGoalId());

        // 2) Deliberation mode can flip externally; track it if we persist it in context
        // putIfChanged(delta, "deliberationMode", beforeMeta.deliberationResult()?.mode(), afterMeta.deliberationResult()?.mode());

        // 3) Signals delta (added/removed/strength changed) – compact + stable
        delta.put("signalDelta", computeSignalDelta(beforeMeta.signals(), afterMeta.signals()));

        // 4) “Key state” delta if we want it (safe + compact)
        putIfChanged(delta, "cycleResultState", beforeMeta.cycleResultState(), afterMeta.cycleResultState());
        putIfChanged(delta, "mutationState", beforeMeta.mutationState(), afterMeta.mutationState());
        putIfChanged(delta, "goalProgress", beforeMeta.goalProgress(), afterMeta.goalProgress());

        // 5) Optional: if we track “why” fields
        putIfChanged(delta, "lastEvolutionFingerprint", beforeMeta.lastEvolutionFingerprint(), afterMeta.lastEvolutionFingerprint());

        return delta;
    }

    private Map<String, Object> computeSignalDelta(Map<SignalType, Signal> before, Map<SignalType, Signal> after) {
        Map<String, Object> out = new HashMap<>();
        Map<SignalType, Signal> a = before != null ? before : Map.of();
        Map<SignalType, Signal> b = after != null ? after : Map.of();

        Set<SignalType> all = new HashSet<>();
        all.addAll(a.keySet());
        all.addAll(b.keySet());

        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        List<Map<String, Object>> changed = new ArrayList<>();

        for (SignalType k : all) {
            Signal sa = a.get(k);
            Signal sb = b.get(k);

            if (sa == null && sb != null) {
                added.add(Map.of(
                        "type", k.name(),
                        "strength", sb.strength(),
                        "age", sb.age()
                ));
                continue;
            }

            if (sa != null && sb == null) {
                removed.add(Map.of(
                        "type", k.name(),
                        "strength", sa.strength(),
                        "age", sa.age()
                ));
                continue;
            }

            // both present
            if (sa != null && sb != null) {
                boolean strengthChanged = Double.compare(sa.strength(), sb.strength()) != 0;
                boolean ageChanged = sa.age() != sb.age();
                boolean decayChanged = Double.compare(sa.decayPerCycle(), sb.decayPerCycle()) != 0;

                if (strengthChanged || ageChanged || decayChanged) {
                    changed.add(Map.of(
                            "type", k.name(),
                            "beforeStrength", sa.strength(),
                            "afterStrength", sb.strength(),
                            "beforeAge", sa.age(),
                            "afterAge", sb.age()
                    ));
                }
            }
        }

        out.put("addedCount", added.size());
        out.put("removedCount", removed.size());
        out.put("changedCount", changed.size());

        // keep details, but compact
        out.put("added", added);
        out.put("removed", removed);
        out.put("changed", changed);

        return out;
    }

    private void putIfChanged(Map<String, Object> delta, String key, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            delta.put(key, Map.of("before", before, "after", after));
        }
    }

    private Map<String, Double> extractSignalSnapshot(CycleContextData ctx) {
        Map<String, Double> out = new HashMap<>();
        if (ctx == null || ctx.signals() == null || ctx.signals().isEmpty()) {
            return out;
        }
        for (Map.Entry<SignalType, Signal> e : ctx.signals().entrySet()) {
            SignalType type = e.getKey();
            Signal s = e.getValue();
            if (type == null || s == null) continue;
            out.put(type.name(), s.strength());
        }
        return out;
    }

    private CycleContextData injectRequiredRoleSignalForModeIfDeadlocked(CycleContextData ctx, DeliberationMode mode) {
        if (ctx == null || mode == null) return ctx;

        // If mode allows multiple roles, we don’t force anything here.
        Set<String> allowed = deliberationGate.allowedRoles(mode);
        if (allowed == null || allowed.size() != 1) return ctx;

        String requiredRole = allowed.iterator().next().toLowerCase();

        SignalType requiredRoleSignal = switch (requiredRole) {
            case "planner" -> BasicSignals.ROLE_PLANNER;
            case "evolution" -> BasicSignals.ROLE_EVOLUTION;
            case "evaluation" -> BasicSignals.ROLE_EVALUATION;
            case "merge" -> BasicSignals.ROLE_MERGE;
            default -> null;
        };

        if (requiredRoleSignal == null) return ctx;

        // If it’s already strong enough, nothing to do.
        if (ctx.hasStrongSignal(requiredRoleSignal, ROLE_MIN_EXEC)) return ctx;

        // Inject the specific missing signal, with metadata describing deadlock.
        Map<String, Object> meta = new HashMap<>();
        meta.put("deadlock", true);
        meta.put("deadlock.mode", mode.name());
        meta.put("deadlock.requiredRole", requiredRole);
        meta.put("deadlock.reason", "MODE_REQUIRES_ROLE_SIGNAL_BELOW_THRESHOLD");

        Signal injected = new Signal(
                requiredRoleSignal,
                DEADLOCK_INJECT_STRENGTH,
                0.25,
                0,
                "deadlock-inject",
                meta
        );

        // Role signals should replace role signals (as we already do elsewhere).
        return ctx.withSignalReplaceRoleSignals(injected);
    }

    private record SkippedMetaStep(int index, String role, String request, String reason) {}

    private record MetaExecutionResult(CycleContextData context,
                                       int executedCount,
                                       List<SkippedMetaStep> skippedSteps) {}

    private record MetaStep(int index, String need, String targetRole, String request, String stopIf) {}
}