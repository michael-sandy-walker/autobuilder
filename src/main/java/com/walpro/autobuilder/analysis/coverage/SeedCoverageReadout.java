package com.walpro.autobuilder.analysis.coverage;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * T4-DA: the answer to "do the subgoals, taken together, still solve the seed?".
 * <p>
 * Two findings, both at the granularity of a clause of the seed's own text:
 * <ul>
 *   <li>{@link #unclaimed()} — clauses carrying terms that appear in NO subgoal description,
 *       whatever its status. The plan does not articulate this part of the specification.</li>
 *   <li>{@link #unprovable()} — clauses the plan does articulate, but whose terms appear in no
 *       acceptance criterion anywhere. Something claims the work; nothing would prove it.</li>
 * </ul>
 * An {@link #isEmpty()} readout means the check ran and found nothing, which is different from
 * the check not running — callers that render this must not treat the two alike.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SeedCoverageReadout(List<Finding> unclaimed, List<Finding> unprovable) {

    /**
     * One clause of the seed with the terms no part of the plan mentions. The clause is kept
     * verbatim because it is what a reader acts on; the terms explain why it was selected.
     */
    public record Finding(String clause, List<String> missingTerms) {}

    public static SeedCoverageReadout empty() {
        return new SeedCoverageReadout(List.of(), List.of());
    }

    /**
     * Derived, and deliberately not serialized. This record rides on {@code CycleContextData},
     * which is written to each cycle's {@code meta.json} and read back; a getter-shaped accessor
     * is written as a property the canonical constructor has no parameter for, and the read-back
     * then fails. It cost 38 {@code mutation_meta_persist_failed} events on the first live run
     * that carried it before the field was suppressed here.
     */
    @JsonIgnore
    public boolean isEmpty() {
        return unclaimed.isEmpty() && unprovable.isEmpty();
    }
}
