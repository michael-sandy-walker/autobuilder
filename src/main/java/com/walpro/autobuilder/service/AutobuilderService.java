package com.walpro.autobuilder.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.walpro.autobuilder.analyzer.DefaultProjectStructureSummarizer;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.event.FileChangeEvent;
import com.walpro.autobuilder.model.FileInfo;
import com.walpro.autobuilder.parser.ResponseParser;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

//@Slf4j
//@Service
//@RequiredArgsConstructor
//public class AutobuilderService {
//
// private final FileWatcherService fileWatcherService;
// private final FileService fileService;
// private final LLMClient llmClient;
//
// @Autowired
// private DefaultProjectStructureSummarizer projectStructureSummarizer;
//
// @PostConstruct
// public void bootstrap() throws IOException {
//     Path seedPath = fileService.resolveSecurePath("bootstrap/seed.txt");
//     if (!Files.exists(seedPath)) {
//         log.info("No seed file found at bootstrap/seed.txt. Skipping bootstrap.");
//         return;
//     }
//
//     try {
//         String seedPrompt = fileService.readFile("bootstrap/seed.txt").trim();
//         if (seedPrompt.isEmpty()) {
//             log.warn("Seed prompt is empty.");
//             return;
//         }
//
//         String projectSummary = projectStructureSummarizer
//                 .summarizeProjectStructure(fileService.resolveSecurePath("output"));
//
//         List<Message> conversation = buildConversationPrompt(seedPrompt, projectSummary, true);
//
//         log.info("Running bootstrap prompt.");
//         ChatResponse response = llmClient.ask(conversation).getBody();
//
//         if (response != null && response.getFirstContent() != null) {
//             String reply = response.getFirstContent();
//             fileService.writeFile("bootstrap/output/seed.reply.txt", reply);
//             log.info("Wrote bootstrap response to bootstrap/output/seed.reply.txt");
//
//             handleLLMResponse(reply, "output/");
//         } else {
//             log.warn("Received no response during bootstrap.");
//         }
//
//     } catch (Exception e) {
//         log.error("Error during bootstrap: {}", e.getMessage(), e);
//     }
// }
//
// @Scheduled(fixedDelay = 5000)
// public void processChanges() {
//     try {
//         processAutonomousChanges();
//     } catch (Exception e) {
//         log.error("Autonomous change processing failed", e);
//     }
// }
//
// public void processAutonomousChanges() throws IOException {
//     List<FileChangeEvent> events = drainEvents();
//     if (events.isEmpty()) return;
//
//     String projectSummary = projectStructureSummarizer
//             .summarizeProjectStructure(fileService.resolveSecurePath("output"));
//
//     for (FileChangeEvent event : events) {
//         String content = fileService.readFile(event.getPath());
//         Path originalPath = Path.of(event.getPath());
//         String baseName = originalPath.getFileName().toString();
//
//         String userPrompt = """
//             A change occurred in file: %s
//
//             Content of the changed file:
//             ---
//             %s
//             ---
//
//             Current project structure:
//             ---
//             %s
//             ---
//
//             What should be added or changed next?
//             Respond only with code blocks of the form:
//             ```java [relative/path/to/File.java]
//             // content
//             ```
//             """.formatted(baseName, content, projectSummary);
//
//         List<Message> conversation = buildConversationPrompt(userPrompt, null, false);
//
//         ChatResponse chatResponse = llmClient.ask(conversation).getBody();
//         if (chatResponse != null && chatResponse.getFirstContent() != null) {
//             String reply = chatResponse.getFirstContent();
//             handleLLMResponse(reply, "output/" + baseName);
//         } else {
//             log.warn("No reply from LLM for file change event.");
//         }
//     }
// }
//
// @Scheduled(fixedDelay = 300_000)
// public void evolveProject() {
//     try {
//         List<FileInfo> projectFiles = fileService.listFiles("output");
//         StringBuilder contextBuilder = new StringBuilder("The current project files are:\n");
//         for (FileInfo file : projectFiles) {
//             contextBuilder.append("- ").append(file.getName()).append("\n");
//         }
//
//         String projectSummary = projectStructureSummarizer
//                 .summarizeProjectStructure(fileService.resolveSecurePath("output"));
//
//         String combinedPrompt = contextBuilder + "\n\nCurrent project structure:\n---\n" +
//                 projectSummary + "\n---\n\nSuggest improvements or additions to the codebase.";
//
//         List<Message> conversation = buildConversationPrompt(combinedPrompt, null, false);
//
//         ChatResponse response = llmClient.ask(conversation).getBody();
//         if (response != null && response.getFirstContent() != null) {
//             handleLLMResponse(response.getFirstContent(), "output/evolve");
//         }
//
//     } catch (Exception e) {
//         log.error("Evolution step failed: {}", e.getMessage(), e);
//     }
// }
//
// private List<Message> buildConversationPrompt(String userPrompt, String projectStructure, boolean isBootstrap) {
//     String systemPrompt = isBootstrap
//             ? """
//                 You are Autobuilder, an autonomous code generation agent.
//                 When asked to build or update software, respond only with code blocks containing file paths and their content.
//                 Use this format for each file:
//                 ```java [src/main/java/com/example/MyClass.java]
//                 public class MyClass {
//                 }
//                 Do not explain. Just output code files in this format.
//               """
//             : """
//                 You are an autonomous software agent helping to evolve Java Spring Boot codebases.
//                 Always respond ONLY with code blocks. Each block MUST start with:
//                 ```java [relative/path/to/file.ext]
//                 <file contents>
//                 Avoid explanations, headers, or non-code text. Do not repeat filenames outside code blocks. No bullet points. No text before or after the code blocks.
//               """;
//
//     List<Message> conversation = new ArrayList<>();
//     conversation.add(new Message("system", systemPrompt));
//     conversation.add(new Message("user", userPrompt));
//     return conversation;
// }
//
// private void handleLLMResponse(String reply, String fallbackPathPrefix) throws IOException {
//     Map<String, String> files = ResponseParser.extractFiles(reply);
//     if (files.isEmpty()) {
//         String fallbackPath = fallbackPathPrefix.endsWith(".txt")
//                 ? fallbackPathPrefix
//                 : fallbackPathPrefix + ".reply.txt";
//         log.warn("No files detected in reply. Writing fallback.");
//         fileService.writeFile(fallbackPath, reply);
//         log.info("Fallback: LLM reply written to {}", fallbackPath);
//     } else {
//         for (Map.Entry<String, String> entry : files.entrySet()) {
//             String targetPath = "output/" + entry.getKey();
//             fileService.writeFile(targetPath, entry.getValue());
//             log.info("Wrote file: {}", targetPath);
//         }
//     }
// }
//
// private List<FileChangeEvent> drainEvents() {
//     List<FileChangeEvent> drained = new ArrayList<>();
//     FileChangeEvent event;
//     while ((event = fileWatcherService.pollEvent()) != null) {
//         drained.add(event);
//     }
//     return drained;
// }
//}

import com.walpro.autobuilder.analyzer.DefaultProjectStructureSummarizer;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.event.FileChangeEvent;
import com.walpro.autobuilder.model.FileInfo;
import com.walpro.autobuilder.parser.ResponseParser;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutobuilderService {

    private final FileWatcherService fileWatcherService;
    private final FileService fileService;
    private final LLMClient llmClient;

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
    }

    @Scheduled(fixedDelay = 5000)
    public void processChanges() {
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

                        Project structure:
                        ---
                        %s
                        ---

                        Suggest improvements as code blocks only:
                        ```java [relative/path/to/File.java]
                        // content
                        ```
                        """.formatted(fileName, content, projectSummary);

                List<Message> convo = buildConversation(prompt, null, false);
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
        try {
            String prompt = "Suggest improvements to the project.";
            String projectSummary = projectStructureSummarizer
                    .summarizeProjectStructure(fileService.resolveSecurePath("output"));

            List<Message> conversation = buildConversation(prompt, projectSummary, false);
            ChatResponse response = llmClient.ask(conversation).getBody();

            if (response != null && response.getFirstContent() != null) {
                handleLLMReply(response.getFirstContent(), "output/evolve");
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
        String usrPrompt = """
                %s

                Current project structure:
                ---
                %s
                ---
                """.formatted(userPrompt, structure);

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

    private List<FileChangeEvent> drainEvents() {
        List<FileChangeEvent> events = new ArrayList<>();
        FileChangeEvent event;
        while ((event = fileWatcherService.pollEvent()) != null) {
            events.add(event);
        }
        return events;
    }
    
    private String summarizeOutputFolder() {
        return projectStructureSummarizer.summarizeProjectStructure(
                fileService.resolveSecurePath("output")
        );
    }
}

