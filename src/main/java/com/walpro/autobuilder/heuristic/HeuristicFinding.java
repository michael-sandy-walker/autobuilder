package com.walpro.autobuilder.heuristic;

public class HeuristicFinding {

    public enum Severity { INFO, WARNING, CRITICAL }

    private final String description;
    private final Severity severity;
    private final String type;  // optional classification (e.g. "deletion", "rename")
    private final String path;  // affected path (optional)

    public HeuristicFinding(String description, Severity severity) {
        this(description, severity, null, null);
    }

    public HeuristicFinding(String description, Severity severity, String type, String path) {
        this.description = description;
        this.severity = severity;
        this.type = type;
        this.path = path;
    }

    public String description() { return description; }
    public Severity severity() { return severity; }
    public String type() { return type; }
    public String path() { return path; }
}