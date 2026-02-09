package com.walpro.autobuilder.core.engine;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.core.deliberation.DeliberationMode;

@Component
public class DeliberationExecutionGate {

    public Set<String> allowedRoles(DeliberationMode mode) {
        if (mode == null) return Set.of(); // safest default

        return switch (mode) {
            case PAUSE -> Set.of();
            case REPLAN -> Set.of("planner", "analysis");
            case EVOLVE -> Set.of("evolution");
            case EVALUATE -> Set.of("evaluation");
            case MERGE -> Set.of("merge");
            case NOOP -> Set.of("planner", "evolution", "evaluation", "merge", "analysis"); // normal operation
            case BOOTSTRAP -> Set.of("planner", "evolution", "evaluation", "analysis");
        };
    }

    public boolean allows(DeliberationMode mode, String role) {
        if (role == null) return false;
        boolean result = allowedRoles(mode).contains(role.toLowerCase());
        return result;
    }
}
