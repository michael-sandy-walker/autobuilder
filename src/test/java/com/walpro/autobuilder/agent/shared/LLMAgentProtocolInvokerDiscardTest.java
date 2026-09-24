package com.walpro.autobuilder.agent.shared;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.LlmCallContext;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T5-BO: an answer thrown away for its form is reported, with how many in a row. */
class LLMAgentProtocolInvokerDiscardTest {

    private static LLMClient answering(String content) {
        LLMClient client = mock(LLMClient.class);
        ChatResponse body = mock(ChatResponse.class);
        when(body.getFirstContent()).thenReturn(content);
        when(client.ask(anyList())).thenReturn(ResponseEntity.ok(body));
        return client;
    }

    @Test
    void aDiscardedAnswerIsReportedAndConsecutiveDiscardsAreCounted() {
        LLMAgentProtocolInvoker invoker = new LLMAgentProtocolInvoker();
        BehaviourLogger logger = mock(BehaviourLogger.class);
        ReflectionTestUtils.setField(invoker, "behaviourLogger", logger);
        LLMClient malformed = answering("here is my answer, not in the format");

        try (var scope = LlmCallContext.scope("evolution", "cycle-1")) {
            invoker.callWithValidation(malformed, "sys", "user", c -> false, c -> c);
            invoker.callWithValidation(malformed, "sys", "user", c -> false, c -> c);
        }

        verify(logger).llmResponseDiscarded(eq("cycle-1"), eq("evolution"), anyString(),
                eq("here is my answer, not in the format"), eq(1));
        verify(logger).llmResponseDiscarded(eq("cycle-1"), eq("evolution"), anyString(), anyString(), eq(2));
    }

    @Test
    void aValidAnswerIsNotReported() {
        LLMAgentProtocolInvoker invoker = new LLMAgentProtocolInvoker();
        BehaviourLogger logger = mock(BehaviourLogger.class);
        ReflectionTestUtils.setField(invoker, "behaviourLogger", logger);

        try (var scope = LlmCallContext.scope("evolution", "cycle-1")) {
            invoker.callWithValidation(answering("ok"), "sys", "user", c -> true, c -> c);
        }

        verify(logger, never()).llmResponseDiscarded(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
