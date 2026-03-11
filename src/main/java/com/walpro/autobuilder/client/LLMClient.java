package com.walpro.autobuilder.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.client.dto.ChatRequest;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LLMClient {

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate restTemplate = new RestTemplate();
    private final LlmCircuitBreaker circuitBreaker;

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.api.model:gpt-4o}")
    private String model;

    public ResponseEntity<ChatResponse> ask(List<Message> conversation) {
        ChatRequest request = new ChatRequest(model, conversation, 0.0);

        log.debug("Sending request to LLM (core={}):", model);
        for (Message message : conversation) {
            String prefix = switch (message.getRole()) {
                case "system" -> "[SYS]";
                case "user" -> "[USR]";
                case "assistant" -> "[ASSIST]";
                default -> "[???]";
            };
            String content = message.getContent();
            if (message.getRole().equals("system")) {
                log.debug("{}\n{}\n{}", prefix, "-----", content);
            } else {
                log.info("{}\n{}\n{}", prefix, "-----", content);
            }
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        HttpEntity<ChatRequest> entity = new HttpEntity<>(request, headers);

        try {
            ResponseEntity<ChatResponse> response = restTemplate.exchange(
                    API_URL, HttpMethod.POST, entity, ChatResponse.class);

            circuitBreaker.recordSuccess();

            if (response.getBody() != null && response.getBody().getFirstContent() != null) {
                log.info("[LLM RESPONSE]\n{}\n{}", "-----", response.getBody().getFirstContent());
            } else {
                log.warn("[LLM RESPONSE] Empty or null");
            }

            return response;

        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            String errorCode = extractErrorCode(e.getResponseBodyAsString());
            circuitBreaker.recordFailure(status, errorCode);
            throw e;
        }
    }

    private String extractErrorCode(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return null;
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode code = root.path("error").path("code");
            if (!code.isMissingNode() && !code.isNull()) return code.asText();
            JsonNode type = root.path("error").path("type");
            if (!type.isMissingNode() && !type.isNull()) return type.asText();
        } catch (Exception ignored) {
        }
        return null;
    }
}
