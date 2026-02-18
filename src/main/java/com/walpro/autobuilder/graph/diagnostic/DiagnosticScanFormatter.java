package com.walpro.autobuilder.graph.diagnostic;

import java.util.List;

public final class DiagnosticScanFormatter {

    private DiagnosticScanFormatter() {}

    public static String toLLMBlock(DiagnosticScanResult r) {
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

        return sb.toString().trim();
    }

    private static String nullSafe(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}