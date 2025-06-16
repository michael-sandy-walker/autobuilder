package com.walpro.autobuilder.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.analyzer.DefaultProjectStructureSummarizer;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.diff.DiffPromptFormatter;
import com.walpro.autobuilder.diff.DiffReport;
import com.walpro.autobuilder.diff.DiffService;
import com.walpro.autobuilder.event.FileChangeEvent;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.heuristic.DefaultMutationHeuristicAnalyzer;
import com.walpro.autobuilder.heuristic.HeuristicFinding;
import com.walpro.autobuilder.heuristic.MutationHeuristicAnalyzer;
import com.walpro.autobuilder.heuristic.ScoreTracker;
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

    private final FileWatcherService fileWatcherService;
    private final FileService fileService;
    private final LLMClient llmClient;
    private final DiffService diffService;
    private final DefaultProjectStructureSummarizer projectStructureSummarizer;
    private final MutationHeuristicAnalyzer mutationHeuristicAnalyzer;
    private final MutationSnapshotService mutationSnapshotService;
    private final GoalPlannerService goalPlannerService;


    private static final double HEURISTIC_THRESHOLD = 0.4;
    private static final ObjectMapper mapper = new ObjectMapper();

    boolean isBootstrapping = false;

    @PostConstruct
    public void initializeProjectFolders() throws IOException {
        List<String> folders = List.of("reference", "last-evolution", "output", "logs", "responses/bootstrap", "responses/evolve");
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
            ProjectInput input = new ProjectInput(
                diffReport,
                projectSummary,
                previousOutput,
                fileService.resolveSecurePath("output"),
                goalPlannerService.getCurrentGoal()
            );
            String cycleName = getNextCycleName();
            List<Message> conversation = buildConversation(input, cycleName, false);

            ChatResponse response = llmClient.ask(conversation).getBody();

            if (response != null && response.getFirstContent() != null) {
            	String timestamp = Instant.now().toString().replace(":", "-");
                String path = "responses/evolve/evolve-" + timestamp + ".reply.txt";
                fileService.writeFile(path, response.getFirstContent());
                log.info("Wrote evolve response to {}", path);

                handleLLMReplyWithHeuristics(cycleName, response.getFirstContent(), path, previousOutput);
            }

        } catch (Exception e) {
            log.error("Evolution failed", e);
        }
    }

    private List<Message> buildConversation(ProjectInput input, String cycleName, boolean isBootstrap) {
        String sysPrompt = ProtocolContract.getSystemPrompt(isBootstrap);        
        String userPrompt;
        if (isBootstrap) {
            userPrompt = input.structureSummary();
        } else {
            String goal = goalPlannerService.getCurrentGoal();
            String diffSummary = DiffPromptFormatter.format(input.diff());
            String structure = input.structureSummary();

            userPrompt = """
                --- PROJECT GOAL ---
                %s

                --- DIFF SUMMARY ---
                %s

                --- CURRENT STRUCTURE ---
                %s
                """.formatted(goal, diffSummary, structure);
        }

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

    private void handleLLMReplyWithHeuristics(String cycleName, String reply, String fallbackPath, Map<String, String> previousOutput) throws IOException {
        Map<String, String> updatedFiles = ResponseParser.extractFiles(reply);
        Set<String> deletedFiles = ResponseParser.extractDeletions(reply);
        if (updatedFiles.isEmpty() && deletedFiles.isEmpty()) {
            fileService.writeFile(fallbackPath, reply);
            log.warn("No files extracted. Wrote fallback: {}", fallbackPath);
            return;
        }

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
        Path logPath = fileService.resolveSecurePath("logs/evolution-log.jsonl");
        fileService.writeString(logPath,
                mapper.writeValueAsString(logEntry) + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        // Absolute threshold
        if (score < HEURISTIC_THRESHOLD) {
            log.warn("Mutation rejected: score {} is below threshold {}", score, HEURISTIC_THRESHOLD);
            return;
        }
        
        // Relative regression pruning
        ScoreTracker tracker = new ScoreTracker(fileService);
        Double lastScore = tracker.getLastAcceptedScore();
        double scoreDropLimit = 0.2; // optional: configurable

        if (lastScore != null && score < lastScore - scoreDropLimit) {
        	log.warn("Pruning: score dropped by {} (from {} to {})",
        		    String.format("%.2f", (lastScore - score)),
        		    String.format("%.2f", lastScore),
        		    String.format("%.2f", score));
            return;
        }

        for (var entry : updatedFiles.entrySet()) {
            String path = "output/" + entry.getKey();
            fileService.writeFile(path, entry.getValue());
            log.info("Wrote file: {}", path);
        }
        
        CycleContext context = new CycleContext(cycleName, score, "Updated: " + updatedFiles.keySet());
        try {
            Path snapshotPath = mutationSnapshotService.createSnapshot(context, Path.of("output"));
            log.info("Snapshot saved at: {}", snapshotPath);
        } catch (IOException ex) {
            log.error("Failed to persist mutation snapshot", ex);
        }
        
        log.info("Cycle '{}' scored {}",
        	    cycleName,
        	    score);
    }

    private List<FileChangeEvent> drainEvents() {
        List<FileChangeEvent> events = new ArrayList<>();
        FileChangeEvent event;
        while ((event = fileWatcherService.pollEvent()) != null) {
            events.add(event);
        }
        return events;
    }

    private String summarizeOutputFolder() throws IOException {
        return projectStructureSummarizer.summarizeProjectStructure(
                fileService.resolveSecurePath("output")
        );
    }

    public void promoteToReference() throws IOException {
        fileService.copyFolder("output", "reference");
        log.info("Promoted output/ to reference/");
    }
    
    private String getNextCycleName() {
        String timestamp = Instant.now().toString().replace(":", "-");
        return "cycle-" + timestamp;
    }
}
