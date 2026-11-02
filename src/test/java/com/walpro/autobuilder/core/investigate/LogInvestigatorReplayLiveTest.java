package com.walpro.autobuilder.core.investigate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.LlmCircuitBreaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * T4-EE slice A: the investigator replayed at archived decision points. Each corpus entry names a run, the
 * log line the decision was taken at (the log is cut there), a question, and a pattern its verified
 * answer must contain. Off unless {@code -Dt4ee.replay=<corpus.json>}; run settings as the loop's
 * (local model, temperature 0, reasoning none). Prints one line per question and writes every step to
 * {@code <corpus>.results.json} for reading; it asserts nothing, the count is the measurement.
 */
@EnabledIfSystemProperty(named = "t4ee.replay", matches = ".+")
class LogInvestigatorReplayLiveTest {

    @Test
    void answersTheCorpusFromTheLogAsItStoodAtEachDecision() throws Exception {
        LLMClient llm = new LLMClient(new LlmCircuitBreaker());
        ReflectionTestUtils.setField(llm, "apiUrl", System.getProperty("t4ee.url", "http://localhost:11434/v1/chat/completions"));
        ReflectionTestUtils.setField(llm, "apiKey", "local");
        ReflectionTestUtils.setField(llm, "model", System.getProperty("t4ee.model", "qwen3.8"));
        ReflectionTestUtils.setField(llm, "readTimeoutMs", 300_000);
        ReflectionTestUtils.setField(llm, "reasoningEffort", System.getProperty("t4ee.effort", "none"));
        ReflectionTestUtils.setField(llm, "readTimeoutEscalationFactor", 1.0);
        ReflectionTestUtils.setField(llm, "retryMaxAttempts", 1);
        ReflectionTestUtils.setField(llm, "responseReserveTokens", 4096);
        ReflectionTestUtils.setField(llm, "contextWindowTokens", 32768);
        Method init = LLMClient.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(llm);

        Path corpusPath = Path.of(System.getProperty("t4ee.replay"));
        Path archive = Path.of(System.getProperty("t4ee.archive", System.getProperty("user.home") + "/.autobuilder/run-archive"));
        int probes = Integer.getInteger("t4ee.probes", LogInvestigator.DEFAULT_PROBES);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode corpus = mapper.readTree(corpusPath.toFile());
        ArrayNode out = mapper.createArrayNode();
        LogInvestigator investigator = new LogInvestigator(llm);
        int right = 0;
        for (JsonNode q : corpus) {
            List<String> lines = head(archive.resolve(q.get("run").asText()).resolve("autobuilder.log.gz"), q.get("cut").asInt());
            Map<String, List<String>> logs = new LinkedHashMap<>();
            logs.put("autobuilder.log", lines);
            // slice D: a question about the project NOW carries the tree as it was at the decision (a cycle snapshot)
            Path tree = q.hasNonNull("tree") ? Path.of(q.get("tree").asText().replaceFirst("^~", System.getProperty("user.home"))) : null;
            long t0 = System.currentTimeMillis();
            LogInvestigator.Investigation inv = investigator.investigate(q.get("question").asText(), logs, tree, probes, null);
            boolean ok = inv.answered() && Pattern.compile(q.get("expect").asText(), Pattern.CASE_INSENSITIVE)
                    .matcher(inv.answer()).find()
                    && (!q.hasNonNull("forbid") || !Pattern.compile(q.get("forbid").asText()).matcher(inv.answer()).find());
            if (ok) right++;
            System.out.printf("T4-EE replay %-20s %s  probes=%d  %ds%n", q.get("id").asText(), ok ? "RIGHT" : "WRONG",
                    inv.steps().size(), (System.currentTimeMillis() - t0) / 1000);
            var rec = out.addObject();
            rec.put("id", q.get("id").asText());
            rec.put("right", ok);
            rec.put("answer", inv.answer());
            var steps = rec.putArray("steps");
            inv.steps().forEach(s -> steps.addObject().put("probe", s.probe()).put("result", s.result()));
        }
        System.out.printf("T4-EE replay: %d of %d right%n", right, corpus.size());
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                corpusPath.resolveSibling(corpusPath.getFileName() + ".results.json").toFile(), out);
    }

    private static List<String> head(Path gz, int lines) throws Exception {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(gz)), StandardCharsets.UTF_8))) {
            String l;
            while (out.size() < lines && (l = r.readLine()) != null) out.add(l);
        }
        return out;
    }
}
