package com.walpro.autobuilder.goal;

import java.util.List;

/**
 * T1-DE: structured result from a coverage-review pass.
 *
 * @param injected features actually injected as new subgoals
 * @param dropped  features the LLM proposed but dropped as GIVEN_UP paraphrases
 */
public record CoverageReviewResult(List<String> injected, int dropped) {}
