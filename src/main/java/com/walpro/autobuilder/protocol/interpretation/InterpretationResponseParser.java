package com.walpro.autobuilder.protocol.interpretation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.walpro.autobuilder.agent.interpretation.model.InterpretationOutcome;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.ChannelRequest;

public class InterpretationResponseParser {

    public InterpretationOutcome parse(String content) {
        if (content == null || content.isBlank()) {
            return InterpretationOutcome.noop("Empty LLM response.");
        }

        String status = parseField(content, "status").orElse("APPLIED");
        String summary = parseField(content, "summary").orElse("(no summary)");
        String directives = parseField(content, "directives").orElse("");
        List<ChannelRequest> requests = parseRequests(content);

        return new InterpretationOutcome(status, summary, directives, requests);
    }

    private java.util.Optional<String> parseField(String content, String key) {
        return content.lines()
                .filter(l -> l.trim().toLowerCase(Locale.ROOT).startsWith(key + ":"))
                .map(l -> l.trim().substring((key + ":").length()).trim())
                .filter(v -> !v.isBlank())
                .findFirst();
    }

    /**
     * T4-BZ-3: the LLM's Signal: lines enqueue typed requests. Urgency/horizons are 1:1 with
     * the strengths/decay-implied lifetimes the stored signals had (Q-c migration pin).
     */
    private List<ChannelRequest> parseRequests(String content) {
        List<ChannelRequest> requests = new ArrayList<>();
        content.lines()
                .map(String::trim)
                .filter(l -> l.toLowerCase(Locale.ROOT).startsWith("signal:"))
                .map(l -> l.substring("signal:".length()).trim().toUpperCase(Locale.ROOT))
                .forEach(name -> {
                    if (name.equals("REPLAN_REQUESTED")) {
                        requests.add(ChannelRequest.of(BasicSignals.REPLAN_REQUESTED, 0.8, "interpretation",
                                ChannelRequest.horizonFromDecay(0.8, 0.05)));
                    } else if (name.equals("LOW_CONFIDENCE")) {
                        requests.add(ChannelRequest.of(BasicSignals.LOW_CONFIDENCE, 0.7, "interpretation",
                                ChannelRequest.horizonFromDecay(0.7, 0.10)));
                    } else if (name.equals("DIVERGENCE_REQUEST")) {
                        requests.add(ChannelRequest.of(BasicSignals.DIVERGENCE_REQUEST, 0.7, "interpretation",
                                ChannelRequest.horizonFromDecay(0.7, 0.10)));
                    }
                });
        return requests;
    }
}
