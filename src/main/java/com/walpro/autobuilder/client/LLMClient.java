package com.walpro.autobuilder.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.walpro.autobuilder.client.dto.ChatRequest;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;

import java.util.List;

@Slf4j
@Component
public class LLMClient {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${openai.api.key}")
    private String apiKey;

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    
    @Value("${openai.api.model:gpt-4o}")
    private String model;

    public ResponseEntity<ChatResponse> ask(List<Message> conversation) {
        ChatRequest request = new ChatRequest(
                model,
                conversation,
                0.0
        );
        

        // Log request payload
        log.info("Sending request to LLM (core={}):", model);
        for (Message message : conversation) {
            String prefix = switch (message.getRole()) {
                case "system" -> "[SYS]";
                case "user" -> "[USR]";
                case "assistant" -> "[ASSIST]";
                default -> "[???]";
            };

            String content = message.getContent();

            log.info("{}\n{}\n{}", prefix, "-----", content);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<ChatRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<ChatResponse> response = restTemplate.exchange(
                API_URL,
                HttpMethod.POST,
                entity,
                ChatResponse.class
        );
        
        // Log response content
        if (response.getBody() != null && response.getBody().getFirstContent() != null) {
            String reply = response.getBody().getFirstContent();
            log.info("[LLM RESPONSE]\n{}\n{}", "-----", reply);

        } else {
            log.warn("[LLM RESPONSE] Empty or null");
        }

        return response;
    }
}
