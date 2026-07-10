package com.walpro.autobuilder.agent.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.agent.analysis.model.AnalysisOutcome;
import com.walpro.autobuilder.core.grounding.SymbolGroundingService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalKindClassifier;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalSimilarityService;

/**
 * T2-DC: regression test for the 2026-05-07 23:51 hallucinated-symbol pathology.
 *
 * Analysis agent emits a subgoal description like "Add missing import for BookAlreadyLoanedException"
 * citing a class that does not exist on disk. Grounding DETECTS the undefined symbol and prepends
 * the acceptance criterion "Create BookAlreadyLoanedException.java" — but the stored description and
 * criteria are persisted MARKER-FREE (the [UNDEFINED] marker is a render-time prompt hint re-applied
 * by refreshGoalAnnotations; baking it into stored text made criteria verify literally and never
 * pass, manufacturing permanent root-closure blockers — observed 2026-06-16).
 */
@ExtendWith(MockitoExtension.class)
class AnalysisAgentGroundingTest {

    @TempDir
    Path tempDir;

    @Mock private AnalysisLLMAdapter adapter;
    @Mock private GoalPlannerService goalPlannerService;
    @Mock private GoalKindClassifier kindClassifier;
    @Mock private GoalSimilarityService similarityService;

    private SymbolGroundingService groundingService;
    private AnalysisAgent agent;

    @BeforeEach
    void setUp() throws IOException {
        // Empty output/ — no symbols defined, so anything cited is "undefined".
        Files.createDirectories(tempDir.resolve("output"));
        FileService fileService = new FileService(tempDir.toString(), new ObjectMapper());
        groundingService = new SymbolGroundingService(fileService);

        agent = new AnalysisAgent(adapter, goalPlannerService, kindClassifier, similarityService, groundingService);
    }

    @Test
    void execute_groundsHallucinatedSymbol_andPrependsCreateCriterion() {
        // Arrange: analysis outcome cites a non-existent class.
        String description = "Add missing import for BookAlreadyLoanedException";
        AnalysisOutcome rawOutcome = new AnalysisOutcome(
                new ArrayList<>(), new ArrayList<>(),
                List.of(description),
                Map.of(description, List.of("Import resolves and the file compiles")),
                "ok", "EXECUTABLE", false, false);

        when(adapter.analyze(any(CycleContextData.class))).thenReturn(rawOutcome);

        // Goal-planner stubs: a current subgoal exists so injection proceeds.
        when(goalPlannerService.getGoalMetadataInt(eq("parent-1"), anyString())).thenReturn(0);
        when(goalPlannerService.countUnmetCriteria("parent-1")).thenReturn(2);
        when(goalPlannerService.getGlobalAnalysisInjectionBudget()).thenReturn(10);
        when(goalPlannerService.getAllGoals()).thenReturn(List.of());

        // Kind classifier accepts the proposed (now-grounded) description as EXECUTABLE.
        when(kindClassifier.classify(anyString())).thenReturn(GoalKindClassifier.GoalKind.EXECUTABLE);
        lenient().when(similarityService.isDuplicate(anyString())).thenReturn(false);

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .currentSubGoalId("parent-1")
                .build();

        // Act
        CycleContextData result = agent.execute(ctx);

        // Assert: addSubGoal received the MARKER-FREE description (grounding detects the undefined
        // symbol but does not bake the [UNDEFINED] marker into the persisted requirement).
        ArgumentCaptor<String> descCaptor = ArgumentCaptor.forClass(String.class);
        verify(goalPlannerService).addSubGoal(
                anyString(), descCaptor.capture(), any(Boolean.class),
                eq("parent-1"), eq("analysis"), eq(GoalKindClassifier.GoalKind.EXECUTABLE));
        String injectedDesc = descCaptor.getValue();
        assertThat(injectedDesc).isEqualTo("Add missing import for BookAlreadyLoanedException");
        assertThat(injectedDesc).doesNotContain("[UNDEFINED]");

        // Assert: the first acceptance criterion frames the work as creating the missing file.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> criteriaCaptor = ArgumentCaptor.forClass(List.class);
        verify(goalPlannerService).setAcceptanceCriteria(anyString(), criteriaCaptor.capture());
        List<String> criteria = criteriaCaptor.getValue();
        assertThat(criteria).isNotEmpty();
        assertThat(criteria.get(0)).isEqualTo("Create BookAlreadyLoanedException.java");
        assertThat(criteria).contains("Import resolves and the file compiles");

        // Assert: a MISSING_SYMBOL request was issued naming the symbol.
        assertThat(result.hasOpenRequest(BasicSignals.MISSING_SYMBOL))
                .as("MISSING_SYMBOL issued for the hallucinated identifier")
                .isTrue();
        assertThat(result.peekRequest(BasicSignals.MISSING_SYMBOL).source())
                .contains("BookAlreadyLoanedException");

        // Assert: the undefined symbol is persisted to the new goal's metadata so future
        // heuristics (T2-DD) can query repeated hallucinations cross-cycle.
        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> symCaptor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(goalPlannerService).setUndefinedSymbols(idCaptor.capture(), symCaptor.capture());
        assertThat(symCaptor.getValue()).contains("BookAlreadyLoanedException");
    }

    @Test
    void execute_criterionCitingUndefinedSymbol_isStoredMarkerFree() {
        // Regression for 2026-06-16: a subgoal naming an undefined type was stored with the
        // [UNDEFINED] marker baked into both its description and criteria ("Implement the
        // ProductRepository [UNDEFINED] interface extending JpaRepository [UNDEFINED]"), so they
        // verified literally and could never pass — a permanent root-closure blocker that drove the
        // run to GIVEN_UP. The marker must stay out of the persisted description and criteria.
        String description = "Implement the ProductRepository interface extending JpaRepository";
        String criterion = "The ProductRepository interface is defined and accessible";
        AnalysisOutcome rawOutcome = new AnalysisOutcome(
                new ArrayList<>(), new ArrayList<>(),
                List.of(description),
                Map.of(description, List.of(criterion)),
                "ok", "EXECUTABLE", false, false);

        when(adapter.analyze(any(CycleContextData.class))).thenReturn(rawOutcome);
        when(goalPlannerService.getGoalMetadataInt(eq("parent-1"), anyString())).thenReturn(0);
        when(goalPlannerService.countUnmetCriteria("parent-1")).thenReturn(2);
        when(goalPlannerService.getGlobalAnalysisInjectionBudget()).thenReturn(10);
        when(goalPlannerService.getAllGoals()).thenReturn(List.of());
        when(kindClassifier.classify(anyString())).thenReturn(GoalKindClassifier.GoalKind.EXECUTABLE);
        lenient().when(similarityService.isDuplicate(anyString())).thenReturn(false);

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .currentSubGoalId("parent-1")
                .build();

        agent.execute(ctx);

        // Persisted description is marker-free.
        ArgumentCaptor<String> descCaptor = ArgumentCaptor.forClass(String.class);
        verify(goalPlannerService).addSubGoal(
                anyString(), descCaptor.capture(), any(Boolean.class),
                eq("parent-1"), eq("analysis"), eq(GoalKindClassifier.GoalKind.EXECUTABLE));
        assertThat(descCaptor.getValue())
                .as("persisted description must not carry the [UNDEFINED] render marker")
                .isEqualTo(description)
                .doesNotContain("[UNDEFINED]");

        // Persisted criteria are marker-free, the original criterion is preserved verbatim, and
        // detection still drives clean "Create X.java" framing for the description-cited types.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> criteriaCaptor = ArgumentCaptor.forClass(List.class);
        verify(goalPlannerService).setAcceptanceCriteria(anyString(), criteriaCaptor.capture());
        List<String> criteria = criteriaCaptor.getValue();
        assertThat(criteria)
                .as("persisted criteria must never carry the [UNDEFINED] render marker")
                .noneMatch(c -> c.contains("[UNDEFINED]"));
        assertThat(criteria).contains(criterion);
        assertThat(criteria).contains("Create ProductRepository.java");
    }

    @Test
    void execute_definedSymbolIsNotAnnotated() throws IOException {
        // BookAlreadyLoanedException now exists on disk → grounding should leave the description alone.
        Path src = tempDir.resolve("output/src/main/java/com/example/library/exception");
        Files.createDirectories(src);
        Files.writeString(src.resolve("BookAlreadyLoanedException.java"),
                "package com.example.library.exception;\npublic class BookAlreadyLoanedException extends RuntimeException {}");

        String description = "Add missing import for BookAlreadyLoanedException";
        AnalysisOutcome rawOutcome = new AnalysisOutcome(
                new ArrayList<>(), new ArrayList<>(),
                List.of(description),
                Map.of(description, List.of("Import resolves")),
                "ok", "EXECUTABLE", false, false);

        when(adapter.analyze(any(CycleContextData.class))).thenReturn(rawOutcome);
        when(goalPlannerService.getGoalMetadataInt(eq("parent-1"), anyString())).thenReturn(0);
        when(goalPlannerService.countUnmetCriteria("parent-1")).thenReturn(2);
        when(goalPlannerService.getGlobalAnalysisInjectionBudget()).thenReturn(10);
        when(goalPlannerService.getAllGoals()).thenReturn(List.of());
        when(kindClassifier.classify(anyString())).thenReturn(GoalKindClassifier.GoalKind.EXECUTABLE);
        lenient().when(similarityService.isDuplicate(anyString())).thenReturn(false);

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .currentSubGoalId("parent-1")
                .build();

        CycleContextData result = agent.execute(ctx);

        ArgumentCaptor<String> descCaptor = ArgumentCaptor.forClass(String.class);
        verify(goalPlannerService).addSubGoal(
                anyString(), descCaptor.capture(), any(Boolean.class),
                eq("parent-1"), eq("analysis"), eq(GoalKindClassifier.GoalKind.EXECUTABLE));

        assertThat(descCaptor.getValue()).doesNotContain("[UNDEFINED]");
        assertThat(com.walpro.autobuilder.core.lifecycle.SignalReadout.of(result).raw())
                .as("no MISSING_SYMBOL signal when cited symbol exists on disk")
                .doesNotContainKey(BasicSignals.MISSING_SYMBOL);
    }
}
