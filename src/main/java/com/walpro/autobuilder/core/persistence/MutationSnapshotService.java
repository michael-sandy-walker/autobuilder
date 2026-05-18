package com.walpro.autobuilder.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MutationSnapshotService {

    private final FileService fileService;
    private final ObjectMapper objectMapper;
    
    public CycleContextData writeMutationMeta(CycleContextData context) throws IOException {
        String mutationPath = "mutations/" + context.cycleName();
        String targetPath = mutationPath + "/meta.json";
        fileService.writeJson(targetPath, context);
        log.debug("Wrote mutation meta for {} to {}", context.cycleName(), targetPath);
        return context.withMutationPath(mutationPath);
    }


    public CycleContextData readMutationMeta(CycleContextData context) throws IOException {
       return(readMutationMeta("mutations/" + context.cycleName() + "/meta.json"));
    }
    
    public CycleContextData readMutationMeta(String relativePath) throws IOException {
//    	String relativePath = "mutations/" + cycleName + "/meta.json";
        Path path = fileService.resolveSecurePath(relativePath);
        return readMutationMetaResolved(path);
    }
    
    public CycleContextData readMutationMetaResolved(Path path) throws IOException {
        if (!fileService.existsResolved(path)) {
            throw new IOException("Snapshot meta not found at: " + path);
        }
        String json = fileService.readStringResolved(path);
        return objectMapper.readValue(json, CycleContextData.class);
    }

    public List<CycleContextData> listAllMutations(CycleContextData context) {
    	String currentCycleId = context.cycleName();
        List<CycleContextData> mutations = new ArrayList<>();
        try {
            List<Path> folders = fileService.listSubfolders("mutations");
            for (Path folder : folders) {
            	if (folder.endsWith(currentCycleId)) {
            		log.debug("Skipping current cycle dir {}", currentCycleId);
            		continue;
            	}
                try {
                	mutations.add(readMutationMeta(folder.toString() + "/meta.json"));
                } catch (IOException e) {
                    log.warn("Skipping invalid snapshot at {}: {}", folder, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Failed to list snapshot folders", e);
        }
        return mutations;
    }

    public List<CycleContextData> listMergedMutations(CycleContextData context) {
        return listAllMutations(context).stream()
                .filter(CycleContextData::merged)
                .toList();
    }

    /**
     * T1-ED: Persists {@code ctx.mutationState()} to the mutation meta.json on disk.
     *
     * <p>Path resolution: current-cycle meta when {@code evolutionRanThisCycle=true};
     * cross-cycle carryover via {@code mutationPath} otherwise. Cross-cycle missing-file
     * failures are silent (T1-CX); same-cycle missing-file failures WARN + emit a behaviour
     * event and fall back to creating the file from the current context — but only when the
     * evolution outcome actually contains file changes. On a {@code #NoDiff}/noOp path
     * EvolutionAgent sets {@code evolutionRanThisCycle=true} without writing any mutation
     * files; falling back there would create a ghost meta.json that promises a variant that
     * doesn't exist on disk and silently blocks merge.
     */
    public void persistMutationState(CycleContextData ctx, @Nullable BehaviourLogger behaviourLogger) {
        if (ctx.mutationState() == null) return;
        boolean thisCycle = ctx.evolutionRanThisCycle();
        String metaRelativePath;
        if (thisCycle) {
            metaRelativePath = "mutations/" + ctx.cycleName() + "/meta.json";
        } else if (ctx.mutationPath() != null && !ctx.mutationPath().isBlank()) {
            metaRelativePath = ctx.mutationPath() + "/meta.json";
        } else {
            return;
        }
        try {
            CycleContextData existingMeta;
            try {
                existingMeta = readMutationMeta(metaRelativePath);
            } catch (IOException notFound) {
                if (thisCycle && hasMutationContent(ctx)) {
                    log.warn("T1-ED: meta.json not found at {} — creating with mutationState={}; cause: {}",
                            metaRelativePath, ctx.mutationState(), notFound.getMessage());
                    if (behaviourLogger != null) {
                        behaviourLogger.mutationMetaPersistFailed(ctx.cycleName(), metaRelativePath,
                                "not_found_fallback_created: " + notFound.getMessage());
                    }
                    writeMutationMeta(ctx);
                } else if (thisCycle) {
                    // #NoDiff / noOp cycle — evolutionRanThisCycle=true but no files written.
                    // Creating a meta.json here would mislead FileVariantCollector into thinking
                    // there's a variant to merge when there isn't.
                    log.debug("T1-ED: meta.json not found at {} on noOp cycle (no evolution outcome content) — skipping fallback create",
                            metaRelativePath);
                } else {
                    log.debug("T1-CX: meta.json not found at {} (cross-cycle carryover): {}",
                            metaRelativePath, notFound.getMessage());
                }
                return;
            }
            if (existingMeta == null) return;
            writeMutationMeta(existingMeta.withMutationState(ctx.mutationState()));
            log.debug("persisted mutationState={} to {}", ctx.mutationState(), metaRelativePath);
        } catch (IOException e) {
            if (thisCycle) {
                log.warn("T1-ED: could not persist mutationState={} to {}: {}",
                        ctx.mutationState(), metaRelativePath, e.getMessage());
                if (behaviourLogger != null) {
                    behaviourLogger.mutationMetaPersistFailed(ctx.cycleName(), metaRelativePath, e.getMessage());
                }
            } else {
                log.debug("could not update mutationState in {}: {}", metaRelativePath, e.getMessage());
            }
        }
    }

    private static boolean hasMutationContent(CycleContextData ctx) {
        var outcome = ctx.evolutionOutcome();
        if (outcome == null) return false;
        boolean addedOrChanged = outcome.addedOrChangedFiles() != null && !outcome.addedOrChangedFiles().isEmpty();
        boolean deleted = outcome.deletedFiles() != null && !outcome.deletedFiles().isEmpty();
        return addedOrChanged || deleted;
    }

    public CycleContextData writeSnapshot(CycleContextData context) throws IOException {
        String targetDir = "snapshots/" + context.cycleName();
        fileService.copyFolder(context.outputPath().toString(), targetDir);
        log.debug("Snapshot saved for {} at {}", context.cycleName(), targetDir);
        return context.withSnapshotPath(targetDir);
    }

}

