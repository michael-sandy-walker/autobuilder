package com.walpro.autobuilder.tools.diff;

import java.util.List;

public class ClassChangeResult {

    private final List<ChangedMethod> changes;

    public ClassChangeResult(List<ChangedMethod> changes) {
        this.changes = changes;
    }

    public List<ChangedMethod> getChanges() {
        return changes;
    }

    public List<ChangedMethod> getAdded() {
        return changes.stream().filter(c -> c.type() == ChangedMethod.ChangeType.ADDED).toList();
    }

    public List<ChangedMethod> getRemoved() {
        return changes.stream().filter(c -> c.type() == ChangedMethod.ChangeType.REMOVED).toList();
    }

    public List<ChangedMethod> getModified() {
        return changes.stream().filter(c -> c.type() == ChangedMethod.ChangeType.MODIFIED).toList();
    }

    @Override
    public String toString() {
        return "Changes:\n" + changes.stream()
                .map(c -> " - [%s] %s".formatted(c.type(), c.name()))
                .reduce("", (a, b) -> a + b + "\n");
    }
}
