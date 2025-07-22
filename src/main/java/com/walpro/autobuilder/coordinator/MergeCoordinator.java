package com.walpro.autobuilder.coordinator;

import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.lifecycle.CycleContextData;
import com.walpro.autobuilder.service.FileService;
import com.walpro.autobuilder.service.merge.CycleMergeTracker;
import com.walpro.autobuilder.service.merge.FileVariantCollector;
import com.walpro.autobuilder.service.merge.MergeProcessor;
import com.walpro.autobuilder.service.merge.MergeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class MergeCoordinator {

    private final FileService fileService;
    private final FileVariantCollector fileVariantCollector;
    private final MergeProcessor mergeProcessor;
    private final CycleMergeTracker cycleMergeTracker;

    public CycleContextData mergeCompletedBranch(CycleContextData context) throws IOException {
        String outputPath = context.outputPath();
        String cycleId = context.cycleName();

        if (cycleMergeTracker.isAlreadyMerged(cycleId)) {
            log.debug("🔁 Skipping merge — cycle {} already marked as merged", cycleId);
            return context;
        }

        List<String> alreadyMergedCycles = context.mergeTrace().mergedCycles();
        Set<String> allPaths = fileVariantCollector.getAllVariantPaths(context);

        for (String path : allPaths) {
            List<FileVariant> variants;
            try {
                variants = fileVariantCollector.collect(path, alreadyMergedCycles);
            } catch (IOException e) {
                log.warn("⚠️ Failed to collect variants for {}: {}", path, e.getMessage());
                continue;
            }

            if (variants.isEmpty()) {
                log.debug("⏭️ No variants found for {}", path);
                continue;
            }

            boolean fileExistsInOutput = fileService.exists("output/" + path);
            String baseContent = fileExistsInOutput ? fileService.readString("output/" + path) : "";

            MergeResult result = mergeProcessor.process(path, baseContent, variants, fileExistsInOutput);            

            if (!result.wasSuccessful()) {
                log.warn("❌ Merge failed for {}", path);
                continue;
            }

            try {
                Path outputFile = fileService.resolveSecurePath(outputPath + "/" + path);
                fileService.writeFileResolved(outputFile, result.currentContent());
            } catch (IOException e) {
                log.warn("⚠️ Failed to write output file {}: {}", path, e.getMessage());
                continue;
            }

            cycleMergeTracker.track(context, path, result);
        }

        CycleContextData r  = cycleMergeTracker.markCyclesAsMerged(context); 
        return r;
    }
}
