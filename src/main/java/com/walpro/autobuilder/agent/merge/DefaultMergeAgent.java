package com.walpro.autobuilder.agent.merge;

import com.walpro.autobuilder.agent.merge.graph.MergeActionRegistrar;
import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.agent.merge.model.MergeOutcome;
import com.walpro.autobuilder.agent.merge.protocol.MergeProtocolContract;
import com.walpro.autobuilder.agent.merge.protocol.MergeResponseParser;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.lifecycle.CycleContextData;

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
    private final MergeActionRegistrar mergeActionRegistrar;

    @Override
    public MergeOutcome merge(String path, String baseContent, List<FileVariant> variants, CycleContextData context) {
        String systemPrompt = MergeProtocolContract.getSystemPrompt();
        String userPrompt = MergeProtocolContract.getUserPrompt(path, baseContent, variants, context);

        log.info("🧠 Submitting merge request to LLM for {}", path);

        List<Message> conversation = List.of(
                new Message("system", systemPrompt),
                new Message("user", userPrompt)
        );

        ResponseEntity<ChatResponse> response = llmClient.ask(conversation);
        ChatResponse body = response.getBody();

        if (body == null || body.getFirstContent() == null) {
            return new MergeOutcome(false, baseContent, "Empty or null LLM response");
        }

        String content = body.getFirstContent();

        if (!MergeProtocolContract.isValidResponse(content)) {
            return new MergeOutcome(false, baseContent, "Invalid merge response format");
        }
        
        MergeOutcome outcome = parser.parse(content);
        mergeActionRegistrar.registerMerge("StaticMergeAgent", context, outcome);

        return outcome;
    }
}
