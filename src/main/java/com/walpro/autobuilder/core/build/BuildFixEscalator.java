package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.DerivedFacts;
import com.walpro.autobuilder.core.lifecycle.Signal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * T2-CV: signal/graph-driven build-fix escalation.
 *
 * <p>Replaces the implicit "abstain after N attempts" pattern with two observable stages,
 * each emitting signals the planner / analysis machinery already consumes:
 *
 * <ul>
 *   <li><b>Stage 1 — replan.</b> Fires when the build has stayed broken across multiple cycles
 *   without progress: emits {@link BasicSignals#REPLAN_REQUESTED} with source
 *   {@code "buildFixStuck"} so the planner gets a turn (it sees the BUILD STATE block, T2-CU,
 *   and the LAST CYCLE ROLLED BACK block, T1-DX/T2-CY, to inform restructuring).</li>
 *
 *   <li><b>Stage 2 — analyse.</b> Fires when stage 1 has already been tried (more attempts
 *   accumulated) and the build is still broken. Boosts {@link BasicSignals#ROLE_ANALYSIS} so the
 *   adaptive engine routes to analysis next — analysis is the diagnostic escape hatch (T2-DE
 *   keeps it eligible under BUILD_BROKEN; the higher fitness here pulls it ahead of competing
 *   role-signals).</li>
 * </ul>
 *
 * <p>Conditions read shared state on {@link CycleContextData} — no private counter inside the
 * escalator. {@code consecutiveBuildFixAttempts} (T1-DQ) measures "merges that tried to fix the
 * build". {@code REPETITION_DETECTED} (signal) measures "same evolution fingerprint reappearing".
 * Either crossing its threshold is enough to trigger.
 */
@Slf4j
@Component
public class BuildFixEscalator {

    /** Stage-1 threshold on {@code consecutiveBuildFixAttempts} before REPLAN_REQUESTED fires. */
    static final int STAGE_1_ATTEMPT_THRESHOLD = 2;

    /** Stage-2 threshold — must exceed stage 1 so the two stages are observable as distinct. */
    static final int STAGE_2_ATTEMPT_THRESHOLD = 4;

    /** Strength floor on {@code REPETITION_DETECTED} that alone is enough to fire stage 1. */
    static final double STAGE_1_REPETITION_THRESHOLD = 1.0;

    /**
     * Post-evaluation hook: inspects {@code ctx} and decides whether to inject escalation signals.
     * Returns a (possibly identical) context. Safe to call in any cycle — early-returns when
     * BUILD_BROKEN is not active.
     */
    public CycleContextData escalateIfStuck(CycleContextData ctx) {
        if (ctx == null) return ctx;
        if (!DerivedFacts.buildBroken(ctx)) return ctx;

        int attempts = ctx.consecutiveBuildFixAttempts();
        Signal repetition = ctx.getSignal(BasicSignals.REPETITION_DETECTED);
        double repetitionStrength = repetition != null ? repetition.strength() : 0.0;

        boolean stage2 = attempts >= STAGE_2_ATTEMPT_THRESHOLD;
        boolean stage1 = stage2
                || attempts >= STAGE_1_ATTEMPT_THRESHOLD
                || repetitionStrength >= STAGE_1_REPETITION_THRESHOLD;

        if (!stage1) return ctx;

        if (!ctx.hasSignal(BasicSignals.REPLAN_REQUESTED)) {
            log.info("T2-CV stage 1 — emitting REPLAN_REQUESTED (buildFixStuck): attempts={} repetition={}",
                    attempts, repetitionStrength);
            ctx = ctx.withSignal(Signal.of(BasicSignals.REPLAN_REQUESTED, 1.0, 0.10, "buildFixStuck"));
        }

        if (stage2) {
            log.info("T2-CV stage 2 — boosting ROLE_ANALYSIS (buildFixStuck): attempts={}", attempts);
            ctx = ctx.withSignal(Signal.of(BasicSignals.ROLE_ANALYSIS, 1.0, 0.05, "buildFixStuck-analyze"));
        }
        return ctx;
    }
}
