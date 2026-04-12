// NEW FILE: src/main/java/com/walpro/autobuilder/protocol/analysis/AnalysisResponseParser.java
package com.walpro.autobuilder.protocol.analysis;

import com.walpro.autobuilder.agent.analysis.model.AnalysisOutcome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AnalysisResponseParser {

    public AnalysisOutcome parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return AnalysisOutcome.empty("Empty response.");
        }

        List<AnalysisOutcome.Finding> findings = new ArrayList<>();
        List<String> hypotheses = new ArrayList<>();
        List<String> proposed = new ArrayList<>();
        Map<String, List<String>> criteriaMap = new LinkedHashMap<>();
        String message = "";
        String goalKind = null;
        boolean possiblyComplete = false;
        boolean injectionThrottle = false;

        String section = "";
        boolean inCriteriaBlock = false;
        String lastProposed = null;

        for (String line : raw.split("\\R")) {
            String t = line.trim();

            // Top-level section headers
            if (t.equalsIgnoreCase("Findings:"))                { section = "findings";   inCriteriaBlock = false; continue; }
            if (t.equalsIgnoreCase("Hypotheses:"))              { section = "hypotheses"; inCriteriaBlock = false; continue; }
            if (t.equalsIgnoreCase("ProposedExecutableSubGoals:")) { section = "proposed"; inCriteriaBlock = false; continue; }
            if (t.startsWith("Message:"))         { message = t.substring("Message:".length()).trim();         section = ""; inCriteriaBlock = false; continue; }
            if (t.startsWith("GoalKind:"))        { goalKind = t.substring("GoalKind:".length()).trim();       section = ""; continue; }
            if (t.startsWith("PossiblyComplete:")) {
                possiblyComplete = t.substring("PossiblyComplete:".length()).trim().equalsIgnoreCase("true");
                section = "";
                continue;
            }
            if (t.startsWith("#InjectionThrottle:")) {
                injectionThrottle = true;
                continue;
            }

            // Optional Criteria: sub-block under ProposedExecutableSubGoals
            if (section.equals("proposed") && t.equalsIgnoreCase("Criteria:")) {
                inCriteriaBlock = (lastProposed != null);
                continue;
            }
            if (inCriteriaBlock && t.startsWith("*")) {
                String criterion = t.substring(1).trim();
                if (!criterion.isEmpty()) {
                    criteriaMap.computeIfAbsent(lastProposed, k -> new ArrayList<>()).add(criterion);
                }
                continue;
            }

            // A new goal line (-) exits the criteria block
            if (section.equals("proposed") && t.startsWith("-")) {
                inCriteriaBlock = false;
                String item = t.substring(1).trim();
                if (!item.isEmpty()) {
                    proposed.add(item);
                    lastProposed = item;
                }
                continue;
            }

            // Findings and hypotheses dash-lines
            if (!t.startsWith("-")) continue;
            String item = t.substring(1).trim();
            switch (section) {
                case "findings"   -> findings.add(parseFinding(item));
                case "hypotheses" -> hypotheses.add(item);
            }
        }

        if (message.isBlank()) message = "Analysis complete.";
        return new AnalysisOutcome(findings, hypotheses, proposed,
                criteriaMap.isEmpty() ? null : criteriaMap,
                message, goalKind, possiblyComplete, injectionThrottle);
    }

    private AnalysisOutcome.Finding parseFinding(String item) {
        // expected: [SEVERITY] type :: evidence :: path
        String severity = "INFO";
        String rest = item;

        if (rest.startsWith("[") && rest.contains("]")) {
            int end = rest.indexOf(']');
            severity = rest.substring(1, end).trim();
            rest = rest.substring(end + 1).trim();
        }

        String type = rest;
        String evidence = "";
        String path = "(n/a)";

        String[] parts = rest.split("\\s*::\\s*");
        if (parts.length >= 1) type = parts[0].trim();
        if (parts.length >= 2) evidence = parts[1].trim();
        if (parts.length >= 3) path = parts[2].trim();

        return new AnalysisOutcome.Finding(severity, type, evidence, path);
    }
}
