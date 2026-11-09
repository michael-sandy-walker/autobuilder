package com.walpro.autobuilder.client;

import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * T4-EM: the narrator's model channel, configured under {@code llm.narrator.*} and NOT inherited from the loop's
 * {@code llm.api.*}. The user, 2026-11-09: "when we use a third-party API for the main-LLM-calls, the narrator-calls
 * should still use qwen3.8. In short: It should just be separate, but we should still be able of using the same
 * (cached) model."
 *
 * <p>Same model today = the same endpoint and model name as the loop. The request carries nothing that makes the
 * server load the model differently (no context size, no load options; the shape is {@link LLMClient}'s), so one
 * loaded copy serves both.
 *
 * <p>Its own {@link LlmCircuitBreaker} instance, not the bean: a narrator endpoint that is down or slow opens this
 * breaker only, and the loop -- which pauses on the bean's breaker -- never learns of it. Never throws: a failed
 * narration is an empty answer.
 */
@Slf4j
@Component
public class NarratorChannel {

    /** The role the narrator's calls are counted and logged under. */
    public static final String ROLE = "narrator";

    private final boolean enabled;
    private final LLMClient client;
    private final LlmCircuitBreaker breaker;

    @Autowired
    public NarratorChannel(@Value("${llm.narrator.enabled:false}") boolean enabled,
                           @Value("${llm.narrator.url:http://localhost:11434/v1/chat/completions}") String url,
                           @Value("${llm.narrator.key:}") String key,
                           @Value("${llm.narrator.model:qwen3.8}") String model,
                           @Value("${llm.narrator.read-timeout-ms:120000}") int readTimeoutMs,
                           @Value("${llm.narrator.reasoning-effort:none}") String reasoningEffort,
                           @Value("${llm.narrator.context-window-tokens:32768}") int contextWindowTokens,
                           @Value("${llm.narrator.response-reserve-tokens:512}") int responseReserveTokens,
                           @Autowired(required = false) LlmUsageTracker usageTracker,
                           @Autowired(required = false) BehaviourLogger behaviourLogger) {
        this.enabled = enabled;
        this.breaker = new LlmCircuitBreaker();
        this.client = enabled ? LLMClient.forChannel(breaker, url, key, model, readTimeoutMs, reasoningEffort,
                contextWindowTokens, responseReserveTokens) : null;
        if (client != null) {
            client.setUsageTracker(usageTracker);
            client.setBehaviourLogger(behaviourLogger);
            log.info("T4-EM: narrator channel on -- model {} at {}", model, url);
        }
    }

    /** Visible for testing: a channel over a client built elsewhere, with the breaker that client was built with. */
    NarratorChannel(LLMClient client, LlmCircuitBreaker breaker) {
        this.enabled = client != null;
        this.client = client;
        this.breaker = breaker;
    }

    public boolean enabled() {
        return enabled;
    }

    public String model() {
        return client == null ? null : client.model();
    }

    /** The narrator's breaker -- this channel's own, never the loop's. */
    LlmCircuitBreaker breaker() {
        return breaker;
    }

    /** The model's answer to {@code system} + {@code user}, or empty when off, failing, or blank. */
    public Optional<String> ask(String system, String user, String cycleId) {
        if (client == null) return Optional.empty();
        try (LlmCallContext.Scope ignored = LlmCallContext.scope(ROLE, cycleId)) {
            ResponseEntity<ChatResponse> r = client.ask(List.of(new Message("system", system), new Message("user", user)));
            String text = r == null || r.getBody() == null ? null : r.getBody().getFirstContent();
            return text == null || text.isBlank() ? Optional.empty() : Optional.of(text.trim());
        } catch (RuntimeException e) {
            log.info("T4-EM: no narration for {} -- {}", cycleId, e.getMessage());
            return Optional.empty();
        }
    }
}
