package com.walpro.autobuilder.analysis.clarity;

import java.util.List;
import java.util.Map;

public class ClarityEvaluator {

    public record ClarityScore(double score, List<String> signals) {}

    /**
     * Compute how well the current symbolic structure aligns with clarity principles.
     *
     * @param structure Map of symbolic types (e.g., "questions", "symbols", "relationships", etc.) to node values
     * @return ClarityScore including a normalized score and explanatory signals
     */
    public ClarityScore evaluate(Map<String, List<String>> structure) {
        double score = 0.0;
        int count;
        ClaritySignals signals = new ClaritySignals();

        // Signal 1: Are there meaningful questions?
        if (hasNonEmpty(structure, "questions")) {
            score += 1.0;
            signals.add("✅ Questions present");
        } else {
            signals.add("⚠️ No guiding questions");
        }

        // Signal 2: Are paths connected to questions?
        if (isLinked(structure, "questions", "paths")) {
            score += 1.0;
            signals.add("✅ Questions connect to paths");
        } else {
            signals.add("⚠️ Questions not leading anywhere");
        }

        // Signal 3: Are there recursive relationships or movement?
        if (hasLoop(structure)) {
            score += 1.0;
            signals.add("✅ Recursive flow: movements → questions");
        } else {
            signals.add("⚠️ Missing recursive flow");
        }

        // Signal 4: Are symbols meaningfully connected to directions/relationships?
        if (isLinked(structure, "symbols", "relationships") || isLinked(structure, "symbols", "directions")) {
            score += 1.0;
            signals.add("✅ Symbols meaningfully connected");
        } else {
            signals.add("⚠️ Symbols unanchored");
        }

        count = 4;
        return new ClarityScore(score / count, signals.messages);
    }

    private boolean hasNonEmpty(Map<String, List<String>> map, String key) {
        return map.containsKey(key) && !map.get(key).isEmpty();
    }

    private boolean isLinked(Map<String, List<String>> map, String from, String to) {
        return hasNonEmpty(map, from) && hasNonEmpty(map, to); // naive check — can be improved
    }

    private boolean hasLoop(Map<String, List<String>> map) {
        return isLinked(map, "movements", "questions"); // stub for now — can core actual edge paths
    }

    private static class ClaritySignals {
        private final List<String> messages = new java.util.ArrayList<>();
        void add(String msg) { messages.add(msg); }
    }
}
