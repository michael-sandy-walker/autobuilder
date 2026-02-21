package com.walpro.autobuilder.core.service.merge;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.MergeTrace;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
import com.walpro.autobuilder.core.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CycleMergeTracker {

    private final FileService fileService;
    private final MutationSnapshotService mutationSnapshotService;

    public CycleContextData track(CycleContextData context, String path, MergeResult result) {
        if (!result.wasSuccessful()) return context;

        MergeTrace trace = context.mergeTrace();
        if (trace == null) return context;

        trace.mergedCycles().addAll(result.contributingCycles());

        if (result.merged() || result.adopted()) { // TODO: Still think about if adoption should be treated differently
            trace.preMergeContent().putIfAbsent(path, result.previousContent()); // assuming original baseContent = result.content()
        }

        trace.postMergeContent().put(path, result.currentContent());

        log.debug("Tracked merge result for {} — merged={}, adopted={}, contributors={}",
                path, result.merged(), result.adopted(), result.contributingCycles());
        
        return context;
    }

    public CycleContextData markCyclesAsMerged(CycleContextData context) {
        if (context.mergeTrace() == null) return context;

        for (String cycleId : context.mergeTrace().mergedCycles()) {
            try {
                Path mutationMetaPath = fileService.resolveSecurePath("mutations/" + cycleId + "/meta.json");
                CycleContextData ctx = mutationSnapshotService.readMutationMetaResolved(mutationMetaPath);

                if (ctx != null && !ctx.merged()) {
                    mutationSnapshotService.writeMutationMeta(ctx.withMerged(true));
                    context = context.withMerged(true);
                    log.info("Marked cycle {} as merged", cycleId);
                } else if (ctx != null) {
                    log.debug("Cycle {} already marked as merged", cycleId);
                }
            } catch (IOException e) {
                log.warn("Failed to mark cycle {} as merged: {}", cycleId, e.getMessage());
            }
        }
        return context;
    }

    public boolean isAlreadyMerged(String cycleId) {
        try {
            Path metaPath = fileService.resolveSecurePath("mutations/" + cycleId + "/meta.json");

            if (!fileService.existsResolved(metaPath)) {
                log.debug("Merge status unknown for {} (meta.json not present yet) -> treating as not merged", cycleId);
                return false;
            }

            CycleContextData ctx = mutationSnapshotService.readMutationMetaResolved(metaPath);
            return ctx != null && ctx.merged();
        } catch (IOException e) {
            log.warn("Could not check merge status for {}: {}", cycleId, e.getMessage());
            return false;
        }
    }
    
    public List<String> getMergedCycles(CycleContextData context) {
        return context.mergeTrace() != null ? context.mergeTrace().mergedCycles() : Collections.emptyList();
    }
}
