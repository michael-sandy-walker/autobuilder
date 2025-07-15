package com.walpro.autobuilder.service.merge;

import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.persistence.CycleContext;
import com.walpro.autobuilder.persistence.MutationSnapshotService;
import com.walpro.autobuilder.service.FileService;
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

    public List<FileVariant> collect(String relativePath, Set<String> alreadyMerged) throws IOException {
        List<FileVariant> variants = new ArrayList<>();
        List<Path> cycleFolders = fileService.listSubfolders("mutations");

        for (Path cycleFolder : cycleFolders) {
            String cycleId = cycleFolder.getFileName().toString();

            if (alreadyMerged.contains(cycleId)) {
                log.debug("⏭️ Skipping already merged cycle {}", cycleId);
                continue;
            }

            String candidatePath = "mutations/" + cycleId;
            Path resolvedVariantPath = fileService.resolveSecurePath(candidatePath + "/" + relativePath);
            Path resolvedMetaPath = fileService.resolveSecurePath(candidatePath + "/meta.json");

            if (!fileService.existsResolved(resolvedVariantPath) || !fileService.existsResolved(resolvedMetaPath)) {
                continue;
            }

            CycleContext ctx;
            try {
                ctx = mutationSnapshotService.readMutationMetaResolved(resolvedMetaPath);
            } catch (IOException e) {
                log.warn("❌ Failed to read meta for cycle {}: {}", cycleId, e.getMessage());
                continue;
            }

            if (ctx == null || ctx.getSubgoalMetadata() == null || !ctx.getSubgoalMetadata().isValid()) {
                log.debug("⏭️ Skipping bootstrap or invalid cycle {}", cycleId);
                continue;
            }

            if (ctx.isMerged()) {
                log.debug("⏭️ Skipping cycle {} marked as merged in meta", cycleId);
                continue;
            }

            String content = fileService.readStringResolved(resolvedVariantPath);
            variants.add(new FileVariant(content, Set.of(ctx.getSubgoalMetadata()), cycleId));
        }

        return variants;
    }
}
