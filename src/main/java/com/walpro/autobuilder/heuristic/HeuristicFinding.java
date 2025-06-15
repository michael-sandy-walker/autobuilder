package com.walpro.autobuilder.heuristic;

public record HeuristicFinding(String description, Severity severity, String type, String path) {
    public enum Severity { INFO, WARNING, CRITICAL }
}