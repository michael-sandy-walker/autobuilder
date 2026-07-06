package com.walpro.autobuilder.protocol.analysis;

import com.walpro.autobuilder.agent.analysis.model.AnalysisOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import java.util.ArrayList;
import java.util.List;

/**
 * T4-BX: renders the {@code --- ANALYSIS OBSERVATIONS ---} block from the analysis agent's authored
 * output ({@code analysisOutcome}) so the evolution and planner agents learn what analysis observed
 * is blocking — e.g. "a required class does not exist yet" — instead of the observation staying a
 * bare fact that only steers control flow. The message and findings are the analysis agent's own
 * words, shown to the LLM verbatim (nothing here parses them).
 *
 * <p>Only WARNING/CRITICAL findings are surfaced (INFO is noise, matching the problem detector's
 * own filter). Returns {@code null} when there is nothing meaningful to relay.
 */
public final class AnalysisObservationsPromptBlock {

    private AnalysisObservationsPromptBlock() {}

    public static String render(CycleContextData ctx) {
        if (ctx == null) return null;
        AnalysisOutcome outcome = ctx.analysisOutcome();
        if (outcome == null) return null;

        List<String> lines = new ArrayList<>();
        if (isMeaningful(outcome.message())) {
            lines.add(outcome.message().trim());
        }
        if (outcome.findings() != null) {
            for (AnalysisOutcome.Finding f : outcome.findings()) {
                if (f == null || !isMeaningful(f.observation())) continue;
                if (!isBlocking(f.severity())) continue;
                String line = f.observation().trim();
                if (isMeaningful(f.path())) {
                    line = line + " (" + f.path().trim() + ")";
                }
                if (!lines.contains(line)) {
                    lines.add(line);
                }
            }
        }
        if (lines.isEmpty()) return null;

        StringBuilder sb = new StringBuilder("--- ANALYSIS OBSERVATIONS ---\n");
        sb.append("The analysis step observed the following about the current code — treat these as ")
          .append("what is blocking progress:\n");
        for (String line : lines) {
            sb.append("  - ").append(line).append("\n");
        }
        return sb.toString();
    }

    private static boolean isMeaningful(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean isBlocking(String severity) {
        if (severity == null) return false;
        String s = severity.trim();
        return s.equalsIgnoreCase("WARNING") || s.equalsIgnoreCase("CRITICAL");
    }
}
