package com.walpro.autobuilder.agent.merge;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.core.engine.ExecutionAgent;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.core.service.merge.CycleMergeTracker;
import com.walpro.autobuilder.core.service.merge.FileVariantCollector;
import com.walpro.autobuilder.core.service.merge.MergeProcessor;
import com.walpro.autobuilder.core.service.merge.MergeResult;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.graph.merge.MergeActionRegistrar;
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
public class MergeAgent implements ExecutionAgent {

    private final FileService fileService;
    private final FileVariantCollector fileVariantCollector;
    private final MergeProcessor mergeProcessor;
    private final CycleMergeTracker cycleMergeTracker;
    private final MergeActionRegistrar mergeActionRegistrar;
    private final GoalPlannerService goalPlannerService;

    private static final String AGENT_ID = "StaticMergeAgent";

    @Override
    public CycleContextData execute(CycleContextData context) {
        log.info("Executing agent (role={})", role());

        try {
            return mergeCompletedBranch(context);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public CycleContextData mergeCompletedBranch(CycleContextData context) throws IOException {
        String outputPath = context.outputPath();
        String cycleId = context.cycleName();
//        String mergeCycleId = (context.lastAttemptId() != null && !context.lastAttemptId().isBlank())
//                ? context.lastAttemptId()
//                : context.cycleName();

        if (cycleMergeTracker.isAlreadyMerged(cycleId)) {
            log.debug("Skipping merge — cycle {} already marked as merged", cycleId);
            return context;
        }

        List<String> alreadyMergedCycles = cycleMergeTracker.getMergedCycles(context);
        Set<String> allPaths = fileVariantCollector.getAllVariantPaths(context);

        for (String path : allPaths) {
            List<FileVariant> variants;
            try {
                variants = fileVariantCollector.collect(path, alreadyMergedCycles);
            } catch (IOException e) {
                log.warn("Failed to collect variants for {}: {}", path, e.getMessage());
                continue;
            }

            if (variants.isEmpty()) {
                log.debug("No variants found for {}", path);
                continue;
            }

            String outputFileRel = outputPath + "/" + path;

            boolean fileExistsInOutput = fileService.exists(outputFileRel);
            String baseContent = fileExistsInOutput ? fileService.readString(outputFileRel) : "";

            MergeResult result = mergeProcessor.process(path, baseContent, variants, fileExistsInOutput, context);

            if (!result.wasSuccessful()) {
                log.warn("Merge failed for {}", path);
                continue;
            }

            try {
                Path outputFile = fileService.resolveSecurePath(outputFileRel);
                fileService.writeFileResolved(outputFile, result.currentContent());
            } catch (IOException e) {
                log.warn("Failed to write output file {}: {}", path, e.getMessage());
                continue;
            }

            context = cycleMergeTracker.track(context, path, result);

            mergeActionRegistrar.registerMerge(AGENT_ID, context, result); // TODO: here, we do not have the ultimate status, including markCyclesAsMerged and withoutSignal ROLE_MERGE, think about this
        }

        // Compute file count of this mutation for T2-AJ complexity-adaptive tolerance.
        EvolutionOutcome evolutionOutcome = context.evolutionOutcome();
        int mergedFileCount = 0;
        if (evolutionOutcome != null) {
            mergedFileCount += (evolutionOutcome.addedOrChangedFiles() != null ? evolutionOutcome.addedOrChangedFiles().size() : 0);
            mergedFileCount += (evolutionOutcome.deletedFiles() != null ? evolutionOutcome.deletedFiles().size() : 0);
        }

        if (mergedFileCount > 0) {
            goalPlannerService.incrementGoalMergeCount(context.currentSubGoalId());
        }

        // Consume merge role signal so it doesn't keep firing.
        return cycleMergeTracker
                .markCyclesAsMerged(context)
                .withLastAcceptedMutationFileCount(mergedFileCount)
                .withoutSignal(BasicSignals.ROLE_MERGE);
    }

    @Override
    public String name() {
        return "merge";
    }

    @Override
    public String role() {
        return "merge";
    }

    @Override
    public Set<SignalType> getSupportedSignals() {
        return BasicSignals.MERGE_SIGNALS;
    }
}
