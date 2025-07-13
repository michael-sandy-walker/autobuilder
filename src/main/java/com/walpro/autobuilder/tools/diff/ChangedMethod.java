package com.walpro.autobuilder.tools.diff;

public record ChangedMethod(String name, ChangeType type) {
    public enum ChangeType {
        ADDED,
        REMOVED,
        MODIFIED
    }
}
