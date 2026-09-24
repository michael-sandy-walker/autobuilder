package com.walpro.autobuilder.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.model.MutationAcceptanceState;
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

    /**
     * T1-ED: Persists {@code ctx.mutationState()} to the mutation meta.json on disk.
     *
     * <p>Path resolution: current-cycle meta when {@code evolutionRanThisCycle=true};
     * cross-cycle carryover via {@code mutationPath} otherwise. {@code evolutionRanThisCycle}
     * means "wrote files" (T2-DH), so the flag being true implies mutation files exist and
     * a fallback create is safe. Cross-cycle missing-file failures are silent (T1-CX).
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
                if (thisCycle) {
                    log.warn("T1-ED: meta.json not found at {} — creating with mutationState={}; cause: {}",
                            metaRelativePath, ctx.mutationState(), notFound.getMessage());
                    if (behaviourLogger != null) {
                        behaviourLogger.mutationMetaPersistFailed(ctx.cycleName(), metaRelativePath,
                                "not_found_fallback_created: " + notFound.getMessage());
                    }
                    writeMutationMeta(ctx);
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

    /**
     * T1-EG: marks a prior cycle's mutation as REJECTED in its meta.json so that
     * {@link com.walpro.autobuilder.core.service.merge.FileVariantCollector} skips it on
     * subsequent cycles. Called from the post-merge rollback path (T1-DW) when a deferred
     * ACCEPTED mutation breaks the build — without this, the variant remains ACCEPTED on disk
     * and gets re-applied (and re-rolled-back) every cycle.
     */
    public void markMutationRejected(String mutationCycleId) {
        if (mutationCycleId == null || mutationCycleId.isBlank()) return;
        String metaRelativePath = "mutations/" + mutationCycleId + "/meta.json";
        try {
            CycleContextData existingMeta = readMutationMeta(metaRelativePath);
            if (existingMeta == null) return;
            writeMutationMeta(existingMeta.withMutationState(MutationAcceptanceState.REJECTED));
            log.info("T1-EG: marked mutation {} as REJECTED in meta.json after rollback", mutationCycleId);
        } catch (IOException e) {
            log.warn("T1-EG: could not mark mutation {} as REJECTED: {}", mutationCycleId, e.getMessage());
        }
    }

    public CycleContextData writeSnapshot(CycleContextData context) throws IOException {
        String targetDir = "snapshots/" + context.cycleName();
        fileService.copyFolder(context.outputPath().toString(), targetDir);
        stampSnapshotState(context, targetDir);
        log.debug("Snapshot saved for {} at {}", context.cycleName(), targetDir);
        return context.withSnapshotPath(targetDir);
    }

    /** File name of the T4-CA per-snapshot state stamp, written inside each snapshot directory. */
    public static final String SNAPSHOT_STATE_FILE = "autobuilder-snapshot-state.json";

    /**
     * T4-CA: recorded facts about the tree at snapshot time. {@code buildResult} is the cycle's
     * carried outcome recorded as-carried (may be null when no build ran); {@code entryPointPresent}
     * is a fresh scan. Consumed by {@link PreservedArtifactService} at the run terminal.
     */
    public record SnapshotState(String cycleId, boolean entryPointPresent, String buildResult,
                                boolean merged, java.time.Instant recordedAt) {}

    private void stampSnapshotState(CycleContextData context, String targetDir) {
        try {
            Path snapshotDir = fileService.resolveSecurePath(targetDir);
            boolean entryPoint = EntryPointScan.hasJavaEntryPoint(snapshotDir);
            String buildResult = context.buildOutcome() != null && context.buildOutcome().result() != null
                    ? context.buildOutcome().result().name()
                    : null;
            fileService.writeJson(targetDir + "/" + SNAPSHOT_STATE_FILE,
                    new SnapshotState(context.cycleName(), entryPoint, buildResult,
                            context.merged(), java.time.Instant.now()));
        } catch (Exception e) {
            log.warn("T4-CA: could not stamp snapshot state for {}: {}", context.cycleName(), e.getMessage());
        }
    }

}

