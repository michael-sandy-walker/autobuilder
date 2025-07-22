package com.walpro.autobuilder.persistence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.walpro.autobuilder.lifecycle.CycleContextData;

import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class CyclePersistenceService {

    private final MutationSnapshotService mutationSnapshotService;

    public void persistSnapshot(CycleContextData context) {
        try {
            CycleContext snapshotContext = mutationSnapshotService.writeMutationMeta(context);
            log.info("Mutation meta saved: {}", snapshotContext);

            mutationSnapshotService.writeSnapshot(context);
            log.info("📸 Snapshot saved: {}", snapshotContext.getName());
        } catch (IOException e) {
            log.error("❌ Failed to persist snapshot for cycle {}", context.cycleName(), e);
        }
    }
    
    public void writeMetadata(CycleContextData context) {
        try {
            mutationSnapshotService.writeMutationMeta(context);
            log.info("📝 Mutation metadata written for cycle {}", context.cycleName());
        } catch (IOException e) {
            log.error("❌ Failed to write mutation metadata for cycle {}", context.cycleName(), e);
        }
    }

}
