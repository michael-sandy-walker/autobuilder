package com.walpro.autobuilder.agent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.walpro.autobuilder.analysis.structure.WiringFactsAnalyzer;
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
 * result, not the model's judgement.
 *
 * <p>Opt-in by contract: needs the local model up, so it is NOT a blocking CI gate. Enable with
 * {@code T5AF_VERIFIER_EVAL=1}; point it at the endpoint via {@code LLM_API_URL} / {@code LLM_API_MODEL}
 * / {@code OPENAI_API_KEY} (defaults mirror {@code application.yml}'s local Ollama config). It prints a
 * precision/recall report and is re-runnable as the model or the verifier prompt changes; it asserts
 * only that the endpoint produced results (a totally-down endpoint fails loudly), never a hard score
 * — the number is a measurement to track, not a pass/fail line.
 */
@Tag("verifier-eval")
@EnabledIfEnvironmentVariable(named = "T5AF_VERIFIER_EVAL", matches = "1")
class CompletionVerifierPrecisionEvalTest {

    @Test
    void scoreCompletionVerifierPrecisionRecallOnLabelledCorpus() {
        List<LabeledCase> cases = HollowVsRealFixtures.all();

        // Score the SAME corpus and model twice, differing only in the verifier's INPUT:
        //  - BASELINE: the flat file blob (verifyFeatures), what T4-BP-3 saw before T4-BS.
        //  - WITH WIRING: the same files plus the WiringFactsAnalyzer's structural notes
        //    (verifyCompletion, the production path). The delta is exactly T4-BS's contribution.
        Score baseline = score("BASELINE (flat blob)", cases, (subject, criterion, output) -> {
            Map<Integer, Boolean> p = realVerifier(false).verifyFeatures(subject, List.of(criterion), output);
            return p.isEmpty() ? null : Boolean.TRUE.equals(p.get(1));
        });
        Score wiring = score("WITH T4-BS WIRING FACTS", cases, (subject, criterion, output) -> {
            // verifyCompletion: blocked ⇒ unconfirmed (✗), all-clear ⇒ confirmed (✓), abstain ⇒ null.
            var v = realVerifier(true).verifyCompletion(subject, List.of(criterion), output);
            return !v.answered() ? null : v.unconfirmed().isEmpty();
        });

        System.out.println("\n========== T5-AF verifier precision/recall (model="
                + env("LLM_API_MODEL", "qwen3-coder:30b") + ") ==========");
        System.out.println(baseline.report());
        System.out.println(wiring.report());

        // Measurement, not a gate: only fail if the endpoint produced no answer at all.
        assertThat(baseline.results())
                .as("the local model produced at least one parseable result — is the endpoint up?")
                .isGreaterThan(0);
    }

    /** How to ask the verifier whether one criterion is confirmed against a given artifact. */
    @FunctionalInterface
    private interface Confirmer {
        /** TRUE/FALSE = criterion graded ✓/✗; null = no observation (LLM failure / unparseable). */
        Boolean confirmed(String subject, String criterion, Map<String, String> output);
    }

    /** The confusion matrix for one scoring run, with a formatted report. */
    private record Score(String label, int tp, int fp, int tn, int fn, int abstain, String detail) {
        int results() { return tp + fp + tn + fn; }
        String report() {
            double precision = (tp + fp) == 0 ? Double.NaN : (double) tp / (tp + fp);
            double recall = (tp + fn) == 0 ? Double.NaN : (double) tp / (tp + fn);
            return "--- " + label + " ---\n" + detail
                    + String.format("  TP=%d FP=%d TN=%d FN=%d abstain=%d%n", tp, fp, tn, fn, abstain)
                    + String.format("  precision (hollow flagged that were truly hollow) = %.2f%n", precision)
                    + String.format("  recall (truly-hollow that were flagged)           = %.2f%n", recall);
        }
    }

    private static Score score(String label, List<LabeledCase> cases, Confirmer confirmer) {
        int tp = 0, fp = 0, tn = 0, fn = 0, abstain = 0;
        StringBuilder detail = new StringBuilder();
        for (LabeledCase c : cases) {
            Boolean hollowConfirmed = confirmer.confirmed(c.subject(), c.criterion(), c.hollow());
            Boolean realConfirmed = confirmer.confirmed(c.subject(), c.criterion(), c.real());

            // Hollow: a correct verifier marks it UNCONFIRMED (✗). Confirmed ⇒ missed hollow (FN).
            if (hollowConfirmed == null) { abstain++; detail.append("  ").append(c.name()).append(" hollow: ABSTAIN\n"); }
            else if (!hollowConfirmed) { tp++; detail.append("  ").append(c.name()).append(" hollow: ✓ detected (TP)\n"); }
            else { fn++; detail.append("  ").append(c.name()).append(" hollow: ✗ MISSED (FN)\n"); }

            // Real: a correct verifier CONFIRMS it (✓). Unconfirmed ⇒ false alarm (FP).
            if (realConfirmed == null) { abstain++; detail.append("  ").append(c.name()).append(" real: ABSTAIN\n"); }
            else if (realConfirmed) { tn++; detail.append("  ").append(c.name()).append(" real: ✓ passed (TN)\n"); }
            else { fp++; detail.append("  ").append(c.name()).append(" real: ✗ FALSE ALARM (FP)\n"); }
        }
        return new Score(label, tp, fp, tn, fn, abstain, detail.toString());
    }

    private static CriteriaVerificationService realVerifier(boolean withWiring) {
        LLMClient client = new LLMClient(new LlmCircuitBreaker());
        ReflectionTestUtils.setField(client, "apiUrl",
                env("LLM_API_URL", "http://localhost:11434/v1/chat/completions"));
        ReflectionTestUtils.setField(client, "apiKey", env("OPENAI_API_KEY", "local-only-no-key"));
        ReflectionTestUtils.setField(client, "model", env("LLM_API_MODEL", "qwen3-coder:30b"));
        ReflectionTestUtils.setField(client, "readTimeoutMs", 120_000);
        ReflectionTestUtils.setField(client, "retryMaxAttempts", 1);
        ReflectionTestUtils.setField(client, "retryDelayMs", 1_000L);
        ReflectionTestUtils.invokeMethod(client, "init");
        // findCriterionResults returns null (mock default) ⇒ no cache hit ⇒ every criterion is verified.
        AgentGraphService graph = mock(AgentGraphService.class);
        CriteriaVerificationService verifier = new CriteriaVerificationService(client, graph);
        if (withWiring) {
            verifier.setWiringFactsAnalyzer(new WiringFactsAnalyzer());
        }
        return verifier;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v != null && !v.isBlank() ? v : fallback;
    }
}
