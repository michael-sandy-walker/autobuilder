package com.walpro.autobuilder.core.stuck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.LlmCircuitBreaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-DZ: the in-loop agent -- the Java port, at the loop's own temperature (0.0) -- on the six frozen
 * questions, against the local model. Off unless {@code -Dt4dz.live=true} (needs the model at
 * {@code -Dt4dz.url}, default the local endpoint). Same bar as the offline slice: at least 4 of 6.
 */
@EnabledIfSystemProperty(named = "t4dz.live", matches = "true")
class TerminalAgentLiveModelTest {

    @Test
    void theJavaAgentAnswersAtLeastFourOfTheSixOpenQuestions() throws Exception {
        LLMClient llm = new LLMClient(new LlmCircuitBreaker());
        ReflectionTestUtils.setField(llm, "apiUrl", System.getProperty("t4dz.url", "http://localhost:11434/v1/chat/completions"));
        ReflectionTestUtils.setField(llm, "apiKey", "local");
        ReflectionTestUtils.setField(llm, "model", System.getProperty("t4dz.model", "qwen3.8"));
        ReflectionTestUtils.setField(llm, "readTimeoutMs", 300_000);
        ReflectionTestUtils.setField(llm, "reasoningEffort", "none");
        ReflectionTestUtils.setField(llm, "readTimeoutEscalationFactor", 1.0);
        ReflectionTestUtils.setField(llm, "retryMaxAttempts", 1);
        Method init = LLMClient.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(llm);
        TerminalAgent agent = new TerminalAgent();
        ReflectionTestUtils.setField(agent, "llmClient", llm);

        int answered = 0;
        List<Path> questions;
        try (Stream<Path> s = Files.list(Path.of("src/test/resources/t4dz"))) {
            questions = s.filter(Files::isDirectory).sorted().toList();
        }
        for (Path q : questions) {
            JsonNode meta = new ObjectMapper().readTree(q.resolve("question.json").toFile());
            StuckDiagnosis.Asked asked = agent.ask(meta.get("signature").asText(),
                    List.of("compiler=" + Files.readString(q.resolve("COMPILER_OUTPUT.txt"))),
                    TerminalAgentTest.files(q.resolve("tree")), TerminalAgentTest.files(q.resolve("candidate")), "live");
            boolean ok = asked.answered() && Pattern.compile(meta.get("truth_regex").asText()).matcher(asked.answer()).find();
            answered += ok ? 1 : 0;
            System.out.println("T4-DZ live | " + q.getFileName() + " | " + (ok ? "ANSWERED" : "not answered") + " | asked: " + asked.question());
        }
        System.out.println("T4-DZ live | " + answered + " of " + questions.size());
        assertThat(answered).isGreaterThanOrEqualTo(4);
    }
}
