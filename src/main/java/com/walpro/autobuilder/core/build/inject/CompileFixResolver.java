package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import java.util.Optional;

/**
 * Strategy interface for proposing a deterministic compile fix.
 *
 * <p>Implementations should be cheap and side-effect free — return a {@link FixProposal} if
 * they recognise the diagnosis, or {@link Optional#empty()} to defer. The orchestrator
 * ({@link CompileFixInjector}) chains resolvers and stops at the first match. New resolvers
 * (Maven Central lookup, local m2 index, symbol resolver) plug in here without touching the
 * injector or the probe.
 *
 * <p>The {@code kind} + {@code missingToken} pair is the structured contract from
 * classification: kind names the failure class; token names the offending package or symbol
 * (empty for {@link Kind#MISSING_POM}).
 */
public interface CompileFixResolver {

    Optional<FixProposal> resolve(Kind kind, String missingToken, CycleContextData context);
}
