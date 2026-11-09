package com.walpro.autobuilder.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NarratorChannelTest {

    @Test
    void aDeadNarratorEndpointOpensOnlyTheNarratorsOwnBreakerAndNeverThrows() {
        LlmCircuitBreaker loops = new LlmCircuitBreaker();   // stands for the bean the loop pauses on
        LlmCircuitBreaker narrators = new LlmCircuitBreaker();
        // port 9 (discard) on localhost: refused at once, no model involved
        LLMClient client = LLMClient.forChannel(narrators, "http://127.0.0.1:9/v1/chat/completions", "", "some-model",
                2_000, "none", 32768, 512);
        NarratorChannel channel = new NarratorChannel(client, narrators);

        for (int i = 0; i < 6; i++) {
            assertThat(channel.ask("s", "u", "cycle-1")).isEmpty();
        }

        assertThat(channel.breaker()).isSameAs(narrators);
        assertThat(narrators.isOpen()).isTrue();
        assertThat(loops.isOpen()).isFalse();
    }

    @Test
    void theChannelUsesItsOwnSettingsNotTheLoops() {
        LLMClient client = LLMClient.forChannel(new LlmCircuitBreaker(), "http://narrator.example/v1", null, "small-model",
                1_000, "", 8192, 256);
        assertThat(client.model()).isEqualTo("small-model");
    }

    @Test
    void offMeansNoClientAndNoAnswer() {
        NarratorChannel off = new NarratorChannel(false, "u", "", "m", 1000, "none", 32768, 512, null, null);
        assertThat(off.enabled()).isFalse();
        assertThat(off.ask("s", "u", "cycle-1")).isEmpty();
    }
}
