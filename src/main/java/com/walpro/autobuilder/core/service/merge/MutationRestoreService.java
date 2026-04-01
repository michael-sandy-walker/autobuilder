package com.walpro.autobuilder.core.service.merge;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.evolution.EvolutionGraphPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Restores mutation files from the graph when the filesystem copy is missing.
 * The graph is the authoritative store; the filesystem acts as a working cache.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MutationRestoreService {

    private final AgentGraphService agentGraphService;
    private final FileService fileService;
    private final MutationSnapshotService mutationSnapshotService;

    /**
     * Looks up the most recent evolution payload for the current subgoal in the graph
     * and writes the mutation files (and a valid meta.json) back to {@code mutations/<cycleId>/}.
     *
     * @return true if at least one file was restored, false if nothing was found or restore failed
     */
    public boolean tryRestoreFromGraph(CycleContextData ctx) {
        String subGoalId = ctx.currentSubGoalId();
        if (subGoalId == null || subGoalId.isBlank()) {
            return false;
        }

        java.util.Optional<EvolutionGraphPayload> payloadOpt =
                agentGraphService.findLatestEvolutionPayload(subGoalId);

        if (payloadOpt.isEmpty()) {
            log.debug("No evolution payload in graph for subGoal={}", subGoalId);
            return false;
        }

        EvolutionGraphPayload payload = payloadOpt.get();
        if (payload.mutations().isEmpty()) {
            log.debug("Evolution payload for subGoal={} has no mutation files", subGoalId);
            return false;
        }

        String cycleId = payload.cycleId();
        String mutationPrefix = "mutations/" + cycleId + "/";

        try {
            for (FileMutation m : payload.mutations()) {
                fileService.writeFile(mutationPrefix + m.path(), m.content());
            }

            // Write a minimal meta.json so FileVariantCollector can pick up the restored files.
            CycleContextData meta = CycleContextData.builder()
                    .cycleName(cycleId)
                    .currentGoalId(ctx.currentGoalId())
                    .currentGoalDescription(ctx.currentGoalDescription())
                    .currentSubGoalId(ctx.currentSubGoalId())
                    .currentSubGoalDescription(ctx.currentSubGoalDescription())
                    .build();
            mutationSnapshotService.writeMutationMeta(meta);

            log.info("Restored {} mutation file(s) from graph for subGoal={} cycleId={}",
                    payload.mutations().size(), subGoalId, cycleId);
            return true;

        } catch (IOException e) {
            log.warn("Failed to restore mutation files from graph for subGoal={}: {}", subGoalId, e.getMessage());
            return false;
        }
    }
}
