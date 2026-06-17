package com.walpro.autobuilder.agent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.walpro.autobuilder.analysis.structure.WiringEvidenceAnalyzer;
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
        List<LabeledCase> cases = HollowVsRealFixtures.all();

        // Two arms over the SAME corpus and model, differing only in the verifier's INPUT:
        //  - BASELINE: the flat file blob (verifyFeatures), what T4-BP-3 saw before T4-BS.
        //  - WITH WIRING: the same files plus the WiringEvidenceAnalyzer's structural notes
        //    (verifyCompletion, the production path). The delta is exactly T4-BS's contribution.
        Arm baseline = new Arm("BASELINE (flat blob)",
                (subject, criterion, output) -> {
                    Map<Integer, Boolean> p = realVerifier(false).verifyFeatures(subject, List.of(criterion), output);
                    return p.isEmpty() ? null : Boolean.TRUE.equals(p.get(1));
                });
        Arm wiring = new Arm("WITH T4-BS WIRING EVIDENCE",
                (subject, criterion, output) -> {
                    // verifyCompletion: present ⇒ unconfirmed (✗), empty ⇒ confirmed (✓).
                    return realVerifier(true).verifyCompletion(subject, List.of(criterion), output).isEmpty();
                });

        String reportBaseline = score(baseline, cases);
        String reportWiring = score(wiring, cases);
        System.out.println("\n========== T5-AF verifier precision/recall (model="
                + env("LLM_API_MODEL", "qwen3-coder:30b") + ") ==========");
        System.out.println(reportBaseline);
        System.out.println(reportWiring);

        // Measurement, not a gate: only fail if the endpoint produced no verdict at all.
        assertThat(baseline.tp + baseline.fp + baseline.tn + baseline.fn)
                .as("the local model produced at least one parseable verdict — is the endpoint up?")
                .isGreaterThan(0);
    }

    /** One arm = a way of asking the verifier whether a criterion is confirmed, plus its tally. */
    private static final class Arm {
        final String label;
        final Confirmer confirmer;
        int tp, fp, tn, fn, abstain;
        Arm(String label, Confirmer confirmer) { this.label = label; this.confirmer = confirmer; }
    }

    @FunctionalInterface
    private interface Confirmer {
        /** TRUE/FALSE = criterion graded ✓/✗; null = no evidence (LLM failure / unparseable). */
        Boolean confirmed(String subject, String criterion, Map<String, String> output);
    }

    private static String score(Arm arm, List<LabeledCase> cases) {
        StringBuilder report = new StringBuilder("--- ").append(arm.label).append(" ---\n");
        for (LabeledCase c : cases) {
            Boolean hollowConfirmed = arm.confirmer.confirmed(c.subject(), c.criterion(), c.hollow());
            Boolean realConfirmed = arm.confirmer.confirmed(c.subject(), c.criterion(), c.real());

            // Hollow: a correct verifier marks it UNCONFIRMED (✗). Confirmed ⇒ missed hollow (FN).
            if (hollowConfirmed == null) { arm.abstain++; report.append("  ").append(c.name()).append(" hollow: ABSTAIN\n"); }
            else if (!hollowConfirmed) { arm.tp++; report.append("  ").append(c.name()).append(" hollow: ✓ detected (TP)\n"); }
            else { arm.fn++; report.append("  ").append(c.name()).append(" hollow: ✗ MISSED (FN)\n"); }

            // Real: a correct verifier CONFIRMS it (✓). Unconfirmed ⇒ false alarm (FP).
            if (realConfirmed == null) { arm.abstain++; report.append("  ").append(c.name()).append(" real: ABSTAIN\n"); }
            else if (realConfirmed) { arm.tn++; report.append("  ").append(c.name()).append(" real: ✓ passed (TN)\n"); }
            else { arm.fp++; report.append("  ").append(c.name()).append(" real: ✗ FALSE ALARM (FP)\n"); }
        }
        double precision = (arm.tp + arm.fp) == 0 ? Double.NaN : (double) arm.tp / (arm.tp + arm.fp);
        double recall = (arm.tp + arm.fn) == 0 ? Double.NaN : (double) arm.tp / (arm.tp + arm.fn);
        report.append(String.format("  TP=%d FP=%d TN=%d FN=%d abstain=%d%n", arm.tp, arm.fp, arm.tn, arm.fn, arm.abstain));
        report.append(String.format("  precision (hollow flagged that were truly hollow) = %.2f%n", precision));
        report.append(String.format("  recall (truly-hollow that were flagged)           = %.2f%n", recall));
        return report.toString();
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
        // findCriterionVerdicts returns null (mock default) ⇒ no cache hit ⇒ every criterion is verified.
        AgentGraphService graph = mock(AgentGraphService.class);
        CriteriaVerificationService verifier = new CriteriaVerificationService(client, graph);
        if (withWiring) {
            verifier.setWiringEvidenceAnalyzer(new WiringEvidenceAnalyzer());
        }
        return verifier;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v != null && !v.isBlank() ? v : fallback;
    }
}
