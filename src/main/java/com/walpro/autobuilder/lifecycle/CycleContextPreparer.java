package com.walpro.autobuilder.lifecycle;

import com.walpro.autobuilder.analyzer.DefaultProjectStructureSummarizer;
import com.walpro.autobuilder.diff.DiffReport;
import com.walpro.autobuilder.diff.DiffService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.model.CycleContextData;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import com.walpro.autobuilder.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class CycleContextPreparer {

    private final FileService fileService;
    private final DefaultProjectStructureSummarizer summarizer;
    private final DiffService diffService;
    private final GoalPlannerService goalPlannerService;

    public Optional<CycleContextData> prepareNewCycle(CycleContextData previous, String recombinationSource) {
        try {
            String cycleName = "cycle-" + Instant.now().toEpochMilli();
            String outputPath = "output";
            Map<String, String> currentOutput = fileService.readFolderContents(outputPath);
            String summary = summarizer.summarizeProjectStructure(outputPath);
            DiffReport diff = diffService.compareFolders(null, outputPath, null);

            GoalEntity currentGoal = goalPlannerService.getRootGoal();
            GoalEntity currentSub = goalPlannerService.getCurrentSubGoal();

            return Optional.of(CycleContextData.builder()
                    .cycleName(cycleName)
                    .outputPath(outputPath)
                    .previousOutput(currentOutput)
                    .projectSummary(summary)
                    .diffReport(diff)

                    .currentGoalDescription(currentGoal.getDescription())
                    .currentSubGoalDescription(currentSub != null ? currentSub.getDescription() : null)
                    .currentGoalId(currentGoal.getId())
                    .currentSubGoalId(currentSub != null ? currentSub.getId() : null)
                    .remainingGoals(goalPlannerService.getRemainingGoals().stream().map(GoalEntity::getId).toList())
                    .completedGoals(goalPlannerService.getCompletedGoals().stream().map(GoalEntity::getId).toList())

                    .mutationState(MutationAcceptanceState.NOT_EVALUATED)
                    .recentEvolutionFingerprints(previous != null ? previous.recentEvolutionFingerprints() : List.of())
                    .parentCycleName(previous != null ? previous.cycleName() : null)
                    .recombinationSourceCycleName(recombinationSource)

                    .rejectionFeedback(null)
                    .recommendation(null)
                    .evolutionOutcome(null)
                    .evaluationOutcome(null)
                    .plannerOutcome(null)
                    .evaluationFeedback(null)
                    .evaluationInterpretation(null)

                    .subgoalPromoted(false)
                    .merged(false)
                    .recombined(false)
                    .repeatedRecombination(false)

                    .lastEvolutionFingerprint(null)
                    .lastRecombinationFingerprint(null)
                    .previousAcceptedCycleName(null)
                    .build());

        } catch (Exception e) {
            log.error("❌ Failed to prepare cycle context", e);
            return Optional.empty();
        }
    }

    public Optional<CycleContextData> prepareInitialContext(String seedGoalDescription) {
        return prepareNewCycle(null, null)
                .map(c -> c.withCurrentGoalDescription(seedGoalDescription));
    }
}
