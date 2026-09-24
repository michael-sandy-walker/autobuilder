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

    @Test
    @SuppressWarnings("unchecked")
    void theRetrySaysWhatWasWrongAndAStreakWarnsTheNextFirstAttempt() {
        LLMAgentProtocolInvoker invoker = new LLMAgentProtocolInvoker();
        LLMClient malformed = answering("Sure! Here is the file.");
        java.util.function.Function<String, String> why = c -> "no block names a file";

        try (var scope = LlmCallContext.scope("evolution", "cycle-1")) {
            for (int i = 0; i < 3; i++) {
                invoker.callWithValidation(malformed, "sys", "the task", c -> false, c -> c, why);
            }
        }

        var captor = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(malformed, org.mockito.Mockito.times(6)).ask(captor.capture());
        var calls = captor.getAllValues();
        var retry = (java.util.List<com.walpro.autobuilder.client.dto.Message>) calls.get(1);
        org.assertj.core.api.Assertions.assertThat(retry.get(3).getContent())
                .as("the retry names the defect").contains("no block names a file");
        var secondFirst = (java.util.List<com.walpro.autobuilder.client.dto.Message>) calls.get(2);
        org.assertj.core.api.Assertions.assertThat(secondFirst.get(1).getContent())
                .as("one discard is not yet a streak").isEqualTo("the task");
        var thirdFirst = (java.util.List<com.walpro.autobuilder.client.dto.Message>) calls.get(4);
        org.assertj.core.api.Assertions.assertThat(thirdFirst.get(1).getContent())
                .startsWith("the task")
                .contains("Your last 2 answers were discarded unread, because no block names a file");
    }

    @Test
    void withoutADescriberTheRetryQuotesHowTheAnswerBegan() {
        org.assertj.core.api.Assertions.assertThat(LLMAgentProtocolInvoker.retryInstruction(null))
                .startsWith("Your previous response did not match the required format.");
    }
}
