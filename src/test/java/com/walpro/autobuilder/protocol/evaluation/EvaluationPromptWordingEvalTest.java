package com.walpro.autobuilder.protocol.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.LlmCircuitBreaker;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.testsupport.HollowVsRealFixtures;
import com.walpro.autobuilder.testsupport.HollowVsRealFixtures.LabeledCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * T5-AG residue gate (evaluation-prompt wording — REAL local model, opt-in): the completion-verifier
 * eval ({@link com.walpro.autobuilder.agent.evaluation.CompletionVerifierPrecisionEvalTest}) never
 * renders {@link EvaluationProtocolContract} — the verifier builds its own prompt — so it cannot
 * gate wording changes to the EVALUATION agent's prompt. This eval does: it renders the REAL
 * evaluation prompt (same system + user composition as {@code EvaluationLLMAdapter}) over the
 * labelled hollow/real corpus, sends it to the local model at the production temperature (0.0),
 * parses with the REAL {@link EvaluationResponseParser}, and records the DECISIONS — score,
 * MutationAcceptanceState, GoalProgressState, per-criterion ✓/✗ marks — one row per case, to
 * {@code target/t5ag-evaluation-prompt-decisions.txt}.
 *
 * <p>Usage is a two-run comparison: run at the pre-change commit to capture a baseline file, apply
 * the prompt-wording change, run again, and diff the decision rows. States and criteria marks must
 * match per case for the wording change to ship; the free-prose Message is deliberately NOT
 * recorded (wording there may legitimately echo the changed example text).
 *
 * <p>Opt-in by contract (needs the local model up): enable with {@code T5AG_PROMPT_WORDING_EVAL=1};
 * endpoint via {@code LLM_API_URL} / {@code LLM_API_MODEL} / {@code OPENAI_API_KEY} (defaults
 * mirror application.yml's local Ollama config). Asserts only that the endpoint produced parseable
 * results — the comparison across runs is the actual gate.
 */
@Tag("prompt-wording-eval")
@EnabledIfEnvironmentVariable(named = "T5AG_PROMPT_WORDING_EVAL", matches = "1")
class EvaluationPromptWordingEvalTest {

    @Test
    void recordEvaluationDecisionsOnLabelledCorpus() throws IOException {
        LLMClient client = realClient();
        EvaluationResponseParser parser = new EvaluationResponseParser();

        StringBuilder rows = new StringBuilder("case|arm|score|mutationState|goalProgress|criteriaMarks\n");
        int parsed = 0;
        for (LabeledCase c : HollowVsRealFixtures.all()) {
            parsed += record(rows, client, parser, c, "hollow", c.hollow()) ? 1 : 0;
            parsed += record(rows, client, parser, c, "real", c.real()) ? 1 : 0;
        }

        Path out = Path.of("target", "t5ag-evaluation-prompt-decisions.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, rows.toString());

        System.out.println("\n========== T5-AG evaluation-prompt decision record (model="
                + env("LLM_API_MODEL", "qwen3-coder:30b") + ") ==========");
        System.out.println(rows);
        System.out.println("written to " + out.toAbsolutePath());

        assertThat(parsed)
                .as("the local model produced at least one parseable evaluation — is the endpoint up?")
                .isGreaterThan(0);
    }

    /** One model call for one (case, arm); appends a decision row. Returns whether it parsed. */
    private static boolean record(StringBuilder rows, LLMClient client, EvaluationResponseParser parser,
                                  LabeledCase c, String arm, Map<String, String> files) {
        String content;
        try {
            List<Message> messages = List.of(
                    new Message("system", EvaluationProtocolContract.getSystemPrompt()),
                    new Message("user", EvaluationProtocolContract.getUserPrompt(contextFor(c, files), 150)));
            ResponseEntity<ChatResponse> r = client.ask(messages);
            content = r.getBody() != null ? r.getBody().getFirstContent() : null;
        } catch (Exception e) {
            rows.append(c.name()).append('|').append(arm).append("|CALL_FAILED: ").append(e.getMessage()).append('\n');
            return false;
        }
        if (content == null || content.isBlank()) {
            rows.append(c.name()).append('|').append(arm).append("|EMPTY_RESPONSE\n");
            return false;
        }
        EvaluationOutcome o = parser.parse(content);
        String marks = parser.parseCriteriaAssessments(content).stream()
                .map(line -> line.contains("✓") ? "✓" : "✗")
                .reduce("", String::concat);
        rows.append(c.name()).append('|').append(arm)
                .append('|').append(o.score() == null ? "(none)" : String.format("%.2f", o.score()))
                .append('|').append(o.mutationState())
                .append('|').append(o.goalProgress())
                .append('|').append(marks.isEmpty() ? "(none)" : marks)
                .append('\n');
        return true;
    }

    /**
     * A minimal evaluation context for one labelled case: the case's files appear both as the
     * mutation's changed files (CURRENT FILE CONTENT block) and the previous output, the criterion
     * as the single acceptance criterion — the shape the criteria-grading rules exist to judge.
     */
    private static CycleContextData contextFor(LabeledCase c, Map<String, String> files) {
        return CycleContextData.builder()
                .cycleName("t5ag-prompt-wording-eval")
                .currentGoalDescription(c.subject())
                .currentSubGoalDescription(c.criterion())
                .currentGoalId("goal-eval")
                .currentSubGoalId("subgoal-eval")
                .currentSubGoalCriteria(List.of(c.criterion()))
                .previousOutput(files)
                .projectDiff(new ProjectDiffResult(null, files, Set.of()))
                .build();
    }

    private static LLMClient realClient() {
        LLMClient client = new LLMClient(new LlmCircuitBreaker());
        ReflectionTestUtils.setField(client, "apiUrl",
                env("LLM_API_URL", "http://localhost:11434/v1/chat/completions"));
        ReflectionTestUtils.setField(client, "apiKey", env("OPENAI_API_KEY", "local-only-no-key"));
        ReflectionTestUtils.setField(client, "model", env("LLM_API_MODEL", "qwen3-coder:30b"));
        ReflectionTestUtils.setField(client, "readTimeoutMs", 120_000);
        ReflectionTestUtils.setField(client, "retryMaxAttempts", 1);
        ReflectionTestUtils.setField(client, "retryDelayMs", 1_000L);
        ReflectionTestUtils.invokeMethod(client, "init");
        return client;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v != null && !v.isBlank() ? v : fallback;
    }
}
