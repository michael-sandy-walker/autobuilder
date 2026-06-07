package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
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
 * <p>T4-AN-7: the {@code classification} + {@code missingToken} pair is the structured contract.
 * Source-fix resolvers branch on {@link Classification#category()} — the universal,
 * technology-agnostic field — and may prefer {@link Classification#missingSymbol()} (LLM-set)
 * over the regex {@code missingToken}. Config-fix resolvers may still read
 * {@link Classification#kind()} where the 4-value category is too coarse (e.g. distinguishing a
 * missing pom from a missing dependency — both {@code CONFIG_FIXABLE}). {@code missingToken}
 * names the offending package or symbol (empty for a missing-pom configuration error).
 */
public interface CompileFixResolver {

    Optional<FixProposal> resolve(Classification classification, String missingToken, CycleContextData context);
}
