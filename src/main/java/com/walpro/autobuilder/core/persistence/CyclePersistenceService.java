package com.walpro.autobuilder.core.persistence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class CyclePersistenceService {

    private final MutationSnapshotService mutationSnapshotService;

    public CycleContextData persistSnapshot(CycleContextData context) {
        try {
            // T1-CT: do NOT call writeMutationMeta here. MutationApplier.applyRegularMutations()
            // already writes mutations/<cycleId>/meta.json immediately after evolution, capturing
            // the correct pre-promotion subgoalId and merged=false. Writing it again here would
            // overwrite it with the post-promotion context (wrong subgoalId, wrong merged flag),
            // which breaks FileVariantCollector's subgoalId filter and causes accepted mutations
            // to be permanently orphaned (T1-CT root cause).
            //
            // T5-BA intent is preserved: we still skip creating a mutations/<cycleId> folder for
            // evaluation-only cycles — MutationApplier is only called when evolution runs.
            //
            // If evolution ran this cycle, set mutationPath on the returned context so downstream
            // consumers (e.g. SmsCycleIngestor) can reference the correct folder path.
            CycleContextData updated = context;
            if (context.evolutionRanThisCycle()) {
                String mutationPath = "mutations/" + context.cycleName();
                updated = context.withMutationPath(mutationPath);
                log.debug("T1-CT: mutation meta already written by MutationApplier for cycle {}; skipping re-write", updated.cycleName());
            } else {
                log.debug("Skipping mutation meta write for cycle {} — no evolution ran", context.cycleName());
            }

            updated = mutationSnapshotService.writeSnapshot(updated);
            log.info("Snapshot saved: {}", updated.cycleName());
            return updated;
        } catch (IOException e) {
            log.error("Failed to persist snapshot for cycle {}", context.cycleName(), e);
            return context;
        }
    }
}
