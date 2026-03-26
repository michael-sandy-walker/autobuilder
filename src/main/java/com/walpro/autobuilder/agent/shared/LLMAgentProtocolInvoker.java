package com.walpro.autobuilder.agent.shared;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

@Component
public class LLMAgentProtocolInvoker {

    private static final int MAX_ATTEMPTS = 2;

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
                                    "Your response did not match the required format. " +
                                    "Respond ONLY with the exact structured format described in the instructions. " +
                                    "Do not add any explanation, preamble, or prose."));

            ChatResponse response = llmClient.ask(messages).getBody();

            content = response != null ? response.getFirstContent() : null;

            if (content != null && isValidResponse.test(content)) {
                return LLMResponseResult.success(parseResponse.apply(content), content);
            }
        }

        String failureReason = content == null
                ? "LLM response was null"
                : "Response did not match protocol";

        return LLMResponseResult.failure(content, failureReason);
    }
}
