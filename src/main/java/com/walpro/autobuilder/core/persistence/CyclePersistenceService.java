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
            CycleContextData updated = mutationSnapshotService.writeMutationMeta(context);
            log.debug("Mutation meta saved for cycle {}", updated.cycleName());

            updated = mutationSnapshotService.writeSnapshot(updated);
            log.info("Snapshot saved: {}", updated.cycleName());
            return updated;
        } catch (IOException e) {
            log.error("Failed to persist snapshot for cycle {}", context.cycleName(), e);
            return context;
        }
    }
}
