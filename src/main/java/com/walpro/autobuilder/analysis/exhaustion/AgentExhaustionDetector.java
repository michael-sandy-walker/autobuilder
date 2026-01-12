package com.walpro.autobuilder.analysis.exhaustion;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AgentExhaustionDetector {

    private final AgentGraphService agentGraphService;

    // TODO: Maybe make these config-driven later
    private static final int MAX_TOTAL_AGENTS_HARD = 14;
    private static final int MAX_TOTAL_AGENTS_SOFT = 10;
    private static final int MAX_ACTIVE_AGENTS_SOFT = 8;

    public ExhaustionReport detect(CycleContextData context) {
//        List<AgentEntity> all = agentGraphService.getAllAgents();
//        long inactive = all.stream().filter(a -> "inactive".equalsIgnoreCase(a.status())).count();

        long total = agentGraphService.countAllAgents();
        long active = agentGraphService.countActiveAgents();
        long activeUnassigned = agentGraphService.countActiveUnassignedAgents();

        Map<String, Object> evidence = new HashMap<>();
        evidence.put("totalAgents", total);
        evidence.put("activeAgents", active);
//        evidence.put("inactiveAgents", inactive);
        evidence.put("activeUnassignedAgents", activeUnassigned);

        // Hard stop: graph is already bloated
        if (total >= MAX_TOTAL_AGENTS_HARD) {
            return ExhaustionReport.of(
                    0.95,
                    "Hard agent limit reached (totalAgents=" + total + "). Spawning must stop; prune/merge agents instead.",
                    evidence
            );
        }

        double confidence = 0.0;

        // Soft pressures (accumulation + churn)
        if (total >= MAX_TOTAL_AGENTS_SOFT) confidence += 0.35;
        if (active >= MAX_ACTIVE_AGENTS_SOFT) confidence += 0.25;

        // “idle churn” is still useful, but not the only driver
        boolean tooManyIdleActive = activeUnassigned >= 2;
        boolean idleRatioHigh = active > 0 && ((double) activeUnassigned / (double) active) >= 0.30;
        if (tooManyIdleActive) confidence += 0.20;
        if (idleRatioHigh) confidence += 0.15;

        // Thrash bonus
        if (context != null && context.hasSignal(BasicSignals.STAGNATION)) {
            confidence = Math.min(1.0, confidence + 0.10);
            evidence.put("stagnationSignal", true);
        }

        if (confidence >= 0.60) {
            return ExhaustionReport.of(
                    Math.min(1.0, confidence),
                    "Agent pool appears saturated. Prefer reuse/pruning over spawning.",
                    evidence
            );
        }

        return ExhaustionReport.none("No agent exhaustion detected (confidence=" + confidence + ")");
    }
}
