package com.walpro.autobuilder.client;

import com.walpro.autobuilder.client.dto.ChatRequest;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The client with its output bound: a runaway is never asked again identically, a clean cut is asked again with
 * more room, and neither counts against the circuit breaker.
 */
class LLMClientOutputBoundsTest {

    private LlmCircuitBreaker breaker;
    private RestTemplate rest;
    private LLMClient client;
    private final List<ChatRequest> sent = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        breaker = spy(new LlmCircuitBreaker());
        rest = mock(RestTemplate.class);
        client = new LLMClient(breaker);
        set("apiUrl", "http://test/v1/chat/completions");
        set("apiKey", "k");
        set("model", "m");
        set("readTimeoutMs", 5000);
        set("retryMaxAttempts", 3);
        set("retryDelayMs", 0L);
        set("restTemplate", rest);
        set("outputBoundsEnabled", true);
        set("outputBounds", new OutputBounds(1000));
    }

    private void set(String field, Object value) throws Exception {
        Field f = LLMClient.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(client, value);
    }

    private static ResponseEntity<ChatResponse> answer(String finish, String content, int tokens) {
        ChatResponse r = new ChatResponse();
        ChatResponse.Choice c = new ChatResponse.Choice();
        c.setMessage(new Message("assistant", content));
        c.setFinishReason(finish);
        r.setChoices(List.of(c));
        ChatResponse.Usage u = new ChatResponse.Usage();
        u.setCompletionTokens(tokens);
        r.setUsage(u);
        return ResponseEntity.ok(r);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void answers(ResponseEntity<ChatResponse> first, ResponseEntity<ChatResponse>... rest) {
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        when(this.rest.exchange(any(String.class), any(), entity.capture(), any(Class.class))).thenAnswer(inv -> {
            ChatRequest r = (ChatRequest) ((HttpEntity) inv.getArgument(2)).getBody();
            sent.add(new ChatRequest(r.getModel(), r.getMessages(), r.getTemperature(), r.getReasoningEffort(),
                    r.getMaxTokens(), r.getFrequencyPenalty()));
            return sent.size() == 1 || rest.length == 0 ? first : rest[Math.min(sent.size() - 2, rest.length - 1)];
        });
    }

    private final List<Message> msg = List.of(new Message("user", "write the class"));

    @Test
    @SuppressWarnings("unchecked")
    void aRunawayIsAskedAgainWithAChangedRequestNeverIdentically() {
        answers(answer("length", OutputBoundsTest.runaway(), 1000), answer("stop", OutputBoundsTest.clean(20), 400));

        ChatResponse got = client.ask(msg).getBody();

        assertThat(got.getChoices().get(0).getFinishReason()).isEqualTo("stop");
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).getMaxTokens()).as("every call is capped").isEqualTo(1000);
        assertThat(sent.get(0).getFrequencyPenalty()).isNull();
        assertThat(sent.get(1).getFrequencyPenalty()).as("the probe's first rescue").isEqualTo(0.5);
        assertThat(sent.get(1).getMaxTokens()).as("the rescue keeps the cap the runaway ran into").isEqualTo(1000);
        verify(breaker, never()).recordNetworkFailure();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRunawayThroughEveryVariantIsReturnedAsItIs_notThrown_andNotABreakerFailure() {
        answers(answer("length", OutputBoundsTest.runaway(), 1000));

        ChatResponse got = client.ask(msg).getBody();

        assertThat(got.getChoices().get(0).getFinishReason()).isEqualTo("length");
        assertThat(sent).hasSize(1 + OutputBounds.RUNAWAY_VARIANTS.size());
        assertThat(sent.get(2).getTemperature()).isEqualTo(0.4);
        verify(breaker, never()).recordNetworkFailure();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCleanAnswerCutByTheCapIsAskedAgainWithMoreRoom() {
        answers(answer("length", OutputBoundsTest.clean(300), 1000), answer("stop", OutputBoundsTest.clean(320), 1200));

        client.ask(msg);

        assertThat(sent).extracting(ChatRequest::getMaxTokens).containsExactly(1000, 1500);
        assertThat(sent.get(1).getFrequencyPenalty()).as("the same request, only bigger").isNull();
    }
}
