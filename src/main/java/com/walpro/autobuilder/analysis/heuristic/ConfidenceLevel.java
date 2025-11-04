package com.walpro.autobuilder.analysis.heuristic;

public enum ConfidenceLevel {
    NONE(0.0),
    LOW(0.25),
    MEDIUM(0.5),
    HIGH(0.75),
    CRITICAL(0.9);

    private final double threshold;

    ConfidenceLevel(double threshold) {
        this.threshold = threshold;
    }

    public double threshold() {
        return threshold;
    }

    public static ConfidenceLevel fromScore(double score) {
        if (score >= CRITICAL.threshold) return CRITICAL;
        if (score >= HIGH.threshold) return HIGH;
        if (score >= MEDIUM.threshold) return MEDIUM;
        if (score >= LOW.threshold) return LOW;
        return NONE;
    }
}