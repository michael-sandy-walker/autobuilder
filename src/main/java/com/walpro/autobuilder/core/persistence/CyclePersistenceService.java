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
            CycleContextData snapshotContext = mutationSnapshotService.writeMutationMeta(context);
            log.debug("Mutation meta saved: {}", snapshotContext);

            context = mutationSnapshotService.writeSnapshot(snapshotContext);
            log.info("Snapshot saved: {}", snapshotContext.cycleName());
        } catch (IOException e) {
            log.error("Failed to persist snapshot for cycle {}", context.cycleName(), e);
        }
        return context;
    }
    
    public void writeMetadata(CycleContextData context) {
        try {
            mutationSnapshotService.writeMutationMeta(context);
            log.info("Mutation metadata written for cycle {}", context.cycleName());
        } catch (IOException e) {
            log.error("Failed to write mutation metadata for cycle {}", context.cycleName(), e);
        }
    }

}
