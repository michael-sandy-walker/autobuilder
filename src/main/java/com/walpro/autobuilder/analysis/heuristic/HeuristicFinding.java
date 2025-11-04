package com.walpro.autobuilder.analysis.heuristic;

public record HeuristicFinding(String description, Severity severity, String type, String path) {
    public enum Severity { INFO, WARNING, CRITICAL }
}