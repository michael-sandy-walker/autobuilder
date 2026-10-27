package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.evaluation.CriterionResultEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CriteriaVerificationServiceTest {

    @Mock private LLMClient llmClient;
    @Mock private AgentGraphService agentGraphService;

    @InjectMocks private CriteriaVerificationService service;

    private static CycleContextData ctx(List<String> criteria, Map<String, String> output) {
        return CycleContextData.builder()
                .cycleName("cycle-7")
                .currentSubGoalId("sg-1")
                .currentSubGoalDescription("Validate loan return")
                .currentSubGoalCriteria(criteria)
                .previousOutput(output)
                .build();
    }

    private void stubLlm(String content) {
        ChatResponse body = mock(ChatResponse.class);
        when(body.getFirstContent()).thenReturn(content);
        when(llmClient.ask(anyList())).thenReturn(ResponseEntity.ok(body));
    }

    /** The user half of the single prompt this service sends. */
    private String promptSent() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.walpro.autobuilder.client.dto.Message>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(llmClient).ask(captor.capture());
        return captor.getValue().get(1).getContent();
    }

    @Test
    void verifyAndRecord_includesWiringFactsInPrompt_whenAnalyzerPresent() {
        // Run 18 (2026-07-06): without the facts, the per-subgoal check judged status-code
        // criteria from raw files and marked working behaviour unmet; the banked ✗ walled the
        // subgoal into a root give-up. The per-subgoal route gets the same facts block the
        // completion gate already uses.
        stubLlm("1. ✓ Returns 422 when the loan limit is exceeded");
        var analyzer = mock(com.walpro.autobuilder.analysis.structure.WiringFactsAnalyzer.class);
        when(analyzer.asPromptBlock(any())).thenReturn(
                "STRUCTURAL WIRING NOTES:\n- MaxActiveLoansExceededException is mapped to status 422");
        service.setWiringFactsAnalyzer(analyzer);

        service.verifyAndRecord(
                ctx(List.of("Returns 422 when the loan limit is exceeded"),
                        Map.of("LoanController.java", "class LoanController {}")),
                "sg-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.walpro.autobuilder.client.dto.Message>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(llmClient).ask(captor.capture());
        assertThat(captor.getValue().get(1).getContent())
                .contains("MaxActiveLoansExceededException is mapped to status 422");
    }

    @Test
    void verifies_records_and_returnsPassMap() {
        stubLlm("1. ✓ Returns 404 when loan missing\n2. ✗ Returns 409 when already returned");
        var context = ctx(
                List.of("Returns 404 when loan missing", "Returns 409 when already returned"),
                Map.of("LoanService.java", "class LoanService {}"));

        Map<Integer, Boolean> result = service.verifyAndRecord(context, "sg-1");

        assertThat(result).containsEntry(1, true).containsEntry(2, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CriterionResultEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(agentGraphService).linkCriterionResults(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0).subgoalId()).isEqualTo("sg-1");
        assertThat(captor.getValue().get(0).criterionKey()).isNotBlank();
    }

    @Test
    void verificationPrompt_carriesBehaviouralGradingRule() {
        // T2-ED: criteria naming internal structure (method/field/class names) leak through
        // creation-side prompt steering (observed twice: 00:03 evaluator path, 13:26 planner
        // nested-injection path). Grading is the one downstream point all criteria pass through,
        // so the system prompt must instruct grading the implied behaviour, not the identifier.
        stubLlm("1. ✓ ServiceX.findItemsByOwner() correctly retrieves items");
        var context = ctx(
                List.of("ServiceX.findItemsByOwner() correctly retrieves items"),
                Map.of("OwnerService.java", "class OwnerService { List<Item> itemsFor(long ownerId) {...} }"));

        service.verifyAndRecord(context, "sg-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.walpro.autobuilder.client.dto.Message>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(llmClient).ask(captor.capture());
        String systemPrompt = captor.getValue().get(0).getContent();
        assertThat(systemPrompt)
                .contains("BEHAVIOURAL GRADING RULE")
                .contains("NOT the literal identifier")
                .contains("regardless of method name");
    }

    @Test
    void verifyCompletion_feedsStructuralWiringNotesIntoPrompt() {
        // T4-BS: when a WiringFactsAnalyzer is wired, the completion gate must put its concrete
        // facts (stub bodies, never-assigned fields) into the verifier's prompt so it grades against
        // wiring rather than presence-matching over a flat blob.
        stubLlm("1. ✗ checkout returns a computed price");
        when(agentGraphService.findCriterionResults(any(), anyString())).thenReturn(Map.of());
        service.setWiringFactsAnalyzer(new com.walpro.autobuilder.analysis.structure.WiringFactsAnalyzer());

        Map<String, String> hollow = Map.of("src/main/java/OrderService.java", """
                package a;
                public class OrderService {
                    public Object checkout(Long cartId) { return null; }
                }
                """);

        var unconfirmed = service.verifyCompletion(
                "checkout endpoint", List.of("checkout returns a computed price"), hollow).unconfirmed();
        assertThat(unconfirmed).isPresent();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.walpro.autobuilder.client.dto.Message>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(llmClient).ask(captor.capture());
        String userPrompt = captor.getValue().get(1).getContent();
        assertThat(userPrompt)
                .contains("STRUCTURAL WIRING NOTES")
                .contains("stub");
    }

    @Test
    void verifyCompletion_withoutAnalyzer_omitsWiringNotes() {
        // The analyzer is optional — absent it, the prompt is unchanged (no empty header).
        stubLlm("1. ✓ checkout returns a computed price");
        when(agentGraphService.findCriterionResults(any(), anyString())).thenReturn(Map.of());

        service.verifyCompletion("checkout endpoint",
                List.of("checkout returns a computed price"),
                Map.of("src/main/java/OrderService.java", "class OrderService {}"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.walpro.autobuilder.client.dto.Message>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(llmClient).ask(captor.capture());
        assertThat(captor.getValue().get(1).getContent()).doesNotContain("STRUCTURAL WIRING NOTES");
    }

    @Test
    void noCriteria_verifiesTheDescriptionAsSingleFeature() {
        // Criteria-less subgoal: the description is the only requirement statement there is —
        // it gets verified as feature 1 instead of skipping (2026-06-11 22:55 field case).
        stubLlm("1. \u2713 Validate loan return");

        Map<Integer, Boolean> result = service.verifyAndRecord(
                ctx(List.of(), Map.of("A.java", "x")), "sg-1");

        assertThat(result).isEqualTo(Map.of(1, true));
    }

    @Test
    void noCriteriaAndNoDescription_skipsLlmEntirely() {
        CycleContextData bare = CycleContextData.builder()
                .cycleName("cycle-7")
                .currentSubGoalId("sg-1")
                .currentSubGoalCriteria(List.of())
                .previousOutput(Map.of("A.java", "x"))
                .build();

        assertThat(service.verifyAndRecord(bare, "sg-1")).isEmpty();
        verify(llmClient, never()).ask(anyList());
    }

    @Test
    void noOutputFiles_skipsLlmEntirely() {
        Map<Integer, Boolean> result = service.verifyAndRecord(
                ctx(List.of("c1"), Map.of()), "sg-1");

        assertThat(result).isEmpty();
        verify(llmClient, never()).ask(anyList());
    }

    @Test
    void llmFailure_returnsEmpty_recordsNothing() {
        when(llmClient.ask(anyList())).thenThrow(new RuntimeException("network"));

        Map<Integer, Boolean> result = service.verifyAndRecord(
                ctx(List.of("c1"), Map.of("A.java", "x")), "sg-1");

        assertThat(result).isEmpty();
        verify(agentGraphService, never()).linkCriterionResults(any());
    }

    @Test
    void unparseableResponse_returnsEmpty() {
        stubLlm("The code looks fine to me overall.");

        Map<Integer, Boolean> result = service.verifyAndRecord(
                ctx(List.of("c1"), Map.of("A.java", "x")), "sg-1");

        assertThat(result).isEmpty();
        verify(agentGraphService, never()).linkCriterionResults(any());
    }

    @Test
    void outOfRangeIndices_areIgnored() {
        stubLlm("1. ✓ real criterion\n9. ✓ hallucinated criterion");

        Map<Integer, Boolean> result = service.verifyAndRecord(
                ctx(List.of("real criterion"), Map.of("A.java", "x")), "sg-1");

        assertThat(result).containsOnlyKeys(1);
    }

    @Test
    void verifyFeatures_directCall_parsesPassMap_withoutGraphRecording() {
        stubLlm("1. ✓ GET /books/{id} implemented\n2. ✗ loan limit not enforced");

        Map<Integer, Boolean> result = service.verifyFeatures(
                "Library API", List.of("GET /books/{id}", "loan limit"), Map.of("A.java", "code"));

        assertThat(result).containsEntry(1, true).containsEntry(2, false);
        verify(agentGraphService, never()).linkCriterionResults(any());
    }

    @Test
    void verifyCompletion_returnsUnconfirmed_whenACriterionFails() {
        // T4-BP step 3: a hollow `checkout` returning null does not satisfy its criterion.
        stubLlm("1. ✓ products are listed\n2. ✗ checkout returns an OrderResponse with a PriceBreakdown");

        var unconfirmed = service.verifyCompletion(
                "Order checkout API",
                List.of("products are listed", "checkout returns an OrderResponse with a PriceBreakdown"),
                Map.of("OrderService.java", "class OrderService { Object checkout(Long id){return null;} }")).unconfirmed();

        assertThat(unconfirmed).isPresent();
        assertThat(unconfirmed.get()).contains("not confirmed").contains("checkout returns an OrderResponse");
    }

    /**
     * T2-FI: the regression that started the ticket. `Main.java` was 215 lines and the old cap was
     * 150, so the method computing the value the requirement asked about was cut out of the prompt
     * and the model answered from the requirement text instead. A file this size must now arrive
     * whole.
     */
    @Test
    void aFileLongerThanTheOldLineCapIsSentWhole() {
        String main = ("line\n".repeat(214) + "int line = 1; // the part the old cap cut");
        stubLlm("1. ✓ it counts physical lines");

        service.verifyCompletion("CLI", List.of("it counts physical lines"), Map.of("Main.java", main));

        assertThat(promptSent()).contains("the part the old cap cut").doesNotContain("truncated");
    }

    /**
     * ⚠ THE ASYMMETRY, and it is the point of the fix rather than a detail. An all-clear on evidence
     * the prompt could not carry is what closes a root on a requirement nobody checked, so it defers
     * instead. A BLOCKED verdict is left alone below, because its cost is recoverable.
     */
    @Test
    void verifyCompletion_abstains_whenEveryCriterionPassesButTheEvidenceWasCropped() {
        service.setFileBudgetChars(200);
        stubLlm("1. ✓ it counts physical lines");

        var result = service.verifyCompletion("CLI", List.of("it counts physical lines"),
                Map.of("Main.java", "x".repeat(5_000)));

        assertThat(result.answered()).isFalse();
        assertThat(result.unconfirmed()).isEmpty();
        verify(agentGraphService, never()).recordCompletionResults(anyList());
    }

    @Test
    void verifyCompletion_stillBlocks_whenACriterionFailsOnCroppedEvidence() {
        service.setFileBudgetChars(200);
        stubLlm("1. ✗ it counts physical lines");

        var result = service.verifyCompletion("CLI", List.of("it counts physical lines"),
                Map.of("Main.java", "x".repeat(5_000)));

        assertThat(result.answered()).isTrue();
        assertThat(result.unconfirmed()).isPresent();
    }

    /** The banking path refuses for the same reason: a cropped pass would outlive its prompt. */
    @Test
    void verifyAndRecord_banksNothing_whenTheEvidenceWasCropped() {
        service.setFileBudgetChars(200);
        stubLlm("1. ✓ it counts physical lines");

        var out = service.verifyAndRecord(
                ctx(List.of("it counts physical lines"), Map.of("Main.java", "x".repeat(5_000))), "sg-1");

        assertThat(out).isEmpty();
        verifyNoInteractions(agentGraphService);
    }

    @Test
    void verifyCompletion_empty_whenAllConfirmed() {
        stubLlm("1. ✓ products are listed");
        assertThat(service.verifyCompletion("API", List.of("products are listed"),
                Map.of("A.java", "code")).unconfirmed()).isEmpty();
    }

    @Test
    void verifyCompletion_skipsLlm_whenGraphHasResultForThisCodeState() {
        // T4-BR: a criterion already judged at this exact code state is read from the graph-backed
        // cache (no LLM call). A cached failing result still surfaces as unconfirmed.
        String criterion = "checkout computes the price";
        String key = com.walpro.autobuilder.graph.evaluation.CriterionResultEntity.keyOf(criterion);
        when(agentGraphService.findCriterionResults(anyCollection(), anyString()))
                .thenReturn(Map.of(key, false));

        var unconfirmed = service.verifyCompletion("API", List.of(criterion),
                Map.of("OrderService.java", "class OrderService {}")).unconfirmed();

        assertThat(unconfirmed).isPresent();
        verify(llmClient, never()).ask(anyList());
        verify(agentGraphService, never()).recordCompletionResults(any());
        assertThat(service.completionVerificationCallCount()).isZero();
    }

    @Test
    void verifyCompletion_verifiesAndRecords_whenGraphHasNoResult() {
        // T4-BR: no cached result ⇒ one LLM pass, and the fresh results are persisted to the graph.
        stubLlm("1. ✓ products are listed");

        service.verifyCompletion("API", List.of("products are listed"), Map.of("A.java", "code"));

        verify(llmClient, org.mockito.Mockito.times(1)).ask(anyList());
        verify(agentGraphService).recordCompletionResults(any());
        assertThat(service.completionVerificationCallCount()).isEqualTo(1);
    }

    @Test
    void verifyCompletion_vacuousAllClear_whenNothingToVerify() {
        // Empty criteria or empty output ⇒ nothing to verify: a vacuous all-clear (no LLM call),
        // preserving long-standing closure behaviour for criteria-less roots.
        assertThat(service.verifyCompletion("API", List.of(), Map.of("A.java", "x")).answered()).isTrue();
        assertThat(service.verifyCompletion("API", List.of("c"), Map.of()).unconfirmed()).isEmpty();
        verify(llmClient, never()).ask(anyList());
    }

    @Test
    void verifyCompletion_abstains_whenLlmGivesNoResult() {
        // 2026-07-02 false completion #2 class: an LLM failure is an ABSTAIN the completion edge
        // must defer on — answered=false, never an all-clear.
        when(llmClient.ask(anyList())).thenThrow(new RuntimeException("Read timed out"));

        var verification = service.verifyCompletion("API", List.of("c"), Map.of("A.java", "x"));

        assertThat(verification.answered()).isFalse();
        assertThat(verification.unconfirmed()).isEmpty();
    }

    @Test
    void aSignatureClaimIsAnsweredFromTheAdoptedTextWithoutAModel() throws Exception {
        String path = "src/main/java/org/warp/Coil.java";
        String adopted = "package org.warp;\npublic class Coil {\n    public int turns() { return 1; }\n}\n";
        var files = mock(com.walpro.autobuilder.core.service.FileService.class);
        when(files.exists(com.walpro.autobuilder.core.build.ProtectedPaths.BASELINE_DIR)).thenReturn(true);
        when(files.listAllRelativeFilePathsUnder(com.walpro.autobuilder.core.build.ProtectedPaths.BASELINE_DIR)).thenReturn(List.of(path));
        when(files.readString(com.walpro.autobuilder.core.build.ProtectedPaths.BASELINE_DIR + "/" + path)).thenReturn(adopted);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "fileService", files);
        String claim = "No existing public method in Coil.java has been renamed, removed, or had its signature changed";

        assertThat(service.structuralAnswer(claim, "Grow the coil", Map.of(path, adopted.replace("public int turns() { return 1; }", ""))))
                .isFalse();
        assertThat(service.structuralAnswer(claim, "Grow the coil", Map.of(path, adopted))).isTrue();
        verifyNoInteractions(llmClient);
    }

    /** field112: ~130 frozen derived tests took the file budget; every closure check cropped the sources out. */
    @Test
    void theTestsWrittenFromTheRequirementsAreLeftOutOfTheFilesAVerificationReads() throws Exception {
        stubLlm("1. \u2713 Hunks are applied in order");
        var files = mock(com.walpro.autobuilder.core.service.FileService.class);
        String frozen = "src/test/java/org/ink/DerivedClaim001Test.java";
        when(files.exists(com.walpro.autobuilder.core.build.DerivedTestsFile.PATH)).thenReturn(true);
        when(files.readFile(com.walpro.autobuilder.core.build.DerivedTestsFile.PATH)).thenReturn(
                "[{\"requirement\":\"r\",\"claim\":\"c\",\"path\":\"" + frozen + "\",\"content\":\"x\","
                        + "\"status\":\"ACCEPTED\",\"reason\":\"y\"},"
                        + "{\"requirement\":\"r\",\"claim\":\"d\",\"path\":\"src/test/java/org/ink/DerivedClaim002Test.java\","
                        + "\"content\":\"x\",\"status\":\"UNACCEPTED\",\"reason\":\"y\"}]");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "fileService", files);

        service.verifyAndRecord(ctx(List.of("Hunks are applied in order"), Map.of(
                "src/main/java/org/ink/Hunks.java", "class Hunks { /* SOURCE */ }",
                frozen, "class DerivedClaim001Test { /* FROZEN */ }",
                "src/test/java/org/ink/HunksTest.java", "class HunksTest { /* OWN */ }")), "sg-1");

        assertThat(promptSent()).contains("SOURCE", "OWN").doesNotContain("FROZEN")
                .contains("1 test file(s) written from the requirements' wording before the change are in the tree");
    }
}
