package com.walpro.autobuilder.agent.shared;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T4-EE: a first reply that is a question is answered in the conversation, and the real answer follows. */
class LLMAgentProtocolInvokerConsultTest {

    @SuppressWarnings("unchecked")
    private static LLMClient replying(String... contents) {
        LLMClient client = mock(LLMClient.class);
        List<ResponseEntity<ChatResponse>> rs = new ArrayList<>();
        for (String c : contents) {
            ChatResponse body = mock(ChatResponse.class);
            when(body.getFirstContent()).thenReturn(c);
            rs.add(ResponseEntity.ok(body));
        }
        when(client.ask(anyList())).thenReturn(rs.get(0), rs.subList(1, rs.size()).toArray(ResponseEntity[]::new));
        return client;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aQuestionIsAnsweredInTheConversation_andIsNotCountedAsAFailedAttempt() {
        LLMClient client = replying("?ask", "bad", "ok");
        var result = new LLMAgentProtocolInvoker().callWithValidation(client, "sys", "user",
                "ok"::equals, c -> c, null, c -> c.equals("?ask") ? "the answer" : null);

        assertThat(result.success()).isTrue();
        ArgumentCaptor<List<Message>> sent = ArgumentCaptor.forClass(List.class);
        verify(client, times(3)).ask(sent.capture());
        List<Message> second = sent.getAllValues().get(1);
        assertThat(second).extracting(Message::getContent).containsExactly("sys", "user", "?ask", "the answer");
        // the format retry keeps the question and its answer, then corrects the bad reply
        List<Message> third = sent.getAllValues().get(2);
        assertThat(third).extracting(Message::getContent).startsWith("sys", "user", "?ask", "the answer", "bad");
    }

    @Test
    void aFirstReplyThatIsNotAQuestion_isJudgedAsBefore() {
        LLMClient client = replying("ok");
        var result = new LLMAgentProtocolInvoker().callWithValidation(client, "sys", "user",
                "ok"::equals, c -> c, null, c -> null);

        assertThat(result.success()).isTrue();
        verify(client, times(1)).ask(anyList());
    }

    @Test
    void onlyOneQuestionPerCall() {
        LLMClient client = replying("?ask", "?ask", "?ask", "?ask", "?ask");
        var result = new LLMAgentProtocolInvoker().callWithValidation(client, "sys", "user",
                "ok"::equals, c -> c, null, c -> c.equals("?ask") ? "the answer" : null);

        assertThat(result.success()).isFalse();
        // one consultation, then the usual attempts
        verify(client, times(1 + LLMAgentProtocolInvoker.MAX_ATTEMPTS)).ask(anyList());
    }

    @Test
    void aConsultThatThrows_costsNothing() {
        LLMClient client = replying("ok");
        var result = new LLMAgentProtocolInvoker().callWithValidation(client, "sys", "user",
                "ok"::equals, c -> c, null, c -> { throw new IllegalStateException("x"); });

        assertThat(result.success()).isTrue();
    }
}
