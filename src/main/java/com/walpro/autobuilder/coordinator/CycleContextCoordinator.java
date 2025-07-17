package com.walpro.autobuilder.coordinator;

import com.walpro.autobuilder.analyzer.DefaultProjectStructureSummarizer;
import com.walpro.autobuilder.diff.DiffReport;
import com.walpro.autobuilder.diff.DiffService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.model.CycleContextData;
import com.walpro.autobuilder.model.MergeTrace;
import com.walpro.autobuilder.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class CycleContextCoordinator {

    private final FileService fileService;
    private final GoalPlannerService goalPlannerService;
    private final DefaultProjectStructureSummarizer projectStructureSummarizer;
    private final DiffService diffService;

    public Optional<CycleContextData> prepareRuntimeContext(CycleContextData context) {
        try {
            String cycleName = "cycle-" + Instant.now().toEpochMilli();
            Path outputPath = fileService.resolveSecurePath("output");
            Map<String, String> currentOutput = fileService.readFolderContentsResolved(outputPath);
            String structureSummary = projectStructureSummarizer.summarizeProjectStructure(outputPath.toString());

            String previousCycleName = (context != null) ? context.previousAcceptedCycleName() : null;
            Path previousOutputPath = previousCycleName != null
                    ? fileService.resolveSecurePath("mutations/" + previousCycleName)
                    : null;

            DiffReport diffReport = diffService.compareFolders(
                    previousOutputPath != null && fileService.existsResolved(previousOutputPath)
                            ? previousOutputPath.toString()
                            : null,
                    outputPath.toString(),
                    null
            );
            log.info("🔍 Comparing previous output '{}' to current output '{}'", previousOutputPath, outputPath);


            GoalEntity currentGoal = goalPlannerService.getRootGoal();
            GoalEntity currentSubGoal = goalPlannerService.getCurrentSubGoal();
            var remaining = goalPlannerService.getRemainingGoals();
            var completed = goalPlannerService.getCompletedGoals();

            CycleContextData baseContext = (context == null ? CycleContextData.builder() : context.toBuilder())
            	    .cycleName(cycleName)
            	    .outputPath(outputPath.toString())
            	    .previousOutput(currentOutput)
            	    .projectSummary(structureSummary)
            	    .diffReport(diffReport)
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

            	    // ✅ Inherited lineage
            	    .lastEvolutionFingerprint(context != null ? context.lastEvolutionFingerprint() : null)
            	    .lastRecombinationFingerprint(context != null ? context.lastRecombinationFingerprint() : null)
            	    .recentEvolutionFingerprints(context != null ? context.recentEvolutionFingerprints() : List.of())
            	    .previousAcceptedCycleName(context != null ? context.previousAcceptedCycleName() : null)
            	    .parentCycleName(context != null ? context.cycleName() : null)
            	    .recombinationSourceCycleName(
            	    	    context != null && context.recombined()
            	    	        ? context.recombinationSourceCycleName() != null
            	    	            ? context.recombinationSourceCycleName()
            	    	            : context.cycleName()
            	    	        : null
            	    )

            	    // 🧠 Optional: preserve merge trace history across recursion
            	    .mergedPath(null)
            	    .mergedBaseContent(null)
            	    .mergedVariants(List.of())
            	    .mergeTrace(new MergeTrace(new ArrayList<>(), new HashMap<>(), new HashMap<>()))

            	    .changedPaths(List.of())
            	    .deletedPaths(List.of())
            	    .oldFiles(List.of())

            	    .findings(List.of())
            	    .scoreHistoryByFile(context != null ? context.scoreHistoryByFile() : Map.of())
            	    .recentDeltas(context != null ? context.recentDeltas() : Map.of())
            	    .changedMethods(List.of())
            	    .build();


            return Optional.of(baseContext);
        } catch (Exception e) {
            log.error("Failed to prepare runtime context", e);
            return Optional.empty();
        }
    }
}
