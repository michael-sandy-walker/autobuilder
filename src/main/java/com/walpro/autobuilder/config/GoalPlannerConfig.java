package com.walpro.autobuilder.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "autobuilder.goal")
public class GoalPlannerConfig {
    private boolean migrateToGraph = false;
    private boolean syncFromGraph = false;
}
