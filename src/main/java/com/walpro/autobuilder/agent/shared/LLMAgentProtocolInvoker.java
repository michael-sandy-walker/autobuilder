package com.walpro.autobuilder.agent.shared;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

public class LLMAgentProtocolInvoker {

    private static final int MAX_ATTEMPTS = 2;

    public static <T> LLMResponseResult<T> callWithValidation(
            LLMClient llmClient,
            String systemPrompt,
            String userPrompt,
            Predicate<String> isValidResponse,
            Function<String, T> parseResponse
    ) {
        String content = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            ChatResponse response = llmClient.ask(List.of(
                    new Message("system", systemPrompt),
                    new Message("user", userPrompt)
            )).getBody();

            content = response != null ? response.getFirstContent() : null;

            if (content != null && isValidResponse.test(content)) {
                return LLMResponseResult.success(parseResponse.apply(content), content);
            }
        }

        String failureReason = content == null
                ? "LLM response was null"
                : "Response did not match evaluation protocol";

        return LLMResponseResult.failure(content, failureReason);
    }
}
