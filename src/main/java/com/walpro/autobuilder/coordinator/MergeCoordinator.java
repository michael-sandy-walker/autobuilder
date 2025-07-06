package com.walpro.autobuilder.coordinator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.walpro.autobuilder.agent.merge.MergeAgent;
import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.agent.merge.model.MergeInput;
import com.walpro.autobuilder.agent.merge.model.MergeOutcome;
import com.walpro.autobuilder.model.CycleContextData;
import com.walpro.autobuilder.model.FileLineage;
import com.walpro.autobuilder.model.SubgoalMetadata;
import com.walpro.autobuilder.persistence.CycleContext;
import com.walpro.autobuilder.persistence.MutationSnapshotService;
import com.walpro.autobuilder.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class MergeCoordinator {

    private final MergeAgent mergeAgent;
    private final FileService fileService;
    private final MutationSnapshotService mutationSnapshotService;

    public CycleContextData mergeCompletedBranch(CycleContextData context) {
        int totalMerged = 0;
        int totalSkipped = 0;

        try {
            Map<String, List<String>> mergedFileLineage = new HashMap<>();

            Path lineageFile = context.outputPath().resolve("lineage.json");
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

            // 🧠 Step 0: Load existing lineage if it exists
            if (fileService.exists(lineageFile)) {
                try {
                    FileLineage existing = mapper.readValue(lineageFile.toFile(), FileLineage.class);
                    mergedFileLineage.putAll(existing.lineage());
                } catch (IOException e) {
                    log.warn("⚠️ Could not read existing lineage file, starting fresh.");
                }
            }

            Map<String, String> currentFiles = fileService.readFolderContents(context.outputPath().toString());

            // Load all already merged cycles
            Set<String> mergedCycles = mutationSnapshotService.listMergedMutations().stream()
                    .map(CycleContext::getName)
                    .collect(Collectors.toSet());

            // 🔍 Step 1: Collect all relevant file paths from all unmerged mutations
            Set<String> allPathsToMerge = new HashSet<>(currentFiles.keySet());
            List<Path> mutationDirs = fileService.listSubfolders(Path.of("mutations"));

            for (Path mutationDir : mutationDirs) {
                String cycleId = mutationDir.getFileName().toString();
                if (mergedCycles.contains(cycleId)) continue;

                List<String> relPaths = fileService.listAllRelativeFilePathsUnder("mutations/" + cycleId);
                allPathsToMerge.addAll(relPaths);
            }

            Set<String> mergedCycleIds = new HashSet<>();

            // 🔁 Step 2: Attempt to merge or add each file
            for (String path : allPathsToMerge) {
                // 🚫 Skip meta.json and system files
                if (isSystemFile(path)) {
                    log.debug("⏭️ Skipping system file from merge: {}", path);
                    continue;
                }

                boolean fileExistsInOutput = currentFiles.containsKey(path);
                String baseContent = fileExistsInOutput ? currentFiles.get(path) : "";

                List<FileVariant> variants = collectFileVariants(path, mergedCycles);
                int variantCount = variants.size();

                if (variants.isEmpty()) {
                    totalSkipped++;
                    log.debug("⏭️ Skipping merge for {} — no variants found.", path);
                    continue;
                }

                if (!fileExistsInOutput && variantCount == 1) {
                    // ✅ Promote new file from one mutation branch without merge
                    FileVariant only = variants.get(0);
                    fileService.writeFile("output/" + path, only.content());
                    log.info("✅ Promoted new file: {}", path);
                    totalMerged++;

                    mergedCycleIds.add(only.originCycleId());
                    mergedFileLineage
                            .computeIfAbsent(path, k -> new ArrayList<>())
                            .add(only.originCycleId());
                    continue;
                }

                // Otherwise: perform merge
                log.debug("🧪 Merging {} — {} variant(s) found", path, variantCount);
                MergeInput input = new MergeInput(path, baseContent, variants);
                MergeOutcome outcome = mergeAgent.merge(input);

                if (outcome.successful()) {
                    fileService.writeFile("output/" + path, outcome.mergedContent());
                    log.info("✅ Merged file: {}", path);
                    totalMerged++;

                    Set<String> contributingCycles = variants.stream()
                            .map(FileVariant::originCycleId)
                            .collect(Collectors.toSet());

                    mergedCycleIds.addAll(contributingCycles);
                    mergedFileLineage
                            .computeIfAbsent(path, k -> new ArrayList<>())
                            .addAll(contributingCycles);
                } else {
                    log.warn("⚠️ Merge failed for {} — Reason: {}", path, outcome.reasoning());
                }
            }

            for (String cycleId : mergedCycleIds) {
                try {
                    Path mutationPath = fileService.resolveSecurePath("mutations/" + cycleId);
                    CycleContext ctx = mutationSnapshotService.readMutationMeta(mutationPath);

                    if (!ctx.isMerged()) {
                        CycleContext updated = ctx.withMerged(true);
                        mutationSnapshotService.writeMutationMeta(updated);
                        log.info("🔏 Marked cycle {} as merged", cycleId);
                    }
                } catch (IOException e) {
                    log.warn("⚠️ Failed to mark cycle {} as merged: {}", cycleId, e.getMessage());
                }
            }

            log.info("🔀 Merge summary for {}: {} file(s) merged/promoted, {} file(s) skipped.",
                    context.cycleName(), totalMerged, totalSkipped);

            // ✅ Step 3: Write updated lineage file
            writeLineageFile(mergedFileLineage, context.outputPath());

        } catch (IOException e) {
            log.error("❌ Merge failed due to I/O error", e);
        }

        return context;
    }

    private List<FileVariant> collectFileVariants(String relativePath, Set<String> mergedCycleIds) throws IOException {
        List<FileVariant> variants = new ArrayList<>();
        List<Path> cycleFolders = fileService.listSubfolders(Path.of("mutations"));

        for (Path cycleFolder : cycleFolders) {
            String cycleId = cycleFolder.getFileName().toString();
            if (mergedCycleIds.contains(cycleId)) continue;

            String candidatePath = "mutations/" + cycleId;
            String candidateRelativePath = candidatePath + "/" + relativePath;
            Path resolvedPath = fileService.resolveSecurePath(candidateRelativePath);
            if (!fileService.exists(resolvedPath)) continue;

            String content = fileService.readString(resolvedPath);
            Path resolvedMetaPath = fileService.resolveSecurePath(candidatePath);
            CycleContext cycleContext = null;
            try {
                cycleContext = mutationSnapshotService.readMutationMeta(resolvedMetaPath);
            } catch (IOException e) {
                log.warn("Failed to read mutation meta for cycle id {}", cycleId);
            }

            if (cycleContext == null || cycleContext.getSubgoalMetadata() == null ||
                    !cycleContext.getSubgoalMetadata().isValid() ||
                    cycleContext.getSubgoalMetadata().id() == null) {
                log.debug("Skipping bootstrap or incomplete cycle {} from merge variant collection", cycleId);
                continue;
            }

            Set<SubgoalMetadata> goals = Set.of(cycleContext.getSubgoalMetadata());
            variants.add(new FileVariant(content, goals, cycleId));
        }
        return variants;
    }

    private boolean isSystemFile(String path) {
        return path.endsWith("meta.json") || path.contains(".DS_Store") || path.endsWith(".log");
    }

    private void writeLineageFile(Map<String, List<String>> lineage, Path outputPath) {
        if (lineage.isEmpty()) return; // nothing to write

        try {
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            Path lineageFile = outputPath.resolve("lineage.json");
            Map<String, List<String>> deduplicated = new HashMap<>();
            for (Map.Entry<String, List<String>> entry : lineage.entrySet()) {
                deduplicated.put(entry.getKey(), entry.getValue().stream().distinct().toList());
            }
            mapper.writeValue(lineageFile.toFile(), new FileLineage(deduplicated));
        } catch (IOException e) {
            log.warn("⚠️ Failed to write lineage file: {}", e.getMessage());
        }
    }
}
