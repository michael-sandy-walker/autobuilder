package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.model.MergeTrace;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Component
public class ContextLineageTracker {

    public CycleContextData enrichLineage(CycleContextData baseContext, CycleContextData previousContext) {
        return baseContext
                .withLastEvolutionFingerprint(previousContext != null ? previousContext.lastEvolutionFingerprint() : null)
                .withLastRecombinationFingerprint(previousContext != null ? previousContext.lastRecombinationFingerprint() : null)
                .withRecentEvolutionFingerprints(previousContext != null ? previousContext.recentEvolutionFingerprints() : List.of())
                .withPreviousAcceptedCycleName(previousContext != null ? previousContext.previousAcceptedCycleName() : null)
                .withParentCycleName(previousContext != null ? previousContext.cycleName() : null)
                .withRecombinationSourceCycleName(
                        previousContext != null && previousContext.hasSignal(BasicSignals.RECOMBINATION_REQUEST)
                                ? (previousContext.recombinationSourceCycleName() != null
                                        ? previousContext.recombinationSourceCycleName()
                                        : previousContext.cycleName())
                                : null
                )
                .withLastEvolutionOutcome(previousContext != null ? previousContext.lastEvolutionOutcome() : null)
                .withLastEvaluationOutcome(previousContext != null ? previousContext.lastEvaluationOutcome() : null)
                .withLastPlannerOutcome(previousContext != null ? previousContext.lastPlannerOutcome() : null)
                .withScoreHistoryByFile(previousContext != null ? previousContext.scoreHistoryByFile() : null)
                .withRecentDeltas(previousContext != null ? previousContext.recentDeltas() : null);
    }

    public MergeTrace emptyMergeTrace() {
        return new MergeTrace(new ArrayList<>(), new HashMap<>(), new HashMap<>());
    }
}
