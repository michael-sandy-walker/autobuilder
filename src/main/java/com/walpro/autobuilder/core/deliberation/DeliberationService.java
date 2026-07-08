package com.walpro.autobuilder.core.deliberation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.walpro.autobuilder.agent.meta.MetaDeliberationAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.walpro.autobuilder.analysis.exhaustion.ExhaustionReport;
import com.walpro.autobuilder.analysis.exhaustion.GoalExhaustionDetector;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.graph.deliberation.DeliberationActionRegistrar;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;

@Component
@RequiredArgsConstructor
@Slf4j
public class DeliberationService {

    private final GoalExhaustionDetector goalExhaustionDetector;
    private final DeliberationProposer proposer;
    private final DeliberationValidator validator;
    private final DeliberationActionRegistrar registrar;
    private final MetaDeliberationAgent metaDeliberationAgent;

    /** T5-H: optional behaviour trace logger. */
    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    private static final String DELIBERATION_AGENT_ID = "deliberation";

    public DeliberationDecision deliberate(CycleContextData context) {
        if (context == null) {
            DeliberationResult r = DeliberationResult.noop("Context is null.");
            return new DeliberationDecision(null, r);
        }

        // Step A: detect exhaustion (generic) and materialize it as a signal in the returned context
        CycleContextData updated = applyGoalExhaustionSignal(context);

        // Step B: propose deliberation based on the updated state
        DeliberationResult proposed = proposer.propose(updated);

        // Step C: merge in exhaustion evidence into deliberation meta (so it shows up in graph)
        DeliberationResult withEvidence = enrichWithExhaustionEvidence(updated, proposed);

        // Step C2: meta review (LLM) — enrich deliberation meta and optionally propose one role signal
        DeliberationResult withMeta = enrichWithMetaDeliberation(updated, withEvidence);

        // Step D: validate + record
        DeliberationResult validated = validator.validate(withMeta);
        registrar.registerDeliberation(DELIBERATION_AGENT_ID, updated, validated);

        if (behaviourLogger != null && validated != null) {
            List<String> proposedSignals = validated.proposedSignals() != null
                    ? validated.proposedSignals().stream()
                        .filter(java.util.Objects::nonNull)
                        .map(s -> s.type().name() + "=" + s.strength())
                        .toList()
                    : List.of();
            List<String> agentSteps = (validated.meta() != null
                    && validated.meta().get("metaControl.steps") instanceof List<?> ll)
                    ? ll.stream().filter(x -> x instanceof String).map(Object::toString).toList()
                    : List.of();
            String modeName = validated.mode() != null ? validated.mode().name() : "UNDIRECTED";
            behaviourLogger.deliberationOutcome(updated.cycleName(), modeName, proposedSignals, agentSteps);
        }

        return new DeliberationDecision(updated, validated);
    }

    private CycleContextData applyGoalExhaustionSignal(CycleContextData context) {
        // T1-AK: only detect subgoal exhaustion when a real (non-root) subgoal is active.
        // currentSubGoalId can equal currentGoalId when no decomposition has happened;
        // in that case the system is at root level and exhaustion is not recoverable here.
        String subGoalId = context.currentSubGoalId();
        String rootGoalId = context.currentGoalId();
        if (subGoalId == null || subGoalId.isBlank() || subGoalId.equals(rootGoalId)) {
            return context;
        }

        ExhaustionReport report = goalExhaustionDetector.detect(context);

        Boolean isExhausted = null;
        Double confidence = null;
        String rationale = null;
        Map<String, Object> evidence = null;
        if (report != null) {
            isExhausted = report.exhausted();
            confidence = report.confidence();
            rationale = report.rationale();
            evidence = report.evidence();
        }

        log.debug("Exhaustion detect: exhausted={}, confidence={}, rationale={}, evidence={}", isExhausted, confidence, rationale, evidence);
        if (report == null || !report.exhausted()) {
            return context;
        }

        // If already strong, do not keep “pumping” it (tick/prune will decay it anyway)
        if (context.hasStrongSignal(BasicSignals.SUBGOAL_EXHAUSTED, 0.50)) {
            return context;
        }

        double strength = Math.max(0.55, Math.min(1.0, report.confidence()));
        Signal exhausted = Signal.of(BasicSignals.SUBGOAL_EXHAUSTED, strength, 0.05, "GoalExhaustionDetector");
        return context.withSignal(exhausted);
    }

    private DeliberationResult enrichWithExhaustionEvidence(CycleContextData ctx, DeliberationResult proposed) {
        if (proposed == null) return null;

        // only add evidence if the signal is present (means detector triggered this cycle)
        if (!ctx.hasSignal(BasicSignals.SUBGOAL_EXHAUSTED)) {
            return proposed;
        }

        Map<String, Object> meta = proposed.meta() != null ? new HashMap<>(proposed.meta()) : new HashMap<>();
        meta.put("goalExhaustedSignal", true);

        // If the detector stores evidence elsewhere later, this keeps the pipe open.
        // (For now we can’t pull the ExhaustionReport back out without re-running detect)
        meta.putIfAbsent("goalExhaustion", "detected");

        return new DeliberationResult(
                proposed.mode(),
                proposed.confidence(),
                proposed.rationale(),
                proposed.considerations(),
                proposed.evidence(),
                meta,
                proposed.proposedSignals(),
                proposed.metaSteps()
        );
    }

    private DeliberationResult enrichWithMetaDeliberation(CycleContextData ctx, DeliberationResult proposed) {
        if (proposed == null) return null;

        var plan = metaDeliberationAgent.decide(ctx, proposed.mode());
        if (plan == null) return proposed;

        Map<String, Object> meta = proposed.meta() != null ? new HashMap<>(proposed.meta()) : new HashMap<>();
        meta.put("metaControl.stop", plan.stop());
        meta.put("metaControl.mode", plan.mode().name());
        meta.put("metaControl.rationale", plan.rationale());

        // T4-AI-1: the string form (index|Need|TargetRole|Request|StopGuard) is TELEMETRY ONLY
        // now — graph registrar + behaviour log render it; the engine consumes the typed
        // metaSteps list on the result, so the stopIf/stopCycleIf distinction survives.
        List<com.walpro.autobuilder.protocol.meta.MetaActionStep> typedSteps =
                plan.steps() != null
                        ? plan.steps().stream()
                                .sorted((a, b) -> Integer.compare(a.index(), b.index()))
                                .toList()
                        : List.of();
        meta.put("metaControl.steps", typedSteps.stream()
                .map(s -> s.index() + "|" + s.need() + "|" + s.targetRole() + "|" + s.request() + "|" + toEngineStopGuard(s))
                .toList());

        return new DeliberationResult(
                proposed.mode(),
                proposed.confidence(),
                proposed.rationale(),
                proposed.considerations(),
                proposed.evidence(),
                meta,
                proposed.proposedSignals(),
                typedSteps
        );
    }

    private static String toEngineStopGuard(com.walpro.autobuilder.protocol.meta.MetaActionStep s) {
        String stopIf = s.stopIf() == null ? "" : s.stopIf().trim();
        String stopCycleIf = s.stopCycleIf() == null ? "" : s.stopCycleIf().trim();

        boolean hasStopCycle = !stopCycleIf.isBlank() && !"never".equalsIgnoreCase(stopCycleIf);
        boolean hasStop = !stopIf.isBlank() && !"never".equalsIgnoreCase(stopIf);

        if (hasStopCycle) return "stop_cycle_if " + stopCycleIf;
        if (hasStop) return "stop_if " + stopIf;
        return "Never";
    }

}
