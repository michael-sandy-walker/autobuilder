package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.core.build.TestRunGate;
import com.walpro.autobuilder.core.build.TestRunReport;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * T6-T: a tree too large for one verification prompt is verified in pieces that fit -- each criterion's files
 * whole, the rest referenced by outline -- and a claim the test run answers never reaches a model. Invented
 * coordinates; the shape is the ladder-rows closure of 2026-10-06 (26 files, 128,000 characters of prompt).
 */
class CriteriaVerificationDecentralizedTest {

    private final LLMClient llm = mock(LLMClient.class);
    private final AgentGraphService graph = mock(AgentGraphService.class);
    private final CriteriaVerificationService service = new CriteriaVerificationService(llm, graph);
    private final List<String> prompts = new ArrayList<>();

    private static final String A = "src/main/java/zeta/Inkwell.java";
    private static final String B = "src/main/java/zeta/Nib.java";
    private final Map<String, String> files = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        files.put(A, "package zeta;\npublic class Inkwell {\n    public int drops() { return 3; }\n" + "    // ink\n".repeat(300) + "}\n");
        files.put(B, "package zeta;\npublic class Nib {\n    public int width() { return 1; }\n" + "    // nib\n".repeat(300) + "}\n");
        service.setFileBudgetChars(4_000);  // one file fits, both do not
    }

    private void answer(Function<String, String> byPrompt) {
        doAnswer(inv -> {
            List<Message> m = inv.getArgument(0);
            String user = m.get(m.size() - 1).getContent();
            prompts.add(user);
            ChatResponse body = mock(ChatResponse.class);
            when(body.getFirstContent()).thenReturn(byPrompt.apply(user));
            return ResponseEntity.ok(body);
        }).when(llm).ask(anyList());
    }

    @Test
    void aTreeThatDoesNotFitIsVerifiedPerCriterion_eachCallShowingItsFilesWholeAndReferencingTheRest() {
        answer(p -> "1. ✓ ok");

        var result = service.verifyCompletion("ink", List.of("Inkwell holds three drops", "Nib is one wide"), files);

        assertThat(result.answered()).isTrue();
        assertThat(result.unconfirmed()).isEmpty();
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(0)).contains("=== " + A + " ===", "REFERENCED, NOT SHOWN", B).doesNotContain("=== " + B + " ===")
                .doesNotContain("truncated");
        assertThat(prompts.get(1)).contains("=== " + B + " ===").doesNotContain("=== " + A + " ===");
    }

    @Test
    void aCriterionThatNeedsAReferencedFileIsAskedAgainWithItWhole() {
        answer(p -> p.contains("=== " + B + " ===") ? "1. ✓ ok" : "1. ? needs " + B);

        var result = service.verifyCompletion("ink", List.of("Inkwell pours through the Nib"), Map.of(A, files.get(A), B, "package zeta;\npublic class Nib {}\n"));

        assertThat(result.answered()).isTrue();
        assertThat(result.unconfirmed()).isEmpty();
    }

    @Test
    void aCriterionNamingNoFileIsLocatedFromTheOutlinesFirst() {
        answer(p -> p.contains("declarations only):") && p.contains("<path>, <path>") ? "1. " + B
                : p.contains("=== " + B + " ===") && !p.contains("=== " + A + " ===") ? "1. ✗ too narrow" : "1. ✓ ok");

        var result = service.verifyCompletion("ink", List.of("the writing tip is one unit wide"), files);

        assertThat(result.unconfirmed()).isPresent();
        assertThat(prompts.get(0)).contains("declarations only");
    }

    @Test
    void aPassOnCroppedFilesStillDefers_aFailStillBlocks() {
        service.setFileBudgetChars(500);
        answer(p -> "1. ✓ ok");
        assertThat(service.verifyCompletion("ink", List.of("Inkwell holds three drops"), files).answered()).isFalse();

        prompts.clear();
        answer(p -> "1. ✗ no");
        assertThat(service.verifyCompletion("ink", List.of("Inkwell holds three drops"), files).unconfirmed()).isPresent();
    }

    /** Record first: a test claim the test run of this tree answers never reaches the model. */
    @Test
    void aTestClaimTheTestRunAnswersIsNeverAskedOfAModel() {
        TestRunGate gate = mock(TestRunGate.class);
        when(gate.freshReport()).thenReturn(Optional.of(new TestRunReport(4, 0, 0, 0, List.of(),
                Map.of("zeta.InkwellTest", List.of(new TestRunReport.Case("pours", TestRunReport.Outcome.PASSED))))));
        ReflectionTestUtils.setField(service, "testRunGate", gate);
        service.setFileBudgetChars(100_000);
        answer(p -> "1. ✓ ok");

        var result = service.verifyCompletion("ink", List.of("every test in InkwellTest passes", "Nib is one wide"), files);

        assertThat(result.unconfirmed()).isEmpty();
        assertThat(prompts).hasSize(1);
        assertThat(prompts.get(0)).contains("Nib is one wide").doesNotContain("every test in InkwellTest passes");
    }
    /** A test claim with the gate live and no report of this tree is not put to a model: the closure defers. */
    @Test
    void aTestClaimWithNoTestRunIsLeftUnanswered_notReadByAModel() {
        TestRunGate gate = mock(TestRunGate.class);
        when(gate.isLive()).thenReturn(true);
        when(gate.freshReport()).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(service, "testRunGate", gate);
        service.setFileBudgetChars(100_000);
        answer(p -> "1. ✓ ok");

        var result = service.verifyCompletion("ink", List.of("InkwellTest passes"), files);

        assertThat(result.answered()).isFalse();
        assertThat(prompts).isEmpty();
    }

    /** A criterion asking only for a file the tree does not have is outside the tree: named, never a deferral. */
    @Test
    void aCriterionAskingOnlyForAFileTheTreeLacksIsNamedOutsideTheTree_notLeftUnanswered() {
        answer(p -> p.contains("Inkwell holds three drops") ? "1. ✓ ok" : "1. ? needs tally_tool.py");

        var result = service.verifyCompletion("ink",
                List.of("Inkwell holds three drops", "the share measured by tally_tool.py is 18%"), files);

        assertThat(result.answered()).isTrue();
        assertThat(result.unconfirmed()).isEmpty();
        assertThat(result.outsideTree()).singleElement().asString()
                .contains("the share measured by tally_tool.py is 18%", "tally_tool.py", "does not have");
    }

    /** A bare file name of a file the tree has is that file, asked again whole -- never "outside the tree". */
    @Test
    void aBareNameOfATreeFileIsThatFile_askedAgainWhole() {
        answer(p -> p.contains("=== " + B + " ===") ? "1. ✓ ok" : "1. ? needs Nib.java");

        var result = service.verifyCompletion("ink", List.of("Inkwell pours through the tip"),
                Map.of(A, files.get(A), B, "package zeta;\npublic class Nib {}\n"));

        assertThat(result.answered()).isTrue();
        assertThat(result.outsideTree()).isEmpty();
        assertThat(prompts).anyMatch(p -> p.contains("=== " + B + " ==="));
    }

    @Test
    void absentNeedsKeepsOnlyNamesNoFileOfTheTreeHas() {
        String raw = "1. ? needs tally_tool.py\n2. ? needs Nib.java\n3. ? needs " + A
                + "\n4. ? needs src/main/java/\n5. ? needs zeta\n6. ? needs PROJECT TEST RUN\n7. ? needs docs/notes"
                + "\n8. ? needs src/main/java/zeta/Blotter.java\n9. ? needs tools/tally_tool.py";
        assertThat(CriteriaVerificationService.absentNeeds(raw, 9, files))
                .as("a folder of the tree, a file missing from one of its folders, and anything not written as a file"
                        + " path, are not outside the tree").containsOnlyKeys(1, 9);
        assertThat(CriteriaVerificationService.looksLikeFile("scripts/tally_tool.py")).isTrue();
        assertThat(CriteriaVerificationService.looksLikeFile("PROJECT TEST RUN")).isFalse();
        assertThat(CriteriaVerificationService.looksLikeFile(".gitignore")).isFalse();
        assertThat(CriteriaVerificationService.parseNeeds(raw, 9, files)).containsOnlyKeys(2, 3);
    }

    /** A second opinion leaves the cache of this code state unread: rule-extraction run 2026-10-19_12-46-09. */
    @Test
    void aSecondOpinionIsAskedAfresh_theCachedVerdictUnread() {
        service.setFileBudgetChars(100_000);
        when(graph.findCriterionResults(org.mockito.ArgumentMatchers.anyCollection(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Map.of(com.walpro.autobuilder.graph.evaluation.CriterionResultEntity.keyOf("Nib is one wide"), false));
        answer(p -> "1. ✓ ok");

        assertThat(service.verifyCompletion("ink", List.of("Nib is one wide"), files).unconfirmed()).isPresent();
        assertThat(prompts).as("answered by the cache").isEmpty();

        var second = service.verifySecondOpinion("ink", List.of("Nib is one wide"), files);
        assertThat(second.answered()).isTrue();
        assertThat(second.unconfirmed()).isEmpty();
        assertThat(prompts).hasSize(1);
    }
}
