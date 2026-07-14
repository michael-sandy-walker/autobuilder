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

    @Test
    void circuitOpen_failsFastWithoutNetworkCall_andWithoutExtendingTheWindow() {
        // T1-FD: run 4's livelock fuel — callers kept firing 2-minute-timeout requests while
        // the circuit was open. An open circuit rejects immediately on every path, and the
        // fast-fail must not record a failure (that would extend the cool-down forever).
        when(circuitBreaker.isOpen()).thenReturn(true);
        when(circuitBreaker.pauseReason()).thenReturn("LLM API: 5 consecutive failures (network error)");

        assertThatThrownBy(() -> client.ask(msg()))
                .isInstanceOf(ResourceAccessException.class)
                .hasMessageContaining("failing fast");

        verify(restTemplate, never()).exchange(any(String.class), any(), any(), any(Class.class));
        verify(circuitBreaker, never()).recordNetworkFailure();
        verify(circuitBreaker, never()).recordFailure(anyInt(), any());
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
        // T1-FD: the failed attempt counts as a breaker observation even though the call recovered —
        // the success right after resets the consecutive counter, so a healthy endpoint never opens.
        verify(circuitBreaker, times(1)).recordNetworkFailure();
        verify(circuitBreaker).recordSuccess();
        assertThat(circuitBreaker.isOpen()).isFalse();
    }

    @Test
    void exhaustsRetries_everyAttemptCountsTowardTheBreaker() {
        when(restTemplate.exchange(any(String.class), any(), any(), any(Class.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));

        assertThatThrownBy(() -> client.ask(msg()))
                .isInstanceOf(ResourceAccessException.class);

        verify(restTemplate, times(3)).exchange(any(String.class), any(), any(), any(Class.class));
        verify(circuitBreaker, times(3)).recordNetworkFailure();
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
        verify(circuitBreaker, times(2)).recordFailure(eq(500), any());
        assertThat(circuitBreaker.isOpen()).isFalse(); // success reset the consecutive counter
    }

    @Test
    void exhausts5xx_everyAttemptCountsTowardTheBreaker() {
        when(restTemplate.exchange(any(String.class), any(), any(), any(Class.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.ask(msg()))
                .isInstanceOf(HttpServerErrorException.class);

        verify(restTemplate, times(3)).exchange(any(String.class), any(), any(), any(Class.class));
        verify(circuitBreaker, times(3)).recordFailure(eq(500), any());
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
    void timeoutAttempts_eachCountsTowardCircuitBreaker_belowThresholdStaysClosed() {
        // T1-FD attempt-level counting (run 3, 2026-07-03): the previous one-failure-per-call
        // aggregation meant a wedged endpoint absorbed retryMaxAttempts × read-timeout (~6 min)
        // per counted failure — ~48 min of retry storm before the threshold opened the circuit.
        // The attempt is the unit of observation about the endpoint.
        when(restTemplate.exchange(any(String.class), any(), any(), any(Class.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));

        assertThatThrownBy(() -> client.ask(msg())).isInstanceOf(ResourceAccessException.class);

        verify(circuitBreaker, times(3)).recordNetworkFailure();
        assertThat(circuitBreaker.isOpen()).isFalse(); // 3 < threshold of 5 → still closed
    }

    @Test
    void circuitOpensMidCall_remainingRetriesAreAbandoned() throws Exception {
        // Two calls against a wedged endpoint: the first burns its 3 attempts (counter = 3),
        // the second's 2nd attempt crosses the threshold of 5 and opens the circuit — its 3rd
        // attempt must NOT run (with a 2-minute read timeout, each avoided attempt matters).
        when(restTemplate.exchange(any(String.class), any(), any(), any(Class.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));

        assertThatThrownBy(() -> client.ask(msg())).isInstanceOf(ResourceAccessException.class);
        assertThatThrownBy(() -> client.ask(msg())).isInstanceOf(ResourceAccessException.class);

        // 3 attempts from call 1 + only 2 from call 2.
        verify(restTemplate, times(5)).exchange(any(String.class), any(), any(), any(Class.class));
        assertThat(circuitBreaker.isOpen()).isTrue();
    }

    // ── retry backoff ─────────────────────────────────────────────────────────

    @Test
    void retryBackoff_escalatesPerAttempt_andIsCapped() {
        // T1-FD: a fixed short delay re-queues work against an endpoint still computing the
        // aborted generation; the pause doubles per attempt and is capped.
        assertThat(LLMClient.retryBackoffMs(5000, 1)).isEqualTo(5000);
        assertThat(LLMClient.retryBackoffMs(5000, 2)).isEqualTo(10_000);
        // 5000 ms doubled 4 times = 80 000 ms; the 60 s cap applies.
        assertThat(LLMClient.retryBackoffMs(5000, 5)).isEqualTo(60_000);
        assertThat(LLMClient.retryBackoffMs(5000, 40)).isEqualTo(60_000); // shift bounded, no overflow
        assertThat(LLMClient.retryBackoffMs(0, 3)).isZero(); // tests configure no delay
    }
}
