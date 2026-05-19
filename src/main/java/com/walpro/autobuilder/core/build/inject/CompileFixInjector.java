package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.build.BuildErrorClassifier;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Orchestrates deterministic compile-fix injection. Given the stderr of a failed compile probe
 * and the current cycle context, classifies the error and walks {@link CompileFixResolver}s in
 * declared order; returns the first {@link FixProposal} produced, or empty if none apply.
 *
 * <p>Stateless and cheap. The "one attempt per cycle" guarantee lives at the call site
 * ({@code CompileProbeService}), not here, so this component stays a pure function from
 * (stderr, context) to (fix, provenance).
 *
 * <p>The injector covers the high-confidence head of the build-error distribution; the
 * LLM-driven {@code [BUILD_FIX]} path stays as the safety net for everything else
 * ({@link BuildErrorClassifier.Kind#UNKNOWN}, symbols without a known fix, etc.).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompileFixInjector {

    private final List<CompileFixResolver> resolvers;

    public Optional<FixProposal> inject(String stderr, CycleContextData context) {
        if (stderr == null || stderr.isBlank()) return Optional.empty();

        Classification classification = BuildErrorClassifier.classify(stderr);
        Kind kind = classification.kind();
        if (kind == Kind.UNKNOWN) {
            log.debug("CompileFixInjector: unknown error kind — deferring to LLM");
            return Optional.empty();
        }

        String token = BuildErrorClassifier.extractToken(stderr, kind).orElse(null);
        for (CompileFixResolver resolver : resolvers) {
            Optional<FixProposal> proposal = resolver.resolve(kind, token, context);
            if (proposal.isPresent()) {
                return proposal;
            }
        }
        log.debug("CompileFixInjector: no resolver matched kind={} token={}", kind, token);
        return Optional.empty();
    }
}
