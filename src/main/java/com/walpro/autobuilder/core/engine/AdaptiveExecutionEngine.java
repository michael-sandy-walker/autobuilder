package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.coordinator.AgentCoordinator;
import com.walpro.autobuilder.core.lifecycle.Signal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;

@Slf4j
@RequiredArgsConstructor
public class AdaptiveExecutionEngine {

    private final List<AgentCoordinator> coordinators;
    private final CoordinationStrategy coordinationStrategy;

    public CycleContextData runCycle(CycleContextData context) {
        boolean progressed;

        do {
            progressed = false;
            List<AgentCoordinator> order = coordinationStrategy.determineExecutionOrder(coordinators, context);

            for (AgentCoordinator c : order) {
                if (c.canExecute(context)) {
                    log.info("🔁 Executing coordinator: {} | active signals: {} | bias={}",
                            c.role(),
                            context.signals().keySet(),
                            context.evaluationBias());
                    CycleContextData before = context;
                    context = c.execute(context);
//                    progressed = !context.equals(before);
                    progressed = hasProgressed(context, before);
                    if (progressed) break; // re-evaluate order dynamically
                } else {
                    log.debug("Skipping coordinator: {}", c.role());
                }
            }

            context = coordinationStrategy.adaptAfterCycle(context);

            if (!progressed) {
                log.info("⚖️ No coordinator progressed. Emitting NEED_PLAN.");
                context = context.withSignal(Signal.of("NEED_PLAN", 1.0, 0.2, "adaptive-engine"));
            }

        } while (progressed);

        return context;
    }

    boolean hasProgressed(CycleContextData before, CycleContextData after) {
//        return !Objects.equals(before.projectDiff(), after.projectDiff())
//                || before.score() != after.score();
        return !after.signals().equals(before.signals());
    }

}