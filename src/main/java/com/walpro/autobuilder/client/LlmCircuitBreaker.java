package com.walpro.autobuilder.client;

import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;

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
 *   <li>5xx / other 4xx / network failures → TIMED open after {@value #CONSECUTIVE_FAILURE_THRESHOLD}
 *       consecutive failures, with escalating cool-down (self-recovering — see T1-FD)</li>
 * </ul>
 */
@Slf4j
@Component
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "live endpoint health — a rewind says nothing about whether the model is answering "
                + "now")
public class LlmCircuitBreaker {

    private static final int CONSECUTIVE_FAILURE_THRESHOLD = 5;

    public enum State { CLOSED, OPEN }

    private volatile State state = State.CLOSED;
    private volatile String pauseReason;
    private volatile Instant nextRetryTime = Instant.EPOCH;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    /** How many timed opens this failure episode has produced — drives cool-down escalation. */
    private final AtomicInteger timedOpens = new AtomicInteger(0);

    /**
     * T1-FD: cool-down for the Nth consecutive timed open — 60s, 120s, 240s, 480s, capped 600s.
     * Timed (self-recovering) opens replaced the previous permanent count-based open: with
     * fail-fast in {@link LLMClient} a permanent open could never observe a success and
     * therefore never close, leaving the system unable to recover on its own (user steer
     * 2026-07-02 after the run-4 timeout livelock).
     */
    static long cooldownSeconds(int nthOpen) {
        return Math.min(600L, 60L * (1L << Math.min(Math.max(nthOpen, 1) - 1, 4)));
    }

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
        timedOpens.set(0);
        if (state == State.OPEN || Instant.now().isBefore(nextRetryTime)) {
            log.info("LLM circuit breaker closed after successful response.");
        }
        nextRetryTime = Instant.EPOCH;
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
            openTimed(failures, "last HTTP " + httpStatus);
        } else {
            log.warn("LLM failure #{} (HTTP {}); circuit still closed.", failures, httpStatus);
        }
    }

    /**
     * T1-FD: count-based opens are TIMED, not permanent — the endpoint may recover (ollama
     * finishing a backlog, a restart), and with fail-fast no probe would otherwise ever run.
     * After the cool-down elapses {@link #isOpen()} returns false, callers try again, and
     * either {@link #recordSuccess()} closes the episode or the next threshold-crossing
     * failure re-opens with an escalated window.
     */
    private void openTimed(int failures, String cause) {
        long backoffSecs = cooldownSeconds(timedOpens.incrementAndGet());
        nextRetryTime = Instant.now().plusSeconds(backoffSecs);
        pauseReason = "LLM API: " + failures + " consecutive failures (" + cause + ") — failing fast for "
                + backoffSecs + "s, next attempt at " + nextRetryTime;
        log.error("LLM circuit breaker OPEN (timed, {}s) after {} consecutive failures: {}",
                backoffSecs, failures, cause);
    }

    /**
     * Called on network-level failures (connection timeout, read timeout, connection refused).
     * Routes to the same count-based threshold as 5xx errors.
     */
    public void recordNetworkFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= CONSECUTIVE_FAILURE_THRESHOLD) {
            openTimed(failures, "network error");
        } else {
            log.warn("LLM network failure #{} — circuit still closed.", failures);
        }
    }

    /** Resets the circuit to CLOSED state — for use after manual intervention or testing. */
    public void reset() {
        consecutiveFailures.set(0);
        timedOpens.set(0);
        state = State.CLOSED;
        pauseReason = null;
        nextRetryTime = Instant.EPOCH;
        log.info("LLM circuit breaker manually reset to CLOSED.");
    }
}
