package com.walpro.autobuilder.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.walpro.autobuilder.analyzer.DefaultProjectStructureSummarizer;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.diff.DiffPromptFormatter;
import com.walpro.autobuilder.diff.DiffReport;
import com.walpro.autobuilder.event.FileChangeEvent;
import com.walpro.autobuilder.heuristic.HeuristicFinding;
import com.walpro.autobuilder.heuristic.MutationHeuristicAnalyzer;
import com.walpro.autobuilder.parser.ResponseParser;

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
    private final MutationHeuristicAnalyzer mutationHeuristicAnalyzer;
    
    boolean isBootstrapping = false;

    @Autowired
    private DefaultProjectStructureSummarizer projectStructureSummarizer;
    
    @PostConstruct
    public void initializeProjectFolders() throws IOException {
        List<String> folders = List.of("reference", "last-evolution", "output");
        for (String dir : folders) {
            Path path = fileService.resolveSecurePath(dir);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
                log.info("Created project directory: {}", path);
            }
        }
    }

    @PostConstruct
    public void bootstrap() throws IOException {
    	isBootstrapping = true;
        Path seedPath = fileService.resolveSecurePath("bootstrap/seed.txt");
        if (!Files.exists(seedPath)) {
            log.info("No seed file found at bootstrap/seed.txt. Skipping bootstrap.");
            return;
        }

        try {
            String seedPrompt = fileService.readFile("bootstrap/seed.txt").trim();
            if (seedPrompt.isEmpty()) {
                log.warn("Seed prompt is empty.");
                return;
            }

            String projectSummary = summarizeOutputFolder();

            List<Message> conversation = buildConversation(seedPrompt, projectSummary, true);
            ChatResponse response = llmClient.ask(conversation).getBody();

            if (response != null && response.getFirstContent() != null) {
                String reply = response.getFirstContent();
                fileService.writeFile("bootstrap/output/seed.reply.txt", reply);
                log.info("Wrote bootstrap response to bootstrap/output/seed.reply.txt");
                handleLLMReply(reply, "output/");
            } else {
                log.warn("No bootstrap response.");
            }

        } catch (Exception e) {
            log.error("Bootstrap failed", e);
        }
        isBootstrapping = false;
    }

//    @Scheduled(fixedDelay = 5000) // TODO: This is currently commented out, because it does not only "filter" out manually changed files, but also files changed by Autobuilder itself
    public void processAutonomousChanges() {
    	if  (isBootstrapping) {
    		return;
    	}
        try {
            List<FileChangeEvent> events = drainEvents();
            if (events.isEmpty()) return;

            String projectSummary = projectStructureSummarizer
                    .summarizeProjectStructure(fileService.resolveSecurePath("output"));

            for (FileChangeEvent event : events) {
                String content = fileService.readFile(event.getPath());
                String fileName = Path.of(event.getPath()).getFileName().toString();

                String prompt = """
                        A change occurred in file: %s

                        Content:
                        ---
                        %s
                        ---
                        """.formatted(fileName, content);

                List<Message> convo = buildConversation(prompt, projectSummary, false);
                ChatResponse response = llmClient.ask(convo).getBody();

                if (response != null && response.getFirstContent() != null) {
                    handleLLMReply(response.getFirstContent(), "output/" + fileName);
                }
            }

        } catch (Exception e) {
            log.error("Change processing failed", e);
        }
    }

    @Scheduled(fixedDelay = 300_000)
    public void evolveProject() {
    	if  (isBootstrapping) {
    		return;
    	}
        try {
            // Step 1: Summarize current project structure
            String projectSummary = projectStructureSummarizer
                    .summarizeProjectStructure(fileService.resolveSecurePath("output"));

            // Step 2: Compute and format diff
            DiffReport diffReport = diffService.compareFolders(
                    fileService.resolveSecurePath("last-evolution").toAbsolutePath().toString(),
                    fileService.resolveSecurePath("output").toAbsolutePath().toString()
            );
            String diffPrompt = DiffPromptFormatter.formatForLLM(diffReport, projectSummary);
            
            // Step 3: Backup current output to last-evolution
            fileService.copyFolder("output", "last-evolution");
            log.info("Backed up current output/ to last-evolution/");

            // Step 4: Combine prompt
            String prompt = """
                Suggest improvements to the project.

                Code changes since last evolution:
                ---
                %s
                ---

                Project structure:
                ---
                %s
                ---
                """.formatted(diffPrompt, projectSummary);

            // Step 5: LLM call
            List<Message> conversation = buildConversation(prompt, "", false);
            ChatResponse response = llmClient.ask(conversation).getBody();
            
            Map<String, String> previousOutput = fileService.readFolderContents("output");

            if (response != null && response.getFirstContent() != null) {
                handleLLMReplyWithHeuristics(response.getFirstContent(), "output/evolve", previousOutput);
            }

        } catch (Exception e) {
            log.error("Evolution failed", e);
        }
    }


    private List<Message> buildConversation(String userPrompt, String structure, boolean isBootstrap) {
        String sysPrompt = isBootstrap
                ? """
                You are Autobuilder, an autonomous code generation agent.
                Only respond with code blocks, using this format:
                ```java [path/to/File.java]
                // content
                ```
                Do not explain anything.
                """
                : """
                You are an autonomous software agent evolving a Java Spring Boot project.
                Only respond with code blocks:
                ```java [relative/path/to/file]
                // code
                ```
                No explanation, no text outside code blocks.
                """;       
        String usrPrompt = StringUtils.isNotBlank(structure) ? """
                %s

                Current project structure:
                ---
                %s
                ---
                """.formatted(userPrompt, structure) : userPrompt;

        List<Message> result = new ArrayList<>();
        result.add(new Message("system", sysPrompt));
        result.add(new Message("user", usrPrompt));
        return result;
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
    
    private void handleLLMReplyWithHeuristics(String reply, String fallbackPrefix, Map<String, String> previousOutput) throws IOException {
        Map<String, String> mutatedFiles = ResponseParser.extractFiles(reply);
        if (mutatedFiles.isEmpty()) {
            String fallback = fallbackPrefix.endsWith(".txt") ? fallbackPrefix : fallbackPrefix + ".reply.txt";
            fileService.writeFile(fallback, reply);
            log.warn("No files extracted. Wrote fallback: {}", fallback);
        } else {
            // Heuristic evaluation
            List<HeuristicFinding> findings = mutationHeuristicAnalyzer.analyze(mutatedFiles, previousOutput);
            for (HeuristicFinding finding : findings) {
                log.warn("Heuristic issue: [{}] {} - {}", finding.type(), finding.path(), finding.description());
            }

            for (var entry : mutatedFiles.entrySet()) {
                String path = "output/" + entry.getKey();
                fileService.writeFile(path, entry.getValue());
                log.info("Wrote file: {}", path);
            }
        }
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
}

