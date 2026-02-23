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
import java.util.ArrayList;
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

        // Append contributors (dedupe to keep list stable-ish)
        if (result.contributingCycles() != null && !result.contributingCycles().isEmpty()) {
            for (String cid : result.contributingCycles()) {
                if (cid == null || cid.isBlank()) continue;
                if (!trace.mergedCycles().contains(cid)) {
                    trace.mergedCycles().add(cid);
                }
            }
        }

        if (result.merged() || result.adopted()) { // TODO: decide adoption semantics later
            trace.preMergeContent().putIfAbsent(path, result.previousContent());
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
                    log.info("Marked cycle {} as merged", cycleId);
                } else if (ctx != null) {
                    log.debug("Cycle {} already marked as merged", cycleId);
                }
            } catch (IOException e) {
                log.warn("Failed to mark cycle {} as merged: {}", cycleId, e.getMessage());
            }
        }

        // IMPORTANT: only mark *current* context as merged when *its own* cycle is being marked
        // (Otherwise we end up setting context.merged=true while marking other cycles.)
        if (context.mergeTrace().mergedCycles().contains(context.cycleName())) {
            context = context.withMerged(true);
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
        if (context.mergeTrace() == null || context.mergeTrace().mergedCycles() == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(context.mergeTrace().mergedCycles()); // defensive copy
    }
}