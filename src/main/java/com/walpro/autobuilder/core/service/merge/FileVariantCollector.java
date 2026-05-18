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

    private static final Set<String> BUILD_CONFIG_FILES = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"
    );

    private static boolean isBuildConfigFile(String path) {
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        return BUILD_CONFIG_FILES.contains(name);
    }

    public List<FileVariant> collect(String relativePath, List<String> alreadyMerged, String activeSubGoalId) throws IOException {
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

            // Build-config files (pom.xml, build.gradle, etc.) are project-wide — skip the
            // subGoalId filter. ACCEPTED variants also bypass it: a mutation that passed LLM +
            // heuristic evaluation is ready to merge regardless of goal-tree restructuring
            // by the planner or analysis agent after evaluation ran (T1-ED).
            boolean subgoalMismatch = activeSubGoalId != null && !activeSubGoalId.isBlank()
                    && !activeSubGoalId.equals(meta.id());
            if (subgoalMismatch && !isBuildConfigFile(relativePath)) {
                if (ctx.mutationState() != MutationAcceptanceState.ACCEPTED) {
                    log.debug("Skipping cycle {} — subgoalId {} does not match active subgoal {}",
                            cycleId, meta.id(), activeSubGoalId);
                    continue;
                }
                log.info("T1-ED: allowing ACCEPTED variant from cycle {} (snapshotSubGoal={}) despite subgoal shift to {}",
                        cycleId, meta.id(), activeSubGoalId);
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
        String activeSubGoalId = context.currentSubGoalId();
        try {
            List<Path> cycleFolders = fileService.listSubfolders("mutations");

            for (Path cycleFolder : cycleFolders) {
                String cycleId = cycleFolder.getFileName().toString();

                Path metaPath = fileService.resolveSecurePath("mutations/" + cycleId + "/meta.json");
                if (!fileService.existsResolved(metaPath)) continue;

                CycleContextData ctx = mutationSnapshotService.readMutationMetaResolved(metaPath);
                if (ctx == null || ctx.merged()) continue;
                if (ctx.mutationState() != MutationAcceptanceState.ACCEPTED) continue;

                // Build-config files are project-wide. ACCEPTED variants are also scope-free:
                // a mutation that passed evaluation is ready to merge regardless of goal-tree
                // restructuring (T1-ED). Other files respect the subgoal scope.
                boolean subGoalMatches = activeSubGoalId == null || activeSubGoalId.isBlank()
                        || activeSubGoalId.equals(ctx.currentSubGoalId())
                        || ctx.mutationState() == MutationAcceptanceState.ACCEPTED;

                List<FileInfo> files = fileService.listFiles("mutations/" + cycleId);
                for (FileInfo file : files) {
                    String relative = file.getName().replace('\\', '/');
                    if (relative.equals("meta.json")) continue;
                    if (!subGoalMatches && !isBuildConfigFile(relative)) continue;
                    allPaths.add(relative);
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
            List<FileVariant> variants = collect(path, alreadyMerged, context.currentSubGoalId());
            if (variants != null && !variants.isEmpty()) {
                return true; // at least one real candidate exists
            }
        }

        return false;
    }
}
