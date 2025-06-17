package com.walpro.autobuilder.service;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.analyzer.DefaultProjectStructureSummarizer;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.diff.DiffReport;
import com.walpro.autobuilder.diff.DiffService;
import com.walpro.autobuilder.evaluation.MutationEvaluator;
import com.walpro.autobuilder.evaluation.dynamic.DynamicEvaluationEngine;
import com.walpro.autobuilder.evaluation.dynamic.DynamicEvaluator;
import com.walpro.autobuilder.evaluation.dynamic.DynamicEvaluatorDefinition;
import com.walpro.autobuilder.evaluation.dynamic.DynamicEvaluatorRegistry;
import com.walpro.autobuilder.evaluation.dynamic.EvaluatingDynamicHost;
import com.walpro.autobuilder.evaluation.dynamic.LLMLogicInterpreter;
import com.walpro.autobuilder.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.evaluation.model.GoalProgressState;
import com.walpro.autobuilder.evaluation.model.MutationAcceptanceState;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.heuristic.DefaultMutationHeuristicAnalyzer;
import com.walpro.autobuilder.heuristic.HeuristicFinding;
import com.walpro.autobuilder.heuristic.MutationHeuristicAnalyzer;
import com.walpro.autobuilder.heuristic.ScoreTracker;
import com.walpro.autobuilder.model.EvaluationInput;
import com.walpro.autobuilder.model.ProjectInput;
import com.walpro.autobuilder.parser.ResponseParser;
import com.walpro.autobuilder.persistence.CycleContext;
import com.walpro.autobuilder.persistence.MutationSnapshotService;
import com.walpro.autobuilder.protocol.ProtocolContract;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class AutobuilderService {

    private final FileService fileService;
    private final LLMClient llmClient;
    private final DiffService diffService;
    private final DefaultProjectStructureSummarizer projectStructureSummarizer;
    private final MutationHeuristicAnalyzer mutationHeuristicAnalyzer;
    private final MutationSnapshotService mutationSnapshotService;
    private final GoalPlannerService goalPlannerService;
    private final MutationEvaluator mutationEvaluator;
    private final DynamicEvaluationEngine dynamicEvaluationEngine;
    private final DynamicEvaluatorRegistry dynamicEvaluatorRegistry;
    private final LLMLogicInterpreter llmLogicInterpreter;

    private static final double HEURISTIC_THRESHOLD = 0.4;
    private static final ObjectMapper mapper = new ObjectMapper();

    boolean isBootstrapping = false;

    @PostConstruct
    public void initializeProjectFolders() throws IOException {
        List<String> folders = List.of("reference", "bootstrap", "output", "logs", "test", "responses/bootstrap", "responses/evolve", "rejected", "logs/rejected");
        for (String dir : folders) {
            Path path = fileService.resolveSecurePath(dir);
            if (!fileService.exists(path)) {
            	fileService.createDirectories(path);
                log.info("Created project directory: {}", path);
            }
        }
    }

    @PostConstruct
    public void bootstrap() throws IOException {
        isBootstrapping = true;
        Path seedPath = fileService.resolveSecurePath("bootstrap/seed.txt");
        if (!fileService.exists(seedPath)) {
            log.info("No seed file found at bootstrap/seed.txt. Skipping bootstrap.");
            return;
        }

        try {
            String seedPrompt = fileService.readFile("bootstrap/seed.txt").trim();
            if (seedPrompt.isEmpty()) {
                log.warn("Seed prompt is empty.");
                return;
            }

            ProjectInput input = ProjectInput.empty(seedPath, seedPrompt);
            List<Message> conversation = buildConversation(input, "bootstrap", true);
            
            ChatResponse response = llmClient.ask(conversation).getBody();

            if (response != null && response.getFirstContent() != null) {
                String reply = response.getFirstContent();
                fileService.writeFile("responses/bootstrap/seed.reply.txt", reply);
                log.info("Wrote bootstrap response to responses/bootstrap/seed.reply.txt");
                handleLLMReply(reply, "output/");
            } else {
                log.warn("No bootstrap response.");
            }

        } catch (Exception e) {
            log.error("Bootstrap failed", e);
        }
        isBootstrapping = false;
    }
    
    @PostConstruct
    public void initEvaluatorDefinition() {
    	DynamicEvaluatorDefinition dynamicEvaluatorDefinition = new DynamicEvaluatorDefinition(
    		    "heuristic-llm-response",
    		    "watchdog",
    		    "Evaluates severity and meaning of heuristic findings to determine if the mutation should be rejected",
    		    """
    		    If there are any heuristic findings with severity CRITICAL, reject the mutation and explain why.
    		    If there are multiple WARNINGs or a mix of WARNING and CRITICAL, reject unless score > 0.7.
    		    If only INFO findings are present, accept.
    		    Always explain your reasoning briefly.
    		    """,
    		    true
    		);
    	
        EvaluatingDynamicHost evaluator = new EvaluatingDynamicHost(dynamicEvaluatorDefinition, llmLogicInterpreter);
        dynamicEvaluatorRegistry.register(evaluator);
    }

    @Scheduled(fixedDelay = 300_000)
    public void evolveProject() {
        if (isBootstrapping) {
            return;
        }
        try {
            String projectSummary = projectStructureSummarizer
                    .summarizeProjectStructure(fileService.resolveSecurePath("output"));

            DiffReport diffReport = diffService.compareFolders(
                    fileService.resolveSecurePath("last-evolution").toAbsolutePath().toString(),
                    fileService.resolveSecurePath("output").toAbsolutePath().toString(),
                    Collections.emptySet()
            );
            
            Map<String, String> previousOutput = fileService.readFolderContents("output");
            String currentGoal = goalPlannerService.getCurrentGoal();
            List<String> remainingGoals = goalPlannerService.getRemainingGoals();
            List<String> completedGoals = goalPlannerService.getCompletedGoals();
            
            String rejectionFeedback = null;
            Path feedbackPath = fileService.resolveSecurePath("rejected/rejection-feedback.txt");
            if (fileService.exists(feedbackPath)) {
                rejectionFeedback = fileService.readFile(feedbackPath.toString()).trim();
            }
            String recommendation = null;
            Path recPath = fileService.resolveSecurePath("rejected/recommendation.txt");
            if (fileService.exists(recPath)) {
                recommendation = fileService.readFile(recPath.toString()).trim();
            }

            ProjectInput input = new ProjectInput(
                diffReport,
                projectSummary,
                previousOutput,
                fileService.resolveSecurePath("output"),
                currentGoal,
                remainingGoals,
                completedGoals,
                rejectionFeedback,
                recommendation != null && !recommendation.isBlank() ? recommendation : null    
            );
            String cycleName = getNextCycleName();
            List<Message> conversation = buildConversation(input, cycleName, false);

            ChatResponse response = llmClient.ask(conversation).getBody();

            if (response != null && response.getFirstContent() != null) {
            	String timestamp = Instant.now().toString().replace(":", "-");
                String path = "responses/evolve/evolve-" + timestamp + ".reply.txt";
                fileService.writeFile(path, response.getFirstContent());
                log.info("Wrote evolve response to {}", path);

                handleLLMReplyWithHeuristics(cycleName, response.getFirstContent(), path, previousOutput, currentGoal, remainingGoals, completedGoals);
            }

        } catch (Exception e) {
            log.error("Evolution failed", e);
        }
    }

    private List<Message> buildConversation(ProjectInput input, String cycleName, boolean isBootstrap) {
        String sysPrompt = ProtocolContract.getSystemPrompt(isBootstrap);        
        String userPrompt = ProtocolContract.getUserPrompt(cycleName, input);

        return List.of(
            new Message("system", sysPrompt),
            new Message("user", userPrompt)
        );
    }

    private void handleLLMReply(String reply, String fallbackPrefix) throws IOException {
        Map<String, String> files = ResponseParser.extractFiles(reply);
        if (files.isEmpty()) {
            String fallback = fallbackPrefix.endsWith(".txt") ? fallbackPrefix : fallbackPrefix + ".reply.txt";
            fileService.writeFile(fallback, reply);
            log.warn("No files extracted. Wrote fallback: {}", fallback);
        } else {
            for (var entry : files.entrySet()) {
                String path = "output/" + entry.getKey();
                fileService.writeFile(path, entry.getValue());
                log.info("Wrote file: {}", path);
            }
        }
    }

    private void handleLLMReplyWithHeuristics(String cycleName, String reply, String fallbackPath, Map<String, String> previousOutput, String currentGoal, List<String> remainingGoals, List<String> completedGoals) throws IOException {
    	Map<String, String> updatedFiles = ResponseParser.extractFiles(reply);
    	Set<String> deletedFiles = ResponseParser.extractDeletions(reply);

    	if (updatedFiles.isEmpty() && deletedFiles.isEmpty()) {
    		fileService.writeFile(fallbackPath, reply);
    		log.warn("No files extracted. Wrote fallback: {}", fallbackPath);
    		return;
    	}

    	// Heuristic analysis
    	List<HeuristicFinding> findings = mutationHeuristicAnalyzer.analyze(previousOutput, updatedFiles, deletedFiles);
    	double score = ((DefaultMutationHeuristicAnalyzer) mutationHeuristicAnalyzer)
    			.calculateScore(previousOutput, updatedFiles, deletedFiles, findings);

    	for (HeuristicFinding finding : findings) {
    		log.warn("Heuristic issue: [{}] {} - {}", finding.type(), finding.path(), finding.description());
    	}
    	log.info("Heuristic score: {}", score);
    	
    	Map<String, Object> logEntry = new HashMap<>();
    	logEntry.put("cycleName", cycleName);
    	logEntry.put("timestamp", Instant.now().toString());
    	logEntry.put("score", score);
    	logEntry.put("warnings", findings);
    	logEntry.put("filesChanged", updatedFiles.keySet());
    	logEntry.put("currentGoal", currentGoal);
    	logEntry.put("remainingGoals", remainingGoals);
    	logEntry.put("completedGoals", completedGoals);
    	Path logPath = fileService.resolveSecurePath("logs/evolution-log.jsonl");
    	fileService.writeString(logPath,
    			mapper.writeValueAsString(logEntry) + System.lineSeparator(),
    			StandardOpenOption.CREATE, StandardOpenOption.APPEND);

    	// Evaluation
    	ScoreTracker tracker = new ScoreTracker(fileService);
    	Double lastScore = tracker.getLastAcceptedScore();
//        EvaluationOutcome outcome = mutationEvaluator.evaluate(
//                score,
//                lastScore,
//                updatedFiles.keySet(),
//                deletedFiles,
//                currentGoal,
//                previousOutput,
//                findings
//            );
//    	log.info("Evaluation result: Mutation={}, Goal={}, Score={}", outcome.mutationState(), outcome.goalProgress(), score);
//    	log.info("Feedback: {}", outcome.feedbackMessage());
//    	if (outcome.recommendation() != null && !outcome.recommendation().isBlank()) {
//    	    log.info("Recommendation: {}", outcome.recommendation());
//    	}
//
//    	if (outcome.mutationState() == MutationAcceptanceState.REJECTED) {
//    		fileService.writeFile("rejected/rejection-feedback.txt", outcome.feedbackMessage());
//    		logRejection(cycleName, outcome.feedbackMessage(), score, lastScore, outcome.recommendation());
//    		tracker.markRejected();
//    		return;
//    	}

    	EvaluationInput input = new EvaluationInput(
    		    score,
    		    lastScore,
    		    updatedFiles.keySet(),
    		    deletedFiles,
    		    currentGoal,
    		    previousOutput,
    		    findings
    		);
    	
    	List<EvaluationOutcome> dynamicOutcomes = dynamicEvaluationEngine.evaluateAll(input);
    	EvaluationOutcome outcome = dynamicOutcomes.stream()
    		    .findFirst() // For now, take the first (only) one
    		    .orElse(new EvaluationOutcome(
    		        MutationAcceptanceState.ACCEPTED,
    		        GoalProgressState.NOT_EVALUATED,
    		        "No active dynamic evaluation",
    		        null
    		    ));
    	
    	log.info("Evaluation result: Mutation={}, Goal={}, Score={}", outcome.mutationState(), outcome.goalProgress(), score);
    	log.info("Feedback: {}", outcome.feedbackMessage());
    	if (outcome.recommendation() != null && !outcome.recommendation().isBlank()) {
    	    log.info("Recommendation: {}", outcome.recommendation());
    	}

    	if (outcome.mutationState() == MutationAcceptanceState.REJECTED) {
    		fileService.writeFile("rejected/rejection-feedback.txt", outcome.feedbackMessage());
    		logRejection(cycleName, outcome.feedbackMessage(), score, lastScore, outcome.recommendation());
    		tracker.markRejected();
    		return;
    	}

    	// Apply accepted files
    	for (var entry : updatedFiles.entrySet()) {
    		String path = "output/" + entry.getKey();
    		fileService.writeFile(path, entry.getValue());
    		log.info("Wrote file: {}", path);
    	}

    	for (String file : deletedFiles) {
    		String path = "output/" + file;
    		if (fileService.exists(fileService.resolveSecurePath(path))) {
    			fileService.deleteFile(path);
    			log.info("Deleted file: {}", path);
    		} else {
    			log.warn("Tried to delete non-existent file: {}", path);
    		}
    	}

    	// Snapshot
    	CycleContext context = new CycleContext(cycleName, score, "Updated: " + updatedFiles.keySet());
    	try {
    		Path snapshotPath = mutationSnapshotService.createSnapshot(context, Path.of("output"));
    		log.info("Snapshot saved at: {}", snapshotPath);
    	} catch (IOException ex) {
    		log.error("Failed to persist mutation snapshot", ex);
    	}

    	tracker.markAccepted();
    	
		log.info("Mutation accepted. Cycle '{}' scored {}",
    			cycleName,
    			score);

    	// Goal stack
    	handleGoalStackMutations(reply);

    	if (outcome.goalProgress() == GoalProgressState.FULFILLED) {
    		goalPlannerService.promoteCurrentGoal();
    		log.info("Promoted goal from active to completed.");
    	} else if (outcome.goalProgress() == GoalProgressState.STAGNATING) {
    		fileService.writeFile("rejected/rejection-feedback.txt", outcome.feedbackMessage());
    		log.warn("Goal stagnation feedback saved.");
    	    if (outcome.recommendation() != null && !outcome.recommendation().isBlank()) {
    	        fileService.writeFile("rejected/recommendation.txt", outcome.recommendation());
    	        log.info("Saved recommendation for next cycle.");
    	    }
    	}
    }

    
    private void handleGoalStackMutations(String reply) {
        ResponseParser.extractGoalStackDirectives(reply).ifPresent(directives -> {
            goalPlannerService.applyGoalStackDirectives(directives);
        });
    }

    public void promoteToReference() throws IOException {
        fileService.copyFolder("output", "reference");
        log.info("Promoted output/ to reference/");
    }
    
    private String getNextCycleName() {
        String timestamp = Instant.now().toString().replace(":", "-");
        return "cycle-" + timestamp;
    }
    
    private void logRejection(String cycleName, String reason, double score, Double lastAcceptedScore, String recommendation) {
        try {
            Map<String, Object> rejection = new HashMap<>();
            rejection.put("cycle", cycleName);
            rejection.put("reason", reason);
            rejection.put("score", score);
            rejection.put("lastAcceptedScore", lastAcceptedScore);
            rejection.put("timestamp", Instant.now().toString());
            if (recommendation != null && !recommendation.isBlank()) {
                rejection.put("recommendation", recommendation);
            }

            Path rejectLog = fileService.resolveSecurePath("logs/rejections-log.jsonl");
            fileService.writeString(
                rejectLog,
                mapper.writeValueAsString(rejection) + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND
            );
            log.warn("Rejection logged for cycle {}: {}", cycleName, reason);
        } catch (IOException e) {
            log.error("Failed to write rejection log", e);
        }
    }

}
