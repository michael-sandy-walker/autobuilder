package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.SignalType;

import java.util.Set;

public interface ExecutionAgent {

    boolean canExecute(CycleContextData context);

    CycleContextData execute(CycleContextData context);

    /** Human-readable id for logs/debug (e.g. "evaluation", "planner"). */
    String name();

    /** Role identity used by deliberation + signal identity (e.g. "evaluation"). */
    String role();

    /** Signals that influence fitness ordering (besides ROLE_* which is always included). */
    Set<SignalType> getSupportedSignals();

    /**
     * Optional priority contribution for ordering decisions.
     * Higher means more likely to run (engine treats it as additive).
     */
    default double priority(CycleContextData context) {
        return switch (role() == null ? "" : role().toLowerCase()) {
            case "planner" -> 1.0;
            case "evaluation" -> 2.0;
            case "evolution" -> 3.0;
            case "merge" -> 4.0;
            default -> 0.0;
        };
    }
}
