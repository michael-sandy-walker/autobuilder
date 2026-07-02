package com.walpro.autobuilder.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmCircuitBreakerTest {

    private LlmCircuitBreaker cb;

    @BeforeEach
    void setUp() {
        cb = new LlmCircuitBreaker();
    }

    @Test
    void startsClosedAndOpen_returnsFalse() {
        assertThat(cb.isOpen()).isFalse();
    }

    @Test
    void insufficientQuota_opensCircuitImmediately() {
        cb.recordFailure(429, "insufficient_quota");
        assertThat(cb.isOpen()).isTrue();
        assertThat(cb.pauseReason()).contains("insufficient_quota");
    }

    @Test
    void http401_opensCircuitImmediately() {
        cb.recordFailure(401, null);
        assertThat(cb.isOpen()).isTrue();
        assertThat(cb.pauseReason()).contains("401");
    }

    @Test
    void rateLimitExceeded_triggersBackoff_notPermanentOpen() {
        cb.recordFailure(429, "rate_limit_exceeded");
        // Circuit is "open" only due to backoff window; not permanently open
        assertThat(cb.isOpen()).isTrue();
        assertThat(cb.pauseReason()).contains("429");
    }

    @Test
    void fiveConsecutiveServerErrors_opensCircuit() {
        for (int i = 0; i < 4; i++) {
            cb.recordFailure(500, null);
            assertThat(cb.isOpen()).isFalse();
        }
        cb.recordFailure(500, null);
        assertThat(cb.isOpen()).isTrue();
    }

    @Test
    void successResetsClosed_afterTransientFailures() {
        cb.recordFailure(500, null);
        cb.recordFailure(500, null);
        cb.recordSuccess();
        assertThat(cb.isOpen()).isFalse();
    }

    @Test
    void reset_closesCircuitAfterPermanentOpen() {
        cb.recordFailure(401, null);
        assertThat(cb.isOpen()).isTrue();
        cb.reset();
        assertThat(cb.isOpen()).isFalse();
    }

    @Test
    void networkFailures_openCircuitAfterThreshold() {
        for (int i = 0; i < 4; i++) {
            cb.recordNetworkFailure();
            assertThat(cb.isOpen()).isFalse();
        }
        cb.recordNetworkFailure();
        assertThat(cb.isOpen()).isTrue();
        assertThat(cb.pauseReason()).contains("network error");
    }

    @Test
    void networkAndHttpFailures_shareCounter() {
        cb.recordNetworkFailure();
        cb.recordNetworkFailure();
        cb.recordFailure(500, null);
        cb.recordFailure(500, null);
        assertThat(cb.isOpen()).isFalse();
        cb.recordNetworkFailure();
        assertThat(cb.isOpen()).isTrue();
    }

    @Test
    void successResetsAfterNetworkFailures() {
        cb.recordNetworkFailure();
        cb.recordNetworkFailure();
        cb.recordSuccess();
        assertThat(cb.isOpen()).isFalse();
    }

    @Test
    void countBasedOpen_isTimedAndSelfRecovering_notPermanent() {
        // T1-FD: with fail-fast in LLMClient, a PERMANENT count-based open could never observe
        // a success and therefore never close — the system must be able to recover on its own.
        // Network/5xx opens are timed (escalating cool-down); only auth/quota stay permanent.
        for (int i = 0; i < 5; i++) cb.recordNetworkFailure();
        assertThat(cb.isOpen()).isTrue();
        assertThat(cb.pauseReason()).contains("failing fast for").contains("next attempt at");

        // A success after the window closes the episode fully (counters + window cleared).
        cb.recordSuccess();
        assertThat(cb.isOpen()).isFalse();
        for (int i = 0; i < 4; i++) cb.recordNetworkFailure();
        assertThat(cb.isOpen()).isFalse(); // fresh episode: threshold not yet crossed
    }

    @Test
    void timedOpenCooldown_escalatesAndCaps() {
        assertThat(LlmCircuitBreaker.cooldownSeconds(1)).isEqualTo(60);
        assertThat(LlmCircuitBreaker.cooldownSeconds(2)).isEqualTo(120);
        assertThat(LlmCircuitBreaker.cooldownSeconds(3)).isEqualTo(240);
        assertThat(LlmCircuitBreaker.cooldownSeconds(4)).isEqualTo(480);
        assertThat(LlmCircuitBreaker.cooldownSeconds(5)).isEqualTo(600);
        assertThat(LlmCircuitBreaker.cooldownSeconds(50)).isEqualTo(600);
    }
}
