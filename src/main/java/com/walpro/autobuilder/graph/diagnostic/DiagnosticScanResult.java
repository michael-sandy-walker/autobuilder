package com.walpro.autobuilder.graph.diagnostic;

import java.util.List;

public record DiagnosticScanResult(
        List<DiagnosticMatch> matches
) {
    public static DiagnosticScanResult empty() {
        return new DiagnosticScanResult(List.of());
    }
}