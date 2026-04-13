package com.walpro.autobuilder.core.service.merge;

import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.FileInfo;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import com.walpro.autobuilder.model.SubgoalMetadata;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
import com.walpro.autobuilder.core.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileVariantCollector {

    private final FileService fileService;
    private final MutationSnapshotService mutationSnapshotService;

    public List<FileVariant> collect(String relativePath, List<String> alreadyMerged) throws IOException {
        List<FileVariant> variants = new ArrayList<>();
        List<Path> cycleFolders = fileService.listSubfolders("mutations");

        for (Path cycleFolder : cycleFolders) {
            String cycleId = cycleFolder.getFileName().toString();

            if (alreadyMerged.contains(cycleId)) {
                log.debug("Skipping already merged cycle {}", cycleId);
                continue;
            }

            String candidatePath = "mutations/" + cycleId;
            Path resolvedVariantPath = fileService.resolveSecurePath(candidatePath + "/" + relativePath);
            Path resolvedMetaPath = fileService.resolveSecurePath(candidatePath + "/meta.json");

            if (!fileService.existsResolved(resolvedVariantPath) || !fileService.existsResolved(resolvedMetaPath)) {
                continue;
            }

            CycleContextData ctx;
            try {
                ctx = mutationSnapshotService.readMutationMetaResolved(resolvedMetaPath);
            } catch (IOException e) {
                log.warn("Failed to read meta for cycle {}: {}", cycleId, e.getMessage());
                continue;
            }

            SubgoalMetadata meta = ctx.currentSubGoalId() != null
                    ? SubgoalMetadata.of(ctx.currentSubGoalId(), ctx.currentSubGoalDescription())
                    : SubgoalMetadata.of(null, ctx.currentGoalDescription());

            if (!meta.isValid()) {
                log.debug("Skipping bootstrap or invalid cycle {}", cycleId);
                continue;
            }

            if (ctx.merged()) {
                log.debug("Skipping cycle {} marked as merged in meta", cycleId);
                continue;
            }

            if (ctx.mutationState() != MutationAcceptanceState.ACCEPTED) {
                log.debug("Skipping cycle {} — mutationState is {} (not ACCEPTED)", cycleId, ctx.mutationState());
                continue;
            }

            String content = fileService.readStringResolved(resolvedVariantPath);
            Double score = ctx.evaluationOutcome() != null ? ctx.evaluationOutcome().score() : null;
            variants.add(new FileVariant(content, Set.of(meta), cycleId, score));
        }

        return variants;
    }
    
    public Set<String> getAllVariantPaths(CycleContextData context) {
        Set<String> allPaths = new HashSet<>();
        try {
            List<Path> cycleFolders = fileService.listSubfolders("mutations");

            for (Path cycleFolder : cycleFolders) {
                String cycleId = cycleFolder.getFileName().toString();

                Path metaPath = fileService.resolveSecurePath("mutations/" + cycleId + "/meta.json");
                if (!fileService.existsResolved(metaPath)) continue;

                CycleContextData ctx = mutationSnapshotService.readMutationMetaResolved(metaPath);
                if (ctx == null || ctx.merged()) continue;
                if (ctx.mutationState() != MutationAcceptanceState.ACCEPTED) continue;

                List<FileInfo> files = fileService.listFiles("mutations/" + cycleId);
                for (FileInfo file : files) {
                    String relative = file.getName();
                    if (!relative.equals("meta.json")) {
                        allPaths.add(relative.replace('\\', '/')); // normalize for cross-platform
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan variant paths: {}", e.getMessage());
        }

        return allPaths;
    }

    public boolean hasAnyVariant(CycleContextData context) throws IOException {
        Set<String> paths = getAllVariantPaths(context);
        if (paths == null || paths.isEmpty()) {
            return false;
        }

        List<String> alreadyMerged =
                context.mergeTrace() != null
                        ? context.mergeTrace().mergedCycles()
                        : List.of();

        for (String path : paths) {
            List<FileVariant> variants = collect(path, alreadyMerged);
            if (variants != null && !variants.isEmpty()) {
                return true; // at least one real candidate exists
            }
        }

        return false;
    }
}
