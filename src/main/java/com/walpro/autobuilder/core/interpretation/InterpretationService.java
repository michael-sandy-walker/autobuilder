package com.walpro.autobuilder.core.interpretation;

import java.util.List;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.interpretation.InterpretationAgent;
import com.walpro.autobuilder.agent.interpretation.model.InterpretationOutcome;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.graph.interpretation.InterpretationActionRegistrar;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class InterpretationService {

    private static final String INTERPRETATION_AGENT_ID = "interpretation";

    private final InterpretationAgent interpretationAgent;
    private final InterpretationActionRegistrar registrar;

    public CycleContextData interpret(CycleContextData context) {
        InterpretationOutcome outcome = interpretationAgent.interpret(context);

        // T2-CZ: scrub misleading "healthy progress" language when the previous cycle rolled back.
        // The interpreter's prompt now carries a ROLLBACK STATE block (see T2-CZ in
        // InterpretationProtocolContract), but defence-in-depth: if the LLM produces a
        // healthy-progress summary alongside a known rollback, rewrite it before it pollutes
        // downstream planner / evolver prompts.
        outcome = rewriteOnKnownRollback(context, outcome);

        registrar.registerInterpretation(INTERPRETATION_AGENT_ID, context, outcome);

        // Carry summary and directives into context so next-cycle agents can read them.
        context = context
                .withInterpretationSummary(outcome.summary())
                .withInterpretationDirectives(outcome.directives());

        // Apply proposed signals into runtime context (optional, but closes the loop immediately).
        // T1-AH: when the stagnation circuit breaker is active, suppress REPLAN_REQUESTED from
        // interpretation to break the stagnate→interpret→replan→stagnate feedback loop.
        List<Signal> proposedSignals = outcome.proposedSignals();
        if (proposedSignals != null && !proposedSignals.isEmpty()) {
            boolean breakerActive = context.stagnationCircuitBreakerActive();
            for (Signal s : proposedSignals) {
                if (breakerActive && BasicSignals.REPLAN_REQUESTED.equals(s.type())) {
                    log.warn("Stagnation circuit breaker active — suppressing REPLAN_REQUESTED from interpretation.");
                    continue;
                }
                context = context.withSignal(s);
            }
        }

        return context;
    }

    /**
     * T2-CZ: when {@code ctx.lastRollback()} is non-null, the previous cycle's mutation was
     * rolled back by the post-merge build gate (T1-DW) — the system has NOT made progress in
     * that cycle. The LLM sometimes still produces "healthy progress" / "accepted mutations"
     * language from earlier patterns. Detect that language and rewrite the summary to surface
     * the rollback explicitly. Idempotent: if the summary already references rollback, returns
     * the outcome unchanged.
     */
    static InterpretationOutcome rewriteOnKnownRollback(CycleContextData ctx, InterpretationOutcome outcome) {
        if (ctx == null || ctx.lastRollback() == null) return outcome;
        if (outcome == null) return outcome;
        String summary = outcome.summary() == null ? "" : outcome.summary();
        String lower = summary.toLowerCase();
        boolean alreadyAware = lower.contains("rolled back") || lower.contains("rollback");
        if (alreadyAware) return outcome;

        String misleading = null;
        if (lower.contains("healthy progress")) misleading = "healthy progress";
        else if (lower.contains("accepted mutations")) misleading = "accepted mutations";
        else if (lower.contains("steady progress")) misleading = "steady progress";
        else if (lower.contains("clear evolution direction")) misleading = "clear evolution direction";
        // Always rewrite when lastRollback is present, even if no flagged phrase — the LLM may
        // simply have omitted rollback from the summary.

        String paths = ctx.lastRollback().restoredPaths() == null
                ? "the prior mutation"
                : ctx.lastRollback().restoredPaths().toString();
        String rewritten;
        if (misleading != null) {
            rewritten = "Previous cycle rolled back " + paths + " — apparent progress was reverted by the build gate. "
                    + "[T2-CZ: scrubbed misleading '" + misleading + "' phrasing from LLM summary.]";
            log.warn("T2-CZ: rewrote interpretation summary — LLM described rolled-back cycle as '{}'", misleading);
        } else {
            rewritten = "Previous cycle rolled back " + paths + " — surfacing rollback explicitly. "
                    + "Original summary: " + summary;
            log.debug("T2-CZ: prepended rollback note to interpretation summary");
        }

        return new InterpretationOutcome(outcome.status(), rewritten, outcome.directives(), outcome.proposedSignals());
    }
}
