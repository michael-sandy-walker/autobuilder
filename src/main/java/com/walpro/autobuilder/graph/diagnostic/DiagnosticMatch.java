package com.walpro.autobuilder.graph.diagnostic;

import java.util.Map;

public record DiagnosticMatch(
        String diagnosticId,
        String name,
        String severity,     // e.g. "LOW|MEDIUM|HIGH"
        double score,        // final computed score [0..1] ideally
        String observation,  // short human/LLM-readable observation line
        Map<String, Object> meta
) {}