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

        Account account0 = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .readValue(Path.of(System.getProperty("t4eq.account")).toFile(), Account.class);
        Account account = account0;
        // -Dt4eq.program=<jar>: an account kept before the streams were recorded has its program runs observed again,
        // with the same files and arguments, so the error stream is there as a job run today would have recorded it.
        String program = System.getProperty("t4eq.program");
        if (program != null) account = reobserved(account, Path.of(program));
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

    private static Account reobserved(Account a, Path jar) throws Exception {
        java.util.List<ReverseEngineeringService.JudgedClaim> out = new java.util.ArrayList<>();
        int n = 0;
        for (ReverseEngineeringService.JudgedClaim j : a.claims()) {
            var k = j.claim();
            if (k.run() == null) {
                out.add(j);
                continue;
            }
            Path cwd = Files.createTempDirectory("t4eq-run");
            for (var f : k.run().files().entrySet()) {
                Path file = cwd.resolve(f.getKey()).normalize();
                if (!file.startsWith(cwd)) continue;
                Files.createDirectories(file.getParent());
                Files.writeString(file, f.getValue());
            }
            java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of("java", "-jar", jar.toAbsolutePath().toString()));
            cmd.addAll(k.run().args());
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile());
            pb.environment().remove("JAVA_TOOL_OPTIONS");
            Process p = pb.start();
            p.getOutputStream().close();
            String stdout = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String stderr = new String(p.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int exit = p.waitFor();
            java.util.List<ReverseEngineeringService.Observation> obs = new java.util.ArrayList<>();
            for (var o : j.observations()) {
                if (!"as-is".equals(o.variant())) {
                    obs.add(o);
                    continue;
                }
                if (o.status() == null || o.status() != exit) throw new IllegalStateException(k.id() + " answered " + exit + " now, " + o.status() + " in the account");
                obs.add(new ReverseEngineeringService.Observation(o.variant(), exit, o.unobserved(), stdout + stderr, o.held(), o.sent(), stderr));
                n++;
            }
            out.add(new ReverseEngineeringService.JudgedClaim(k, j.verdict(), j.alsoNeeds(), obs, j.byFeature()));
        }
        System.out.println("T4EQ re-observed " + n + " program run(s) with the streams read apart");
        return new Account(a.source(), a.copy(), a.coldRead(), a.kind(), a.features(), a.capabilities(), a.unknownDeclarations(),
                a.proposals(), a.variants(), out, a.verdicts(), a.durationMs());
    }
}
