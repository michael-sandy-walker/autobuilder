package com.walpro.autobuilder.core.coldread;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.LlmCircuitBreaker;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Account;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * T4-EQ's instrument: a kept reverse-engineering account composed into a seed and refined, with the run's model and
 * settings. Off unless {@code -Dt4eq.account=<account.json>}. Prints both texts (and writes them beside
 * {@code -Dt4eq.out=<dir>} when given); it asserts nothing -- the texts are scored by the rule stated on the ticket
 * before the run.
 */
@EnabledIfSystemProperty(named = "t4eq.account", matches = ".+")
class SeedRefinerLiveTest {

    @Test
    void composesAndRefinesAKeptAccount() throws Exception {
        LLMClient llm = new LLMClient(new LlmCircuitBreaker());
        ReflectionTestUtils.setField(llm, "apiUrl", System.getProperty("t4eq.url", "http://localhost:11434/v1/chat/completions"));
        ReflectionTestUtils.setField(llm, "apiKey", "local");
        ReflectionTestUtils.setField(llm, "model", System.getProperty("t4eq.model", "qwen3.8"));
        ReflectionTestUtils.setField(llm, "readTimeoutMs", 600_000);
        ReflectionTestUtils.setField(llm, "reasoningEffort", System.getProperty("t4eq.effort", "none"));
        ReflectionTestUtils.setField(llm, "readTimeoutEscalationFactor", 1.0);
        ReflectionTestUtils.setField(llm, "retryMaxAttempts", 1);
        ReflectionTestUtils.setField(llm, "responseReserveTokens", 4096);
        ReflectionTestUtils.setField(llm, "contextWindowTokens", 32768);
        Method init = LLMClient.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(llm);

        Account account = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .readValue(Path.of(System.getProperty("t4eq.account")).toFile(), Account.class);
        String given = System.getProperty("t4eq.composed");
        String composed = given != null ? Files.readString(Path.of(given)) : new SeedComposer(llm).compose(account).text();
        String refined = new SeedRefiner(llm, true).refine(account, composed);

        System.out.println("T4EQ ===== composed =====\n" + composed + "\nT4EQ ===== refined =====\n" + refined + "\nT4EQ ===== end =====");
        String out = System.getProperty("t4eq.out");
        if (out != null) {
            Files.createDirectories(Path.of(out));
            Files.writeString(Path.of(out, "composed.txt"), composed);
            Files.writeString(Path.of(out, "refined.txt"), refined);
        }
    }
}
