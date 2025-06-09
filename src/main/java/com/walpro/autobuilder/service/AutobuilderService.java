//package com.walpro.autobuilder.service;
//
//import java.util.List;
//import java.util.concurrent.Executors;
//
//import org.springframework.http.ResponseEntity;
//import org.springframework.stereotype.Service;
//
//import com.walpro.autobuilder.client.LLMClient;
//import com.walpro.autobuilder.client.dto.ChatResponse;
//import com.walpro.autobuilder.client.dto.Message;
//import com.walpro.autobuilder.event.FileChangeEvent;
//
//import jakarta.annotation.PostConstruct;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//
//@Slf4j
//@Service
//@RequiredArgsConstructor
//public class AutobuilderService {
//    private final FileWatcherService fileWatcherService;
//    private final FileService fileService;
//    private final LLMClient llmClient;
//
//    @PostConstruct
//    public void init() {
//        Executors.newSingleThreadExecutor().submit(this::processLoop);
//    }
//
//    private void processLoop() {
//        while (true) {
//            List<FileChangeEvent> events = fileWatcherService.drainEvents();
//            for (FileChangeEvent event : events) {
//                if (event.getPath().contains("/input/")) {
//                    handleInputFile(event);
//                }
//            }
//            try {
//                Thread.sleep(2000); // short delay to avoid busy loop
//            } catch (InterruptedException e) {
//                Thread.currentThread().interrupt();
//            }
//        }
//    }
//
//    private void handleInputFile(FileChangeEvent event) {
//        try {
//            String content = fileService.readFile(event.getPath());
//            Message systemPrompt = new Message("system", "You are an autonomous application builder...");
//            Message userPrompt = new Message("user", "Here's a new input file:\n\n" + content);
//
//            ResponseEntity<ChatResponse> response = llmClient.ask(List.of(systemPrompt, userPrompt));
//            String reply = response.getBody().getReply();
//
//            // Save to output or execute depending on LLM output format
//            String outputPath = "output/result_" + System.currentTimeMillis() + ".txt";
//            fileService.writeFile(outputPath, reply);
//        } catch (Exception e) {
//            log.error("Failed to process input file event", e);
//        }
//    }
//}

package com.walpro.autobuilder.service;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.event.FileChangeEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutobuilderService {

    private final FileWatcherService fileWatcherService;
    private final FileService fileService;
    private final LLMClient llmClient;

    private final List<Message> conversation = new ArrayList<>();

    @PostConstruct
    public void initConversation() {
        conversation.add(new Message("system", "You are Autobuilder, an autonomous development agent."));
    }

//    @Scheduled(fixedDelay = 5000)
//    public void processChanges() {
//        List<FileChangeEvent> events = drainEvents();
//        if (events.isEmpty()) return;
//
//        String summary = summarize(events);
//        log.info("Processing changes: {}", summary);
//
//        Message userMessage = new Message("user", summary);
//        conversation.add(userMessage);
//
//        String reply = getReply();
//        log.info("LLM replied:\n{}", reply);
//    }
    

//    @Scheduled(fixedDelay = 5000)
//    public void processChanges() {
//        List<FileChangeEvent> events = drainEvents();
//
//        if (events.isEmpty()) return;
//
//        log.info("Processing {} file event(s)...", events.size());
//
//        for (FileChangeEvent event : events) {
//            String prompt = "File change detected: " + event.getKind() + " on " + event.getPath();
//            conversation.add(new Message("user", prompt));
//        }
//
//        try {
//            ChatResponse response = llmClient.ask(conversation).getBody();
//
//            if (response != null && response.getChoices() != null && !response.getChoices().isEmpty()) {
//                String reply = response.getChoices().get(0).getMessage().getContent();
//                log.info("LLM reply: {}", reply);
//                conversation.add(new Message("assistant", reply));
//            } else {
//                log.warn("Empty or null response from LLM.");
//            }
//        } catch (Exception ex) {
//            log.error("Error calling LLM: {}", ex.getMessage(), ex);
//        }
//    }
    
    @Scheduled(fixedDelay = 5000)
    public void processChanges() {
        List<FileChangeEvent> events = drainEvents();

        if (events.isEmpty()) return;

        log.info("Detected {} event(s).", events.size());

        for (FileChangeEvent event : events) {
            try {
                // Only act on file creations/modifications (ignore directory changes for now)
                if (!event.getKind().equals("ENTRY_CREATE") && !event.getKind().equals("ENTRY_MODIFY")) continue;

                String content = fileService.readFile(event.getPath());
                String prompt = String.format("File '%s' was %s. Contents:\n%s\n\nWhat should be done?", 
                        event.getPath(), event.getKind(), content);

                conversation.add(new Message("user", prompt));

                ChatResponse response = llmClient.ask(conversation).getBody();

                if (response != null && response.getChoices() != null && !response.getChoices().isEmpty()) {
                    String reply = response.getChoices().get(0).getMessage().getContent();
                    log.info("LLM response: {}", reply);
                    conversation.add(new Message("assistant", reply));
                } else {
                    log.warn("Empty or null response from LLM.");
                }

            } catch (Exception ex) {
                log.error("Error processing file event for {}: {}", event.getPath(), ex.getMessage());
            }
        }
    }

    private List<FileChangeEvent> drainEvents() {
        List<FileChangeEvent> drained = new ArrayList<>();
        FileChangeEvent event;
        while ((event = fileWatcherService.pollEvent()) != null) {
            drained.add(event);
        }
        return drained;
    }

    private String summarize(List<FileChangeEvent> events) {
        StringBuilder builder = new StringBuilder("The following file events occurred:\n");
        for (FileChangeEvent event : events) {
            builder.append("- ").append(event.getKind()).append(": ").append(event.getPath()).append("\n");
        }
        return builder.toString();
    }

    private String getReply() {
        return llmClient.ask(conversation)
                .getBody()
                .getFirstContent();
    }
    
    public void runAutobuilder() throws IOException {
        List<FileChangeEvent> events = drainEvents();
        if (events.isEmpty()) {
            return;
        }

        for (FileChangeEvent event : events) {
            String content = fileService.readFile(event.getPath());
            Message userMessage = new Message("user", content);
            ChatResponse chatResponse = llmClient.ask(List.of(userMessage)).getBody();

            if (chatResponse != null) {
                String reply = chatResponse.getFirstContent();
                String outPath = event.getPath() + ".reply.txt";
                fileService.writeFile(outPath, reply);
            }
        }
    }
}
