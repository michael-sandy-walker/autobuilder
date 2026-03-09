package com.walpro.autobuilder.graph.diagnostic;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class DiagnosticScanFormatter {

    private DiagnosticScanFormatter() {}

    public static String toLLMBlock(DiagnosticScanResult r, List<DiagnosticScanResult> history) {
        if (r == null || r.matches() == null || r.matches().isEmpty()) {
            return """
                   --- DIAGNOSTIC SCAN ---
                   (no matches)
                   """.trim();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("--- DIAGNOSTIC SCAN ---\n");

        List<DiagnosticMatch> ms = r.matches();
        for (DiagnosticMatch m : ms) {
            if (m == null) continue;
            sb.append("- ")
                    .append(nullSafe(m.name()))
                    .append(" [").append(nullSafe(m.severity())).append("]")
                    .append(" score=").append(String.format("%.3f", m.score()))
                    .append(" :: ")
                    .append(nullSafe(m.evidence()))
                    .append("\n");
        }

        if (history != null && !history.isEmpty()) {
            Map<String, Long> recurrenceCounts = ms.stream()
                    .filter(m -> m != null && m.diagnosticId() != null)
                    .collect(Collectors.toMap(
                            DiagnosticMatch::diagnosticId,
                            m -> history.stream()
                                    .filter(h -> h != null && h.matches() != null)
                                    .filter(h -> h.matches().stream()
                                            .anyMatch(hm -> hm != null && m.diagnosticId().equals(hm.diagnosticId())))
                                    .count(),
                            (a, b) -> a
                    ));

            boolean anyRecurring = recurrenceCounts.values().stream().anyMatch(c -> c > 0);
            if (anyRecurring) {
                sb.append("Recurrence (of last ").append(history.size()).append(" scans):\n");
                for (DiagnosticMatch m : ms) {
                    if (m == null) continue;
                    long count = recurrenceCounts.getOrDefault(m.diagnosticId(), 0L);
                    sb.append("  ").append(nullSafe(m.name()))
                            .append(": appeared in ").append(count).append("/").append(history.size())
                            .append(" prior scans\n");
                }
            }
        }

        return sb.toString().trim();
    }

    private static String nullSafe(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}