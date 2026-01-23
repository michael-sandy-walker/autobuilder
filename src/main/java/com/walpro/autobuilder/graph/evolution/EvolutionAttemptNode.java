package com.walpro.autobuilder.graph.evolution;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record EvolutionAttemptNode(
        String id,
        String cycleId,
        boolean divergent,
        String fingerprint,
        int changedFilesCount,
        int deletedFilesCount,
        List<String> changedPaths,
        List<String> deletedPaths,
        String goalId,
        String subGoalId,
        Instant created,
        Map<String, Object> meta
) {
    public static EvolutionAttemptNode of(
            String id,
            String cycleId,
            boolean divergent,
            String fingerprint,
            int changedFilesCount,
            int deletedFilesCount,
            List<String> changedPaths,
            List<String> deletedPaths,
            String goalId,
            String subGoalId,
            Map<String, Object> meta
    ) {
        return new EvolutionAttemptNode(
                id,
                cycleId,
                divergent,
                fingerprint,
                changedFilesCount,
                deletedFilesCount,
                changedPaths,
                deletedPaths,
                goalId,
                subGoalId,
                Instant.now(),
                meta
        );
    }
}
