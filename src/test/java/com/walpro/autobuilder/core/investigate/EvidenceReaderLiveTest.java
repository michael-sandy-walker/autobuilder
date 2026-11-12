package com.walpro.autobuilder.core.investigate;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.LlmCircuitBreaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * T4-EU's replay instrument: the evidence reader asked, with the run's model and settings, for the lines a
 * recorded refusal is about, in a kept project tree. Off unless {@code -Dt4eu.project=<tree>} and
 * {@code -Dt4eu.statement=<file holding the refusal's text>}. Prints the places read, the probes and the
 * model's own sentence; it asserts nothing -- whether the lines printed are the ones that settle the refusal
 * is read by a person.
 */
@EnabledIfSystemProperty(named = "t4eu.project", matches = ".+")
class EvidenceReaderLiveTest {

    @Test
    void readsTheLinesARecordedRefusalIsAbout() throws Exception {
        LLMClient llm = new LLMClient(new LlmCircuitBreaker());
        ReflectionTestUtils.setField(llm, "apiUrl", System.getProperty("t4eu.url", "http://localhost:11434/v1/chat/completions"));
        ReflectionTestUtils.setField(llm, "apiKey", "local");
        ReflectionTestUtils.setField(llm, "model", System.getProperty("t4eu.model", "qwen3.8"));
        ReflectionTestUtils.setField(llm, "readTimeoutMs", 300_000);
        ReflectionTestUtils.setField(llm, "reasoningEffort", System.getProperty("t4eu.effort", "none"));
        ReflectionTestUtils.setField(llm, "readTimeoutEscalationFactor", 1.0);
        ReflectionTestUtils.setField(llm, "retryMaxAttempts", 1);
        ReflectionTestUtils.setField(llm, "responseReserveTokens", 4096);
        ReflectionTestUtils.setField(llm, "contextWindowTokens", 32768);
        Method init = LLMClient.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(llm);

        String statement = Files.readString(Path.of(System.getProperty("t4eu.statement"))).strip();
        var reading = new EvidenceReader(new LogInvestigator(llm), true)
                .read(statement, Path.of(System.getProperty("t4eu.project")), "replay");

        System.out.println("T4EU statement: " + statement);
        reading.probes().forEach(p -> System.out.println("T4EU probe: " + p));
        System.out.println("T4EU places: " + reading.places().size());
        reading.places().forEach(p -> System.out.println("T4EU " + p.render().replace("\n", "\nT4EU ")));
        System.out.println("T4EU model's own reading: " + reading.modelReading());
    }
}
