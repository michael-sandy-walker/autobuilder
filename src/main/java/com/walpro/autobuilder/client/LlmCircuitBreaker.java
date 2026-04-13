package com.walpro.autobuilder.client;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Tracks LLM API failure patterns and opens a circuit to halt the scheduler
 * when permanent failures (quota exhaustion, auth) or sustained transient failures occur.
 *
 * <ul>
 *   <li>429 insufficient_quota / 401 → permanent open (manual reset required)</li>
 *   <li>429 rate_limit_exceeded → exponential backoff (auto-recovers after delay)</li>
 *   <li>5xx / other 4xx → count-based open after {@value #CONSECUTIVE_FAILURE_THRESHOLD} failures</li>
 * </ul>
 */
@Slf4j
@Component
public class LlmCircuitBreaker {

    private static final int CONSECUTIVE_FAILURE_THRESHOLD = 5;

    public enum State { CLOSED, OPEN }

    private volatile State state = State.CLOSED;
    private volatile String pauseReason;
    private volatile Instant nextRetryTime = Instant.EPOCH;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    /**
     * Returns true when the circuit is open (permanently) or the backoff window has not elapsed.
     */
    public boolean isOpen() {
        return state == State.OPEN || Instant.now().isBefore(nextRetryTime);
    }

    public String pauseReason() {
        return pauseReason;
    }

    /** Called on any successful LLM response — resets transient counters. */
    public void recordSuccess() {
        consecutiveFailures.set(0);
        nextRetryTime = Instant.EPOCH;
        if (state == State.OPEN) {
            log.info("LLM circuit breaker closed after successful response.");
        }
        state = State.CLOSED;
    }

    /**
     * Called on LLM HTTP error.
     *
     * @param httpStatus HTTP status code from the error response
     * @param errorCode  API-level error code extracted from the response body (e.g. "insufficient_quota"), may be null
     */
    public void recordFailure(int httpStatus, String errorCode) {
        int failures = consecutiveFailures.incrementAndGet();

        if (httpStatus == 401 || "insufficient_quota".equals(errorCode)) {
            state = State.OPEN;
            pauseReason = "LLM API fatal error: HTTP " + httpStatus
                    + (errorCode != null ? " / " + errorCode : "")
                    + " — manual reset required";
            log.error("LLM circuit breaker OPEN (permanent): {}", pauseReason);
            return;
        }

        if (httpStatus == 429) {
            // Exponential backoff: 30s, 60s, 120s, 240s, capped at 300s
            long backoffSecs = Math.min(300L, 30L * (1L << Math.min(failures - 1, 4)));
            nextRetryTime = Instant.now().plusSeconds(backoffSecs);
            pauseReason = "LLM rate-limited (HTTP 429); next retry at " + nextRetryTime;
            log.warn("LLM rate limit hit (failure #{}); backing off {}s.", failures, backoffSecs);
            return;
        }

        if (failures >= CONSECUTIVE_FAILURE_THRESHOLD) {
            state = State.OPEN;
            pauseReason = "LLM API: " + failures + " consecutive failures (last HTTP " + httpStatus + ")";
            log.error("LLM circuit breaker OPEN after {} consecutive failures: {}", failures, pauseReason);
        } else {
            log.warn("LLM failure #{} (HTTP {}); circuit still closed.", failures, httpStatus);
        }
    }

    /**
     * Called on network-level failures (connection timeout, read timeout, connection refused).
     * Routes to the same count-based threshold as 5xx errors.
     */
    public void recordNetworkFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= CONSECUTIVE_FAILURE_THRESHOLD) {
            state = State.OPEN;
            pauseReason = "LLM API: " + failures + " consecutive failures (network error)";
            log.error("LLM circuit breaker OPEN after {} consecutive network failures", failures);
        } else {
            log.warn("LLM network failure #{} — circuit still closed.", failures);
        }
    }

    /** Resets the circuit to CLOSED state — for use after manual intervention or testing. */
    public void reset() {
        consecutiveFailures.set(0);
        state = State.CLOSED;
        pauseReason = null;
        nextRetryTime = Instant.EPOCH;
        log.info("LLM circuit breaker manually reset to CLOSED.");
    }
}
