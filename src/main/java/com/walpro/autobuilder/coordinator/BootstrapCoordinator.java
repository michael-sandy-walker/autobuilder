package com.walpro.autobuilder.coordinator;

import com.walpro.autobuilder.analyzer.DefaultProjectStructureSummarizer;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.diff.DiffReport;
import com.walpro.autobuilder.diff.DiffService;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalSimilarityService;
import com.walpro.autobuilder.model.CycleContextData;
import com.walpro.autobuilder.model.ProjectInput;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.parser.ResponseParser;
import com.walpro.autobuilder.protocol.bootstrap.ProtocolContract;
import com.walpro.autobuilder.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class BootstrapCoordinator {
    private final FileService fileService;
    private final LLMClient llmClient;
    private final GoalPlannerService goalPlannerService;
    private final DefaultProjectStructureSummarizer projectStructureSummarizer;
    private final DiffService diffService;
    private final GoalSimilarityService similarityService;

    public boolean bootstrapIfNecessary() throws IOException {
        Path seedPath = fileService.resolveSecurePath("bootstrap/seed.txt");
        if (!fileService.exists(seedPath)) {
            log.info("No seed file found. Skipping bootstrap.");
            return false;
        }

        Path bootstrapFlag = fileService.resolveSecurePath("meta/bootstrap.done");
        if (fileService.exists(bootstrapFlag)) {
            log.info("Bootstrap already completed. Skipping.");
            return false;
        }

        try {
            // Contains the initial root goal for bootstrapping the system.
            // If present and bootstrap is not yet done, this file will trigger initial goal + file generation.
            String seedPrompt = fileService.readFile(seedPath.toString()).trim();
            if (seedPrompt.isEmpty()) {
                log.warn("Seed prompt is empty. Aborting bootstrap.");
                return false;
            }

            Path outputPath = fileService.resolveSecurePath("output");
            ProjectInput input = ProjectInput.forBootstrap(outputPath, seedPrompt, projectStructureSummarizer);
            List<Message> conversation = List.of(
                    new Message("system", ProtocolContract.getSystemPrompt(true)),
                    new Message("user", ProtocolContract.getUserPrompt("bootstrap", input))
            );

            ChatResponse response = llmClient.ask(conversation).getBody();
            if (response != null && response.getFirstContent() != null) {
                String reply = response.getFirstContent();
                fileService.writeFile("responses/bootstrap/seed.reply.txt", reply);
                log.info("Wrote bootstrap response to responses/bootstrap/seed.reply.txt");

                Map<String, String> files = ResponseParser.extractFiles(reply);
                for (var entry : files.entrySet()) {
                    String path = "output/" + entry.getKey();
                    fileService.writeFile(path, entry.getValue());
                    log.info("Wrote file: {}", path);
                }

                if (goalPlannerService.getRootGoal() == null) {
                    goalPlannerService.addInitialGoal("goal-" + Instant.now().toEpochMilli(), seedPrompt);
                    log.info("Initial goal injected from seed prompt.");
                } else if (goalPlannerService.getRootGoal() != null &&
                        !goalPlannerService.getRootGoal().getDescription().equals(seedPrompt)) { // Replace goal stack if legacy or inconsistent
                    log.warn("⚠️ Detected mismatched or legacy root goal. Overwriting goal stack with seed prompt.");
                    goalPlannerService.resetInitialGoal("goal-" + Instant.now().toEpochMilli(), seedPrompt);
                }

                fileService.writeFile(bootstrapFlag.toString(), Instant.now().toString());
                
                goalPlannerService.getAllGoals().forEach(g -> similarityService.register(g.getDescription()));

                return true;
            } else {
                log.warn("Bootstrap LLM returned no usable content.");
                return false;
            }

        } catch (Exception e) {
            log.error("Bootstrap failed", e);
            return false;
        }
    }

    public Optional<CycleContextData> prepareContext(CycleContextData context) {
        try {
            String cycleName = "cycle-" + Instant.now().toEpochMilli();
            Path outputPath = fileService.resolveSecurePath("output");
            Map<String, String> currentOutput = fileService.readFolderContents(outputPath.toString());

            String structureSummary = projectStructureSummarizer.summarizeProjectStructure(outputPath);
            DiffReport diffReport = diffService.compareFolders(null, outputPath.toString(), null); // null = no previous state

            GoalEntity currentGoal = goalPlannerService.getRootGoal();
            GoalEntity currentSubGoal = goalPlannerService.getCurrentSubGoal();
            var remaining = goalPlannerService.getRemainingGoals();
            var completed = goalPlannerService.getCompletedGoals();
            
        	if (context == null) {	
        		CycleContextData initialContext = CycleContextData.builder()
	                    .cycleName(cycleName)
	                    .outputPath(outputPath)
	                    .previousOutput(currentOutput)
	                    .projectSummary(structureSummary)
	                    .diffReport(diffReport)
	                    .currentGoalDescription(currentGoal.getDescription())
	                    .currentSubGoalDescription(currentSubGoal != null ? currentSubGoal.getDescription() : null)
	                    .currentGoalId(currentGoal.getId())
	                    .currentSubGoalId(currentSubGoal != null ? currentSubGoal.getId() : null)
	                    .remainingGoals(remaining.stream().map(GoalEntity::getId).toList())
	                    .completedGoals(completed.stream().map(GoalEntity::getId).toList())
	                    .rejectionFeedback(null)
	                    .recommendation(null)
	                    .build();
	
	            return Optional.of(initialContext);
        	} else {
        		return Optional.of(
        				context
        				.withCycleName(cycleName)
        				.withOutputPath(outputPath)
        				.withPreviousOutput(currentOutput)
        				.withProjectSummary(structureSummary)
        				.withCurrentGoalDescription(currentGoal.getDescription())
 	                    .withCurrentSubGoalDescription(currentSubGoal != null ? currentSubGoal.getDescription() : null)
 	                    .withCurrentGoalId(currentGoal.getId())
 	                    .withCurrentSubGoalId(currentSubGoal != null ? currentSubGoal.getId() : null)
 	                    .withRemainingGoals(remaining.stream().map(GoalEntity::getId).toList())
 	                    .withCompletedGoals(completed.stream().map(GoalEntity::getId).toList())
 	                    .withRejectionFeedback(null)
 	                    .withRecommendation(null));
        	}
        } catch (Exception e) {
            log.error("Failed to prepare bootstrap context", e);
            return Optional.empty();
        }
    }
}