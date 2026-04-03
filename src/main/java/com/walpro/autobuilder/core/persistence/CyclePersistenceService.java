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
            // T5-BA: only write mutations/<cycleId>/meta.json when evolution actually ran;
            // otherwise evaluation-only cycles create empty folders that pollute FileVariantCollector scans.
            CycleContextData updated = context;
            if (context.evolutionRanThisCycle()) {
                updated = mutationSnapshotService.writeMutationMeta(context);
                log.debug("Mutation meta saved for cycle {}", updated.cycleName());
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
