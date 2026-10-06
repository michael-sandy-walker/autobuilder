package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.LlmCircuitBreaker;
import com.walpro.autobuilder.core.build.SurefireReports;
import com.walpro.autobuilder.core.build.TestRunGate;
import com.walpro.autobuilder.core.build.TestRunReport;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.protocol.PromptTokenBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T6-T: the archive replay of the completion verifier -- every prepared closure ({@code scripts/closure_replay_prep.py})
 * re-decided with the current verifier against the local model, the tree's own test run as the record. The bar: no
 * completion on a tree the yardstick does not score in full (a false completion). Off unless
 * {@code -Dt6t.replay=<prepared dir>}; prints one line per run and a summary.
 */
@EnabledIfSystemProperty(named = "t6t.replay", matches = ".+")
class ClosureVerificationReplayLiveTest {

    private static final Pattern SCORE = Pattern.compile("SMOKE: (\\d+)/(\\d+)");

    @Test
    void noCompletionOnATreeTheYardstickDoesNotScoreInFull() throws Exception {
        LLMClient llm = new LLMClient(new LlmCircuitBreaker());
        ReflectionTestUtils.setField(llm, "apiUrl", System.getProperty("t6t.url", "http://localhost:11434/v1/chat/completions"));
        ReflectionTestUtils.setField(llm, "apiKey", "local");
        ReflectionTestUtils.setField(llm, "model", System.getProperty("t6t.model", "qwen3.8"));
        ReflectionTestUtils.setField(llm, "readTimeoutMs", 300_000);
        ReflectionTestUtils.setField(llm, "reasoningEffort", "none");
        ReflectionTestUtils.setField(llm, "readTimeoutEscalationFactor", 1.0);
        ReflectionTestUtils.setField(llm, "retryMaxAttempts", 1);
        Method init = LLMClient.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(llm);

        List<Path> runs;
        try (Stream<Path> s = Files.list(Path.of(System.getProperty("t6t.replay")))) {
            runs = s.filter(p -> Files.exists(p.resolve("yardstick.txt"))).sorted().toList();
        }
        int falseCompletions = 0, trueCompletions = 0, blockedFull = 0, deferredFull = 0, full = 0, partial = 0;
        List<String> lines = new ArrayList<>();
        for (Path run : runs) {
            Matcher m = SCORE.matcher(Files.readString(run.resolve("yardstick.txt")));
            boolean scored = m.find();
            boolean isFull = scored && m.group(1).equals(m.group(2));
            Path tree = run.resolve("tree");
            TestRunGate gate = mock(TestRunGate.class);
            Optional<TestRunReport> report = Files.exists(tree.resolve("TIMEOUT")) ? Optional.empty()
                    : SurefireReports.read(tree.resolve("target/surefire-reports"), 0L, tree);
            when(gate.freshReport()).thenReturn(report);
            CriteriaVerificationService service = new CriteriaVerificationService(llm, mock(AgentGraphService.class));
            ReflectionTestUtils.setField(service, "testRunGate", gate);
            service.setPromptTokenBudget(new PromptTokenBudget());
            List<String> criteria = Files.readAllLines(run.resolve("criteria.txt")).stream().filter(l -> !l.isBlank()).toList();
            // The closure's order: the test gate first -- a suite that hangs or fails is refused before the verifier.
            String decision;
            var v = com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService.CompletionVerification.abstained();
            if (report.isEmpty()) {
                decision = "REFUSED (the test gate: the suite did not finish or did not run)";
            } else if (!report.get().passes()) {
                decision = "REFUSED (the test gate: " + (report.get().failures() + report.get().errors()) + " failing)";
            } else {
                v = service.verifyCompletion(Files.readString(run.resolve("goal.txt")).strip(), criteria, files(tree));
                decision = !v.answered() ? "DEFERRED" : v.unconfirmed().isEmpty() ? "COMPLETED" : "BLOCKED";
            }
            if (isFull) {
                full++;
                if (decision.equals("COMPLETED")) trueCompletions++;
                else if (decision.startsWith("BLOCKED") || decision.startsWith("REFUSED")) blockedFull++;
                else deferredFull++;
            } else {
                partial++;
                if (decision.equals("COMPLETED")) falseCompletions++;
            }
            String line = "T6-T replay | " + run.getFileName() + " | " + (scored ? m.group(0) : "?") + " | " + decision
                    + (v.unconfirmed().map(u -> " | " + (u.length() > 160 ? u.substring(0, 160) : u)).orElse(""));
            lines.add(line);
            System.out.println(line);
        }
        String summary = "T6-T replay | full trees " + full + ": completed " + trueCompletions + ", blocked " + blockedFull
                + ", deferred " + deferredFull + " | partial trees " + partial + ": FALSE COMPLETIONS " + falseCompletions;
        System.out.println(summary);
        Files.write(Path.of(System.getProperty("t6t.replay")).resolve("RESULT.txt"),
                (String.join("\n", lines) + "\n" + summary + "\n").getBytes());
        assertThat(falseCompletions).isZero();
    }

    /** The tree as the loop holds it: every file under it, by relative path, without build output. */
    static Map<String, String> files(Path tree) throws Exception {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(tree)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String rel = tree.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("target/") || rel.equals("TIMEOUT")) continue;
                try {
                    out.put(rel, Files.readString(p));
                } catch (Exception binary) {
                    // not text: the loop does not hold it either
                }
            }
        }
        return out;
    }
}
