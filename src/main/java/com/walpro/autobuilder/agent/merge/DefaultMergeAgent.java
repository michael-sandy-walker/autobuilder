package com.walpro.autobuilder.agent.merge;

import com.walpro.autobuilder.agent.merge.model.MergeInput;
import com.walpro.autobuilder.agent.merge.model.MergeOutcome;
import com.walpro.autobuilder.agent.merge.protocol.MergeProtocolContract;
import com.walpro.autobuilder.agent.merge.protocol.MergeResponseParser;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultMergeAgent implements MergeAgent {

    private final LLMClient llmClient;
    private final MergeResponseParser parser = new MergeResponseParser();

    @Override
    public MergeOutcome merge(MergeInput input) {
        String systemPrompt = MergeProtocolContract.getSystemPrompt();
        String userPrompt = MergeProtocolContract.getUserPrompt(input.path(), input.baseContent(), input);

        log.info("🧠 Submitting merge request to LLM for {}", input.path());

        List<Message> conversation = List.of(
                new Message("system", systemPrompt),
                new Message("user", userPrompt)
        );

        ResponseEntity<ChatResponse> response = llmClient.ask(conversation);
        ChatResponse body = response.getBody();

        if (body == null || body.getFirstContent() == null) {
            return new MergeOutcome(false, input.baseContent(), "Empty or null LLM response");
        }

        String content = body.getFirstContent();

        if (!MergeProtocolContract.isValidResponse(content)) {
            return new MergeOutcome(false, input.baseContent(), "Invalid merge response format");
        }

        return parser.parse(content);
    }
}
