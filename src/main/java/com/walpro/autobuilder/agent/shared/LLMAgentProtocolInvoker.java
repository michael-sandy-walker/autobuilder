package com.walpro.autobuilder.agent.shared;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

@Component
@com.walpro.autobuilder.core.checkpoint.StateHolder(
        value = com.walpro.autobuilder.core.checkpoint.StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "per-role count of consecutive discarded answers, for the discard event only")
public class LLMAgentProtocolInvoker {

    private static final int MAX_ATTEMPTS = 2;

    /** T5-BO: optional, so narrow unit tests need not wire it. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.observability.behaviour.BehaviourLogger behaviourLogger;

    /** Consecutive discards per role; a valid answer resets its role's count. */
    private final java.util.Map<String, Integer> consecutiveDiscards = new java.util.concurrent.ConcurrentHashMap<>();

    public <T> LLMResponseResult<T> callWithValidation(
            LLMClient llmClient,
            String systemPrompt,
            String userPrompt,
            Predicate<String> isValidResponse,
            Function<String, T> parseResponse
    ) {
        String content = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            List<Message> messages = attempt == 1
                    ? List.of(
                            new Message("system", systemPrompt),
                            new Message("user", userPrompt))
                    : List.of(
                            new Message("system", systemPrompt),
                            new Message("user", userPrompt),
                            new Message("assistant", content),
                            new Message("user",
                                    "Your previous response did not match the required format. " +
                                    "Respond ONLY with the exact structured format described in the instructions. " +
                                    "Do not add any explanation, preamble, or prose."));

            ChatResponse response = llmClient.ask(messages).getBody();

            content = response != null ? response.getFirstContent() : null;

            if (content != null && isValidResponse.test(content)) {
                consecutiveDiscards.remove(com.walpro.autobuilder.client.LlmCallContext.currentRole());
                return LLMResponseResult.success(parseResponse.apply(content), content);
            }
        }
        reportDiscard(content);

        String failureReason = content == null
                ? "LLM response was null"
                : "Response did not match protocol";

        return LLMResponseResult.failure(content, failureReason);
    }

    private void reportDiscard(String content) {
        String role = com.walpro.autobuilder.client.LlmCallContext.currentRole();
        int n = consecutiveDiscards.merge(role, 1, Integer::sum);
        if (behaviourLogger == null) return;
        String first = content == null ? "(no answer)" : content.strip().lines().findFirst().orElse("");
        if (first.length() > 120) first = first.substring(0, 120);
        behaviourLogger.llmResponseDiscarded(com.walpro.autobuilder.client.LlmCallContext.currentCycleId(),
                role, content == null ? "no answer" : "did not match the protocol", first, n);
    }
}
