package com.walpro.autobuilder.goal;

import java.util.List;

/**
 * T1-DE: structured result from a coverage-review pass.
 *
 * @param injected    features actually injected as new subgoals
 * @param dropped     features the LLM proposed but dropped as GIVEN_UP paraphrases
 * @param injectedIds goal ids of the injected subgoals, in injection order — lets the closure
 *                    edge attach real acceptance criteria to a work item instead of leaving it
 *                    criteria-less (where promotion would declare its log-text description as
 *                    the criterion and the next completion verification blocks on the blob)
 */
public record CoverageReviewResult(List<String> injected, int dropped, List<String> injectedIds) {

    public CoverageReviewResult(List<String> injected, int dropped) {
        this(injected, dropped, List.of());
    }
}
