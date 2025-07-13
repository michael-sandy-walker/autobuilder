package com.walpro.autobuilder.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "autobuilder.heuristic")
@Getter
@Setter
public class HeuristicConfigProperties {
    /**
     * Name of the score strategy bean, e.g. "weightedAverageScoreAdjustmentStrategy"
     */
    private String scoreStrategy = "weightedAverageScoreAdjustmentStrategy";
}
