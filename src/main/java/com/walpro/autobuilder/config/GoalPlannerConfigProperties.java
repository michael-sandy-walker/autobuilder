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
    /** Minimum fraction of direct children that must be COMPLETED (not GIVEN_UP) for the root to be
     *  auto-completed. If the ratio falls at or below this threshold the root is marked GIVEN_UP and
     *  GOAL_EXHAUSTED is raised.  Default 0.5 = strict majority required. */
    private double minCompletionRatio = 0.5;
}
