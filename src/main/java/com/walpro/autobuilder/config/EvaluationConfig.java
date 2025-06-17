package com.walpro.autobuilder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

@Configuration
@Getter
@Setter
@ConfigurationProperties(prefix = "autobuilder.evaluation")
public class EvaluationConfig {

    private double heuristicThreshold = 0.4;
    private double maxScoreDrop = 0.1; // Max allowed drop compared to last accepted score
    private int minChangedFiles = 1;

    // Add more thresholds or toggles here as needed
}

