package com.walpro.autobuilder.client;

import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class LLMClientTest {

    private LlmCircuitBreaker circuitBreaker;
    private RestTemplate restTemplate;
    private LLMClient client;

    @BeforeEach
    void setUp() throws Exception {
        circuitBreaker = spy(new LlmCircuitBreaker());
        restTemplate = mock(RestTemplate.class);
        client = new LLMClient(circuitBreaker);
        set(client, "apiUrl", "http://test/v1/chat/completions");
        set(client, "apiKey", "test-key");
        set(client, "model", "test-model");
        set(client, "readTimeoutMs", 5000);
        set(client, "retryMaxAttempts", 3);
        set(client, "retryDelayMs", 0L);   // no delay in tests
        set(client, "restTemplate", restTemplate);
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private List<Message> msg() {
        return List.of(new Message("user", "hello"));
    }

    // ── success path ─────────────────────────────────────────────────────────

    @Test
    void succeedsOnFirstAttempt_noRetry() {
        ResponseEntity<ChatResponse> ok = ResponseEntity.ok(new ChatResponse());
        when(restTemplate.exchange(any(String.class), any(), any(), any(Class.class))).thenReturn(ok);

        client.ask(msg());

        verify(restTemplate, times(1)).exchange(any(String.class), any(), any(), any(Class.class));
        verify(circuitBreaker).recordSuccess();
        verify(circuitBreaker, never()).recordNetworkFailure();
    }

    // ── network/timeout retries ───────────────────────────────────────────────

    @Test
    void retriesOnTimeout_succeedsOnSecondAttempt() {
        ResponseEntity<ChatResponse> ok = ResponseEntity.ok(new ChatResponse());
        when(restTemplate.exchange(any(String.class), any(), any(), any(Class.class)))
                .thenThrow(new ResourceAccessException("Read timed out"))
                .thenReturn(ok);

        client.ask(msg());

        verify(restTemplate, times(2)).exchange(any(String.class), any(), any(), any(Class.class));
        verify(circuitBreaker).recordSuccess();
        verify(circuitBreaker, never()).recordNetworkFailure();
    }

    @Test
    void exhaustsRetries_recordsOneNetworkFailure() {
        when(restTemplate.exchange(any(String.class), any(), any(), any(Class.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));

        assertThatThrownBy(() -> client.ask(msg()))
                .isInstanceOf(ResourceAccessException.class);

        verify(restTemplate, times(3)).exchange(any(String.class), any(), any(), any(Class.class));
        verify(circuitBreaker, times(1)).recordNetworkFailure();
        verify(circuitBreaker, never()).recordSuccess();
    }

    // ── 5xx retries ──────────────────────────────────────────────────────────

    @Test
    void retries5xx_succeedsOnThirdAttempt() {
        ResponseEntity<ChatResponse> ok = ResponseEntity.ok(new ChatResponse());
        when(restTemplate.exchange(any(String.class), any(), any(), any(Class.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR))
                .thenReturn(ok);

        client.ask(msg());

        verify(restTemplate, times(3)).exchange(any(String.class), any(), any(), any(Class.class));
        verify(circuitBreaker).recordSuccess();
        verify(circuitBreaker, never()).recordFailure(anyInt(), any());
    }

    @Test
    void exhausts5xx_recordsOneHttpFailure() {
        when(restTemplate.exchange(any(String.class), any(), any(), any(Class.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.ask(msg()))
                .isInstanceOf(HttpServerErrorException.class);

        verify(restTemplate, times(3)).exchange(any(String.class), any(), any(), any(Class.class));
        verify(circuitBreaker, times(1)).recordFailure(eq(500), any());
    }

    // ── no-retry cases ────────────────────────────────────────────────────────

    @Test
    void http401_noRetry_immediateCircuitBreakerRecord() {
        when(restTemplate.exchange(any(String.class), any(), any(), any(Class.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.ask(msg()))
                .isInstanceOf(HttpClientErrorException.class);

        verify(restTemplate, times(1)).exchange(any(String.class), any(), any(), any(Class.class));
        verify(circuitBreaker, times(1)).recordFailure(eq(401), any());
    }

    @Test
    void http429_noRetry_immediateCircuitBreakerRecord() {
        when(restTemplate.exchange(any(String.class), any(), any(), any(Class.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.ask(msg()))
                .isInstanceOf(HttpClientErrorException.class);

        verify(restTemplate, times(1)).exchange(any(String.class), any(), any(), any(Class.class));
        verify(circuitBreaker, times(1)).recordFailure(eq(429), any());
    }

    // ── circuit-breaker count semantics ──────────────────────────────────────

    @Test
    void threeTimeoutAttempts_countAsOneFailureTowardCircuitBreaker() {
        when(restTemplate.exchange(any(String.class), any(), any(), any(Class.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));

        assertThatThrownBy(() -> client.ask(msg())).isInstanceOf(ResourceAccessException.class);

        // Circuit breaker must see exactly ONE logical failure, not three
        verify(circuitBreaker, times(1)).recordNetworkFailure();
        assertThat(circuitBreaker.isOpen()).isFalse(); // 1 < threshold of 5 → still closed
    }
}
