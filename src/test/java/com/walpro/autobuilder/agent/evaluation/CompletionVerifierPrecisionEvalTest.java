package com.walpro.autobuilder.agent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.LlmCircuitBreaker;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.testsupport.HollowVsRealFixtures;
import com.walpro.autobuilder.testsupport.HollowVsRealFixtures.LabeledCase;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * T5-AF layer 2 (verifier precision — REAL local model, opt-in): runs the genuine
 * {@link CriteriaVerificationService#verifyFeatures} over the labelled hollow/real corpus
 * ({@link HollowVsRealFixtures}) and scores precision/recall on the "is this artifact hollow?"
 * detection. This is the layer that actually answers the question nondeterminism leaves open —
 * "does T4-BP-3 catch a hollow artifact?" — because layer 1 only proves the wiring around a canned
 * verdict, not the model's judgement.
 *
 * <p>Opt-in by contract: needs the local model up, so it is NOT a blocking CI gate. Enable with
 * {@code T5AF_VERIFIER_EVAL=1}; point it at the endpoint via {@code LLM_API_URL} / {@code LLM_API_MODEL}
 * / {@code OPENAI_API_KEY} (defaults mirror {@code application.yml}'s local Ollama config). It prints a
 * precision/recall report and is re-runnable as the model or the verifier prompt changes; it asserts
 * only that the endpoint produced verdicts (a totally-down endpoint fails loudly), never a hard score
 * — the number is a measurement to track, not a pass/fail line.
 */
@Tag("verifier-eval")
@EnabledIfEnvironmentVariable(named = "T5AF_VERIFIER_EVAL", matches = "1")
class CompletionVerifierPrecisionEvalTest {

    @Test
    void scoreCompletionVerifierPrecisionRecallOnLabelledCorpus() {
        CriteriaVerificationService verifier = realVerifier();
        List<LabeledCase> cases = HollowVsRealFixtures.all();

        // Confusion matrix for the "hollow detected" positive class (mirrors the completion-gate
        // semantics: a criterion graded ✓ is "confirmed", anything else is "unconfirmed" = blocked).
        int tp = 0, fp = 0, tn = 0, fn = 0, abstain = 0;
        StringBuilder report = new StringBuilder("\n=== T5-AF verifier precision/recall ===\n");

        for (LabeledCase c : cases) {
            Boolean hollowConfirmed = confirmed(verifier, c.subject(), c.criterion(), c.hollow());
            Boolean realConfirmed = confirmed(verifier, c.subject(), c.criterion(), c.real());

            // Hollow: a correct verifier marks it UNCONFIRMED (✗). Confirmed ⇒ missed hollow (FN).
            if (hollowConfirmed == null) { abstain++; report.append("  ").append(c.name()).append(" hollow: ABSTAIN\n"); }
            else if (!hollowConfirmed) { tp++; report.append("  ").append(c.name()).append(" hollow: ✓ detected (TP)\n"); }
            else { fn++; report.append("  ").append(c.name()).append(" hollow: ✗ MISSED (FN)\n"); }

            // Real: a correct verifier CONFIRMS it (✓). Unconfirmed ⇒ false alarm (FP).
            if (realConfirmed == null) { abstain++; report.append("  ").append(c.name()).append(" real: ABSTAIN\n"); }
            else if (realConfirmed) { tn++; report.append("  ").append(c.name()).append(" real: ✓ passed (TN)\n"); }
            else { fp++; report.append("  ").append(c.name()).append(" real: ✗ FALSE ALARM (FP)\n"); }
        }

        double precision = (tp + fp) == 0 ? Double.NaN : (double) tp / (tp + fp);
        double recall = (tp + fn) == 0 ? Double.NaN : (double) tp / (tp + fn);
        report.append(String.format("  TP=%d FP=%d TN=%d FN=%d abstain=%d%n", tp, fp, tn, fn, abstain));
        report.append(String.format("  precision (hollow flagged that were truly hollow) = %.2f%n", precision));
        report.append(String.format("  recall (truly-hollow that were flagged)           = %.2f%n", recall));
        System.out.println(report);

        // Measurement, not a gate: only fail if the endpoint produced no verdict at all.
        assertThat(tp + fp + tn + fn)
                .as("the local model produced at least one parseable verdict — is the endpoint up?")
                .isGreaterThan(0);
    }

    /** TRUE/FALSE = the criterion was graded ✓/✗; null = no evidence (LLM failure / unparseable). */
    private static Boolean confirmed(CriteriaVerificationService verifier, String subject,
                                     String criterion, Map<String, String> output) {
        Map<Integer, Boolean> pass = verifier.verifyFeatures(subject, List.of(criterion), output);
        if (pass.isEmpty()) return null;
        return Boolean.TRUE.equals(pass.get(1));
    }

    private static CriteriaVerificationService realVerifier() {
        LLMClient client = new LLMClient(new LlmCircuitBreaker());
        ReflectionTestUtils.setField(client, "apiUrl",
                env("LLM_API_URL", "http://localhost:11434/v1/chat/completions"));
        ReflectionTestUtils.setField(client, "apiKey", env("OPENAI_API_KEY", "local-only-no-key"));
        ReflectionTestUtils.setField(client, "model", env("LLM_API_MODEL", "qwen3-coder:30b"));
        ReflectionTestUtils.setField(client, "readTimeoutMs", 120_000);
        ReflectionTestUtils.setField(client, "retryMaxAttempts", 1);
        ReflectionTestUtils.setField(client, "retryDelayMs", 1_000L);
        ReflectionTestUtils.invokeMethod(client, "init");
        // verifyFeatures never touches the graph — a mock satisfies the constructor.
        return new CriteriaVerificationService(client, mock(AgentGraphService.class));
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v != null && !v.isBlank() ? v : fallback;
    }
}
