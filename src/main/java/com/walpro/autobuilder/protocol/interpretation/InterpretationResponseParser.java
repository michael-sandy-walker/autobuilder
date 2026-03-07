package com.walpro.autobuilder.protocol.interpretation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.walpro.autobuilder.agent.interpretation.model.InterpretationOutcome;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.Signal;

public class InterpretationResponseParser {

    public InterpretationOutcome parse(String content) {
        if (content == null || content.isBlank()) {
            return InterpretationOutcome.noop("Empty LLM response.");
        }

        String status = parseField(content, "status").orElse("APPLIED");
        String summary = parseField(content, "summary").orElse("(no summary)");
        String directives = parseField(content, "directives").orElse("");
        List<Signal> signals = parseSignals(content);

        return new InterpretationOutcome(status, summary, directives, signals);
    }

    private java.util.Optional<String> parseField(String content, String key) {
        return content.lines()
                .filter(l -> l.trim().toLowerCase(Locale.ROOT).startsWith(key + ":"))
                .map(l -> l.trim().substring((key + ":").length()).trim())
                .filter(v -> !v.isBlank())
                .findFirst();
    }

    private List<Signal> parseSignals(String content) {
        List<Signal> signals = new ArrayList<>();
        content.lines()
                .map(String::trim)
                .filter(l -> l.toLowerCase(Locale.ROOT).startsWith("signal:"))
                .map(l -> l.substring("signal:".length()).trim().toUpperCase(Locale.ROOT))
                .forEach(name -> {
                    if (name.equals("REPLAN_REQUESTED")) {
                        signals.add(Signal.of(BasicSignals.REPLAN_REQUESTED, 0.8, 0.05, "interpretation"));
                    } else if (name.equals("LOW_CONFIDENCE")) {
                        signals.add(Signal.of(BasicSignals.LOW_CONFIDENCE, 0.7, 0.10, "interpretation"));
                    } else if (name.equals("DIVERGENCE_REQUEST")) {
                        signals.add(Signal.of(BasicSignals.DIVERGENCE_REQUEST, 0.7, 0.10, "interpretation"));
                    }
                });
        return signals;
    }
}
