package com.walpro.autobuilder.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "autobuilder.goal")
public class GoalPlannerConfigProperties {
    /** Whether the goal state in meta/GoalStack.json is pushed into the graph on startup. */
    private boolean migrateToGraph = false;
    /** Whether the goal state is loaded from the graph on startup. */
    private boolean syncFromGraph = false;





    /**
     * T2-EI (count-based admission queue): maximum number of active subgoal LEAVES that may be
     * surfaced as the workable set at any time. Excess active leaves are HELD — they keep their
     * ACTIVE status (so they still block premature root completion) but are not selectable as the
     * current subgoal, so no agent works them and their files never reach the shared output/ disk.
     * As admitted leaves terminate, held ones are surfaced. This deterministically bounds the
     * working set the model must keep coherent, independent of how many subgoals the planner (or
     * the coverage gate) proposes in one wave — the lever the 3-run experiment pinned (run-B's
     * small coherent set delivered by construction, not by luck). 0 = disabled. The default, 6, is
     * about one resource's worth of layers, reproduces run-B behaviour, and is what every measured
     * run used. See [[project-t2ei-design-note]] (P1/P2).
     */
    private int admissionCap = 6;
}
