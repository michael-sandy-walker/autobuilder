package com.walpro.autobuilder.core.deliberation;

import java.util.List;
import java.util.Map;

import com.walpro.autobuilder.core.lifecycle.Signal;
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

        DeliberationMode mode = result.mode() != null ? result.mode() : DeliberationMode.NOOP;
        String rationale = result.rationale() != null ? result.rationale() : "(no rationale)";
        var considerations = result.considerations() != null ? result.considerations() : List.<String>of();
        var evidence = result.evidence() != null ? result.evidence() : List.<String>of();
        var meta = result.meta() != null ? result.meta() : Map.<String, Object>of();
        var proposedSignals = result.proposedSignals() != null ? result.proposedSignals() : List.<Signal>of();

        return new DeliberationResult(mode, c, rationale, considerations, evidence, meta, proposedSignals);
    }
}
