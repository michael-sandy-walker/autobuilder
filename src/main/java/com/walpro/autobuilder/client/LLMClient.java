package com.walpro.autobuilder.client;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private RestTemplate restTemplate;
    private final LlmCircuitBreaker circuitBreaker;

    @Value("${llm.api.url}")
    private String apiUrl;

    @Value("${llm.api.key}")
    private String apiKey;

    @Value("${llm.api.model}")
    private String model;

    @Value("${llm.api.read-timeout-ms:120000}")
    private int readTimeoutMs;

    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(readTimeoutMs);
        restTemplate = new RestTemplate(factory);
    }

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
            if (message.getRole().equals("system") || message.getRole().equals("assistant")) {
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
                    apiUrl, HttpMethod.POST, entity, ChatResponse.class);

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
        } catch (ResourceAccessException e) {
            circuitBreaker.recordNetworkFailure();
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
