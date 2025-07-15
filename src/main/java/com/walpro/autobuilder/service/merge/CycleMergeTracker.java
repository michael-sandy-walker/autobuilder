package com.walpro.autobuilder.service.merge;

import com.walpro.autobuilder.model.CycleContextData;
import com.walpro.autobuilder.model.MergeTrace;
import com.walpro.autobuilder.persistence.CycleContext;
import com.walpro.autobuilder.persistence.MutationSnapshotService;
import com.walpro.autobuilder.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class CycleMergeTracker {

    private final FileService fileService;
    private final MutationSnapshotService mutationSnapshotService;

    public void track(CycleContextData context, String path, MergeResult result) {
        if (!result.wasSuccessful()) return;

        MergeTrace trace = context.mergeTrace();
        if (trace == null) return;

        trace.mergedCycles().addAll(result.contributingCycles());

        if (result.merged() || result.promoted()) { // TODO: Still think about if promotion should be treated differently
            trace.preMergeContent().putIfAbsent(path, result.content()); // assuming original baseContent = result.content()
        }

        trace.postMergeContent().put(path, result.content());

        log.debug("🧬 Tracked merge result for {} — merged={}, promoted={}, contributors={}",
                path, result.merged(), result.promoted(), result.contributingCycles());
    }

    public void markCyclesAsMerged(Set<String> cycleIds) {
        for (String cycleId : cycleIds) {
            try {
                Path mutationMetaPath = fileService.resolveSecurePath("mutations/" + cycleId + "/meta.json");
                CycleContext ctx = mutationSnapshotService.readMutationMetaResolved(mutationMetaPath);

                if (ctx != null && !ctx.isMerged()) {
                    CycleContext updated = ctx.withMerged(true);
                    mutationSnapshotService.writeMutationMeta(updated);
                    log.info("🔏 Marked cycle {} as merged", cycleId);
                } else if (ctx != null) {
                    log.debug("Cycle {} already marked as merged", cycleId);
                }
            } catch (IOException e) {
                log.warn("⚠️ Failed to mark cycle {} as merged: {}", cycleId, e.getMessage());
            }
        }
    }

    public boolean isAlreadyMerged(String cycleId) {
        try {
            Path metaPath = fileService.resolveSecurePath("mutations/" + cycleId + "/meta.json");
            CycleContext ctx = mutationSnapshotService.readMutationMetaResolved(metaPath);
            return ctx != null && ctx.isMerged();
        } catch (IOException e) {
            log.warn("⚠️ Could not check merge status for {}: {}", cycleId, e.getMessage());
            return false;
        }
    }
}
