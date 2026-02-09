// NEW FILE: src/main/java/com/walpro/autobuilder/protocol/analysis/AnalysisResponseParser.java
package com.walpro.autobuilder.protocol.analysis;

import com.walpro.autobuilder.agent.analysis.model.AnalysisOutcome;

import java.util.ArrayList;
import java.util.List;

public final class AnalysisResponseParser {

    public AnalysisOutcome parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return AnalysisOutcome.empty("Empty response.");
        }

        List<AnalysisOutcome.Finding> findings = new ArrayList<>();
        List<String> hypotheses = new ArrayList<>();
        List<String> proposed = new ArrayList<>();
        String message = "";

        String section = "";
        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (t.equalsIgnoreCase("Findings:")) { section = "findings"; continue; }
            if (t.equalsIgnoreCase("Hypotheses:")) { section = "hypotheses"; continue; }
            if (t.equalsIgnoreCase("ProposedExecutableSubGoals:")) { section = "proposed"; continue; }
            if (t.startsWith("Message:")) { message = t.substring("Message:".length()).trim(); section = ""; continue; }

            if (!t.startsWith("-")) continue;
            String item = t.substring(1).trim();

            switch (section) {
                case "findings" -> findings.add(parseFinding(item));
                case "hypotheses" -> hypotheses.add(item);
                case "proposed" -> proposed.add(item);
            }
        }

        if (message.isBlank()) message = "Analysis complete.";
        return new AnalysisOutcome(findings, hypotheses, proposed, message);
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