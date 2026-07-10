package com.walpro.autobuilder.core.deliberation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        // Step D: validate + record
        DeliberationResult validated = validator.validate(withEvidence);
        registrar.registerDeliberation(DELIBERATION_AGENT_ID, updated, validated);

        if (behaviourLogger != null && validated != null) {
            // T4-BZ-5: the event keeps its name and NAME=value shape (I9); entries come from
            // the typed proposal carriers (requests as kind=urgency, windows as ROLE_*=boost).
            List<String> proposedSignals = new java.util.ArrayList<>();
            if (validated.proposedRequests() != null) {
                validated.proposedRequests().stream().filter(java.util.Objects::nonNull)
                        .map(r -> r.kind().name() + "=" + r.urgency()).forEach(proposedSignals::add);
            }
            if (validated.proposedWindows() != null) {
                validated.proposedWindows().stream().filter(java.util.Objects::nonNull)
                        .map(w -> com.walpro.autobuilder.core.lifecycle.BasicSignals.roleSignalFor(w.role()).name()
                                + "=" + w.boost())
                        .forEach(proposedSignals::add);
            }
            String modeName = validated.mode() != null ? validated.mode().name() : "UNDIRECTED";
            behaviourLogger.deliberationOutcome(updated.cycleName(), modeName, proposedSignals);
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
        if (context.hasStrongRequest(BasicSignals.SUBGOAL_EXHAUSTED, 0.50)) {
            return context;
        }

        double strength = Math.max(0.55, Math.min(1.0, report.confidence()));
        return context.issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(
                BasicSignals.SUBGOAL_EXHAUSTED, strength, "GoalExhaustionDetector",
                com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(strength, 0.05)));
    }

    private DeliberationResult enrichWithExhaustionEvidence(CycleContextData ctx, DeliberationResult proposed) {
        if (proposed == null) return null;

        // only add evidence if the signal is present (means detector triggered this cycle)
        if (!ctx.hasOpenRequest(BasicSignals.SUBGOAL_EXHAUSTED)) {
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
                proposed.proposedRequests(),
                proposed.proposedWindows()
        );
    }

}
