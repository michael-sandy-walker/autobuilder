package com.walpro.autobuilder.coordinator;

import com.walpro.autobuilder.analyzer.DefaultProjectStructureSummarizer;
import com.walpro.autobuilder.diff.ProjectDiffResult;
import com.walpro.autobuilder.diff.ProjectDiffService;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalSimilarityService;
import com.walpro.autobuilder.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.MergeTrace;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.service.FileService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class BootstrapCoordinator {
    private final FileService fileService;
    private final GoalPlannerService goalPlannerService;
    private final DefaultProjectStructureSummarizer projectStructureSummarizer;
    private final GoalSimilarityService similarityService;
    private final ProjectDiffService projectDiffService;

    public boolean bootstrapIfNecessary() throws IOException {
        Path seedPath = fileService.resolveSecurePath("bootstrap/seed.txt");
        if (!fileService.existsResolved(seedPath)) {
            log.info("No seed file found. Skipping bootstrap.");
            return false;
        }

        Path bootstrapFlag = fileService.resolveSecurePath("meta/bootstrap.done");
        if (fileService.existsResolved(bootstrapFlag)) {
            log.info("Bootstrap already completed. Skipping.");
            return false;
        }

        try {
            String seedPrompt = fileService.readFileResolved(seedPath).trim();
            if (seedPrompt.isEmpty()) {
                log.warn("Seed prompt is empty. Aborting bootstrap.");
                return false;
            }

            String goalId = "goal-" + Instant.now().toEpochMilli();
            GoalEntity existingRoot = goalPlannerService.getRootGoal();

            if (existingRoot == null) {
                goalPlannerService.addInitialGoal(goalId, seedPrompt);
                log.info("Initial goal injected from seed prompt.");
            } else if (!existingRoot.getDescription().equals(seedPrompt)) {
                log.warn("⚠️ Detected mismatched or legacy root goal. Overwriting goal stack with seed prompt.");
                goalPlannerService.resetInitialGoal(goalId, seedPrompt);
            }

            fileService.writeFileResolved(bootstrapFlag, Instant.now().toString());
            goalPlannerService.getAllGoals().forEach(g -> similarityService.register(g.getDescription()));

            return true;
        } catch (Exception e) {
            log.error("Bootstrap failed", e);
            return false;
        }
    }

    public Optional<CycleContextData> prepareContext(CycleContextData context) {
        try {
            String cycleName = "bootstrap";
            Path outputPath = fileService.resolveSecurePath("output");
            Map<String, String> currentOutput = fileService.readFolderContentsResolved(outputPath);
            String structureSummary = projectStructureSummarizer.summarizeProjectStructure(outputPath.toString());
//            DiffReport diffReport = diffService.compareFolders(null, outputPath.toString(), null);
//            ProjectDiffResult diffResult = projectDiffService.computeDiff(Map.of(), mutatedOutput);
            ProjectDiffResult diffResult = projectDiffService.computeDiff(outputPath, null);



            GoalEntity currentGoal = goalPlannerService.getRootGoal();
            GoalEntity currentSubGoal = goalPlannerService.getCurrentSubGoal();
            var remaining = goalPlannerService.getRemainingGoals();
            var completed = goalPlannerService.getCompletedGoals();

            CycleContextData baseContext = (context == null ? CycleContextData.builder() : context.toBuilder())
                    .cycleName(cycleName)
                    .outputPath(outputPath.toString())
                    .previousOutput(currentOutput)
                    .projectSummary(structureSummary)
                    .projectDiff(diffResult)
                    .currentGoalDescription(currentGoal.getDescription())
                    .currentSubGoalDescription(currentSubGoal != null ? currentSubGoal.getDescription() : null)
                    .currentGoalId(currentGoal.getId())
                    .currentSubGoalId(currentSubGoal != null ? currentSubGoal.getId() : null)
                    .remainingGoals(remaining)
                    .completedGoals(completed)
                    .rejectionFeedback(null)
                    .recommendation(null)

                    .evolutionOutcome(null)
                    .evaluationOutcome(null)
                    .plannerOutcome(null)
                    .evaluationFeedback(null)
                    .evaluationInterpretation(null)
                    .mutationState(null)

                    .subgoalPromoted(false)
                    .merged(false)
                    .recombined(false)
                    .repeatedRecombination(false)

                    .lastEvolutionFingerprint(null)
                    .lastRecombinationFingerprint(null)
                    .recentEvolutionFingerprints(List.of())
                    .previousAcceptedCycleName(null)
                    .parentCycleName(null)
                    .recombinationSourceCycleName(null)

                    .mergedPath(null)
                    .mergedBaseContent(null)
                    .mergedVariants(List.of())
                    .mergeTrace(new MergeTrace(new ArrayList<>(), new HashMap<>(), new HashMap<>()))

                    .findings(List.of())
                    .scoreHistoryByFile(Map.of())
                    .recentDeltas(Map.of())
                    .build();

            return Optional.of(baseContext);
        } catch (Exception e) {
            log.error("Failed to prepare bootstrap context", e);
            return Optional.empty();
        }
    }
}
