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

    @Value("${llm.api.retry.max-attempts:3}")
    private int retryMaxAttempts;

    @Value("${llm.api.retry.delay-ms:5000}")
    private long retryDelayMs;

    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(readTimeoutMs);
        restTemplate = new RestTemplate(factory);
    }

    public ResponseEntity<ChatResponse> ask(List<Message> conversation) {
        // T1-FD: fail fast while the circuit is open. Run 4 (2026-07-02): after the breaker
        // opened, REST-path callers kept firing 2-minute-timeout requests for 2+ hours,
        // keeping the local model's queue saturated (the livelock's fuel). An open circuit
        // now rejects immediately on EVERY path; abstain-aware callers defer, and the timed
        // cool-down (see LlmCircuitBreaker) lets the next post-window call probe recovery.
        // Deliberately no recordNetworkFailure here — fast-fails must not extend the window.
        if (circuitBreaker.isOpen()) {
            String reason = circuitBreaker.pauseReason();
            log.warn("LLM call rejected — circuit open ({}). Failing fast.", reason);
            throw new ResourceAccessException("LLM circuit open — failing fast: " + reason);
        }
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

        Exception lastException = null;
        boolean lastWasNetwork = false;
        int lastHttpStatus = -1;
        String lastErrorCode = null;

        for (int attempt = 1; attempt <= retryMaxAttempts; attempt++) {
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
                // Never retry auth failures, quota exhaustion, or rate limits
                if (status == 401 || "insufficient_quota".equals(errorCode) || status == 429) {
                    circuitBreaker.recordFailure(status, errorCode);
                    throw e;
                }
                // 5xx — eligible for retry
                log.warn("LLM HTTP {} on attempt {}/{} — {}",
                        status, attempt, retryMaxAttempts,
                        attempt < retryMaxAttempts ? "retrying" : "giving up");
                lastException = e;
                lastWasNetwork = false;
                lastHttpStatus = status;
                lastErrorCode = errorCode;

            } catch (ResourceAccessException e) {
                log.warn("LLM network/timeout error on attempt {}/{}: {} — {}",
                        attempt, retryMaxAttempts, e.getMessage(),
                        attempt < retryMaxAttempts ? "retrying" : "giving up");
                lastException = e;
                lastWasNetwork = true;
            }

            if (attempt < retryMaxAttempts) {
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // All attempts exhausted — record one failure to the circuit breaker
        if (lastWasNetwork) {
            circuitBreaker.recordNetworkFailure();
        } else {
            circuitBreaker.recordFailure(lastHttpStatus, lastErrorCode);
        }
        if (lastException instanceof ResourceAccessException rae) throw rae;
        throw (HttpStatusCodeException) lastException;
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
