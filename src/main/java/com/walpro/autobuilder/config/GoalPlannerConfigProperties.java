package com.walpro.autobuilder.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "autobuilder.goal")
public class GoalPlannerConfigProperties {
    private boolean migrateToGraph = false;
    private boolean syncFromGraph = false;

    /**
     * T2-EG: the completion-edge coverage gate (leading "is this gap already implemented?" question
     * over a flat blob — the framing that false-positived 18/18 on the 2026-06-16 hollow artifact).
     * Demoted to off-by-default: T4-BP-3's inverse, per-criterion corroboration is the single home
     * for "is the work done?", and disabling the gate cuts an LLM call at closure. Flip to {@code true}
     * to re-arm the incumbent if T4-BP-3 ever underperforms in the field.
     */
    private boolean completionCoverageGateEnabled = false;
}
