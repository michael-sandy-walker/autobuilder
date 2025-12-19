package com.walpro.autobuilder.core.deliberation;

import java.util.HashMap;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.walpro.autobuilder.analysis.exhaustion.ExhaustionReport;
import com.walpro.autobuilder.analysis.exhaustion.GoalExhaustionDetector;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.graph.deliberation.DeliberationActionRegistrar;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@Slf4j
public class DeliberationService {

    private final GoalExhaustionDetector goalExhaustionDetector;
    private final DeliberationProposer proposer;
    private final DeliberationValidator validator;
    private final DeliberationActionRegistrar registrar;

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

        // Step D: validate + record
        DeliberationResult validated = validator.validate(withEvidence);
        registrar.registerDeliberation(DELIBERATION_AGENT_ID, updated, validated);

        return new DeliberationDecision(updated, validated);
    }

    private CycleContextData applyGoalExhaustionSignal(CycleContextData context) {
        ExhaustionReport report = goalExhaustionDetector.detect(context);

        log.debug("Exhaustion detect: exhausted={}, confidence={}, rationale={}, evinded={}", report.exhausted(), report.confidence(), report.rationale(), report.evidence());

        if (report == null || !report.exhausted()) {
            return context;
        }

        // If already strong, do not keep “pumping” it (your tick/prune will decay it anyway)
        if (context.hasStrongSignal(BasicSignals.GOAL_EXHAUSTED, 0.50)) {
            return context;
        }

        double strength = Math.max(0.55, Math.min(1.0, report.confidence()));
        Signal exhausted = Signal.of(BasicSignals.GOAL_EXHAUSTED, strength, 0.05, "GoalExhaustionDetector");
        return context.withSignal(exhausted);
    }

    private DeliberationResult enrichWithExhaustionEvidence(CycleContextData ctx, DeliberationResult proposed) {
        if (proposed == null) return null;

        // only add evidence if the signal is present (means detector triggered this cycle)
        if (!ctx.hasSignal(BasicSignals.GOAL_EXHAUSTED)) {
            return proposed;
        }

        Map<String, Object> meta = proposed.meta() != null ? new HashMap<>(proposed.meta()) : new HashMap<>();
        meta.put("goalExhaustedSignal", true);

        // If your detector stores evidence elsewhere later, this keeps the pipe open.
        // (For now we can’t pull the ExhaustionReport back out without re-running detect)
        meta.putIfAbsent("goalExhaustion", "detected");

        return new DeliberationResult(
                proposed.mode(),
                proposed.confidence(),
                proposed.rationale(),
                proposed.considerations(),
                proposed.evidence(),
                meta,
                proposed.proposedSignals()
        );
    }
}
