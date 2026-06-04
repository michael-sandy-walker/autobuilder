package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;

import java.util.List;

/**
 * Provenance record for a deterministic compile fix produced by {@link CompileFixInjector}.
 *
 * <p>One record is the canonical source of truth for "this file change came from the injector,
 * not the LLM." It is persisted in {@code mutations/&lt;cycleId&gt;/meta.json}, referenced by the
 * graph layer, mirrored once into the behaviour log, and surfaced into Evaluator and Planner
 * prompts so downstream agents reason correctly about the diff.
 *
 * @param rule          short identifier of the resolver that fired (e.g. {@code "bootstrap-pom"},
 *                      {@code "known-starter:jakarta.persistence"}). Stable enough to be filtered
 *                      on in log analysis without being a strict enum.
 * @param kind          classified build-error kind that triggered the fix.
 * @param missingToken  package or symbol named in the stderr (empty for MISSING_POM).
 * @param touchedPaths  files written or modified by the fix, relative to {@code output/}.
 * @param description   one-line human-readable summary suitable for prompts and logs.
 * @param proposalHash  T2-DY: stable hash from {@link FixProposal#proposalHash()}. Persisted so
 *                      log analysis can aggregate "same proposal fired N times" without re-deriving
 *                      it from raw fields. Empty for legacy records — fall back to recomputation.
 */
public record InjectorFixRecord(
        String rule,
        Kind kind,
        String missingToken,
        List<String> touchedPaths,
        String description,
        String proposalHash
) {
    public InjectorFixRecord {
        touchedPaths = touchedPaths == null ? List.of() : List.copyOf(touchedPaths);
        if (rule == null || rule.isBlank()) throw new IllegalArgumentException("rule required");
        if (kind == null) throw new IllegalArgumentException("kind required");
        proposalHash = proposalHash == null ? "" : proposalHash;
    }

    /** Back-compat constructor without proposalHash. */
    public InjectorFixRecord(String rule, Kind kind, String missingToken,
                             List<String> touchedPaths, String description) {
        this(rule, kind, missingToken, touchedPaths, description, "");
    }
}
