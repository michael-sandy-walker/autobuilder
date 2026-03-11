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
}
