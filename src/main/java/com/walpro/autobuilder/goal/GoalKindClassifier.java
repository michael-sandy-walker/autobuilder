package com.walpro.autobuilder.goal;

import org.springframework.stereotype.Component;

import java.util.List;
// TODO: upgrade to LLM classification
@Component
public final class GoalKindClassifier {

    public enum GoalKind { EXECUTABLE, COGNITIVE, HYBRID }

    private static final List<String> COGNITIVE_MARKERS = List.of(
            "code review", "review", "analy", "diagnos", "investigat", "root cause",
            "differential analysis", "compare", "identify issues", "gather information",
            "understand", "assess", "evaluate why", "reason about", "inspect", "triage"
    );

    private static final List<String> EXECUTABLE_MARKERS = List.of(
            "add", "remove", "rename", "refactor", "implement", "fix", "update",
            "create", "delete", "introduce class", "add logging", "add test"
    );

    public GoalKind classify(String text) {
        if (text == null || text.isBlank()) return GoalKind.COGNITIVE;
        String s = text.toLowerCase();

        boolean cog = COGNITIVE_MARKERS.stream().anyMatch(s::contains);
        boolean exe = EXECUTABLE_MARKERS.stream().anyMatch(s::contains);

        if (cog && exe) return GoalKind.HYBRID;
        if (cog) return GoalKind.COGNITIVE;
        return GoalKind.EXECUTABLE;
    }
}