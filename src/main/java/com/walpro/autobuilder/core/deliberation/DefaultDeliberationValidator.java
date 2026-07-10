package com.walpro.autobuilder.core.deliberation;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class DefaultDeliberationValidator implements DeliberationValidator {

    @Override
    public DeliberationResult validate(DeliberationResult result) {
        if (result == null) {
            return DeliberationResult.noop("DeliberationResult was null.");
        }

        double c = result.confidence();
        if (Double.isNaN(c) || Double.isInfinite(c)) c = 0.0;

        c = Math.max(0.0, Math.min(1.0, c));

        DeliberationMode mode = result.mode() != null ? result.mode() : DeliberationMode.UNDIRECTED;
        String rationale = result.rationale() != null ? result.rationale() : "(no rationale)";
        var considerations = result.considerations() != null ? result.considerations() : List.<String>of();
        var evidence = result.evidence() != null ? result.evidence() : List.<String>of();
        var meta = result.meta() != null ? result.meta() : Map.<String, Object>of();
        // T4-BZ-4 fix (caught by DeadlockGuardInvariantTest): rebuilding through a compat
        // constructor DROPPED proposedRequests. Every proposal carrier passes through.
        var proposedRequests = result.proposedRequests() != null
                ? result.proposedRequests() : List.<com.walpro.autobuilder.core.lifecycle.ChannelRequest>of();
        var proposedWindows = result.proposedWindows() != null
                ? result.proposedWindows() : List.<com.walpro.autobuilder.core.lifecycle.ParticipationWindow>of();

        return new DeliberationResult(mode, c, rationale, considerations, evidence, meta,
                proposedRequests, proposedWindows);
    }
}
