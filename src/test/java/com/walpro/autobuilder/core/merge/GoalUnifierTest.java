package com.walpro.autobuilder.core.merge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-EG slice 4: a kept import's goals join the project's one tree. Structure first, the model only for what structure
 * leaves open; no second root; what the merged code already meets stays quiet.
 */
class GoalUnifierTest {

    @TempDir
    Path base;

    private final ObjectMapper mapper = new ObjectMapper();
    private final InputComparison comparison = mock(InputComparison.class);
    private GoalPlannerService goals;
    private String intent;

    @BeforeEach
    void setUp() throws Exception {
        goals = new GoalPlannerService(new FileService(base.toString(), mapper), mock(GoalGraphService.class),
                mock(GoalPlannerConfigProperties.class));
        goals.init();
        goals.addInitialGoal("root", "A library API.");
        goals.addSubGoal("books", "Implement POST /books and GET /books", false);
        goals.setAcceptanceCriteria("books", List.of("POST /books returns 201", "GET /books lists all books"));
        goals.addSubGoal("members", "Implement the Member domain model", false);
        intent = goals.landIntent("input-2", "Use the other project", List.of("the project uses its reader"));
    }

    private JsonNode tree(String json) throws Exception {
        return mapper.readTree(json);
    }

    private List<GoalEntity> broughtBy(String inputId) {
        return goals.getAllGoals().stream().filter(g -> g.getMetadata() != null && inputId.equals(g.getMetadata().get("inputId")))
                .filter(g -> !g.getId().equals(intent)).toList();
    }

    @Test
    void theImportsRootIsNotCopied_aGoalWithTheSameTextIsUnified_noNodeAdded() throws Exception {
        int before = goals.getAllGoals().size();
        new GoalUnifier(goals, comparison, "spec", "input-2").unify(tree("""
                {"id":"r","description":"A library API.","status":"COMPLETED","subGoals":[
                  {"id":"b","description":"Implement POST /books and  GET /books.","status":"COMPLETED","subGoals":[]}]}"""), intent);

        assertThat(goals.getAllGoals()).hasSize(before);
        assertThat(goals.getAllGoals()).filteredOn(g -> g.getDescription().startsWith("A library API")).hasSize(1);
        verify(comparison, never()).sameGoal(anyString(), anyString(), anyString());
    }

    @Test
    void criteriaThatLargelyAgreeUnify_andTheMetOnesItLacksAreBanked() throws Exception {
        new GoalUnifier(goals, comparison, "spec", "input-2").unify(tree("""
                {"id":"r","description":"x","status":"COMPLETED","subGoals":[
                  {"id":"b","description":"Books endpoints","status":"COMPLETED",
                   "metadata":{"acceptanceCriteria":["POST /books returns 201","GET /books lists all books","GET /books/{id} returns 404 when absent"],
                               "criteriaPassStatus":{"1":true,"2":true,"3":true}},"subGoals":[]}]}"""), intent);

        GoalEntity books = goals.getGoalById("books");
        assertThat(broughtBy("input-2")).isEmpty();
        @SuppressWarnings("unchecked")
        List<Object> criteria = (List<Object>) books.getMetadata().get("acceptanceCriteria");
        assertThat(criteria).contains("GET /books/{id} returns 404 when absent");
        assertThat(goals.getCriteriaPassStatus(books)).containsEntry(criteria.indexOf("GET /books/{id} returns 404 when absent") + 1, true);
    }

    @Test
    void structureLeavesItOpen_theModelDecides_onlyAgainstTheClosestGoals() throws Exception {
        when(comparison.sameGoal(anyString(), contains("Member domain model"), anyString())).thenReturn(true);

        new GoalUnifier(goals, comparison, "spec", "input-2").unify(tree("""
                {"id":"r","description":"x","status":"COMPLETED","subGoals":[
                  {"id":"m","description":"Create the Member domain class","status":"COMPLETED","subGoals":[]}]}"""), intent);

        assertThat(broughtBy("input-2")).isEmpty();
        verify(comparison).sameGoal(anyString(), contains("Member domain model"), contains("Member domain class"));
        verify(comparison, never()).sameGoal(anyString(), contains("POST /books"), anyString());
    }

    @Test
    void aGoalThatMatchesNothingIsAddedUnderItsParentsCounterpart_metWorkLandsCompleted_givenUpIsNotBrought() throws Exception {
        new GoalUnifier(goals, comparison, "spec", "input-2").unify(tree("""
                {"id":"r","description":"x","status":"COMPLETED","subGoals":[
                  {"id":"b","description":"Implement POST /books and GET /books","status":"COMPLETED","subGoals":[
                    {"id":"c","description":"Reject a title over 120 characters","status":"COMPLETED",
                     "metadata":{"acceptanceCriteria":["a 121-character title is refused with 400"],"criteriaPassStatus":{"1":true}},"subGoals":[]},
                    {"id":"d","description":"Paginate the book list","status":"ACTIVE","subGoals":[]}]},
                  {"id":"e","description":"Export the catalogue as XML","status":"GIVEN_UP","subGoals":[]}]}"""), intent);

        List<GoalEntity> added = broughtBy("input-2");
        assertThat(added).extracting(GoalEntity::getDescription)
                .containsExactlyInAnyOrder("Reject a title over 120 characters", "Paginate the book list");
        assertThat(added).allSatisfy(g -> assertThat(g.getParentId()).as("under the unified goal's counterpart").isEqualTo("books"));
        assertThat(added).filteredOn(g -> g.getDescription().startsWith("Reject")).singleElement()
                .satisfies(g -> assertThat(g.getStatus()).as("already met stays quiet").isEqualTo(GoalStatus.COMPLETED));
        assertThat(added).filteredOn(g -> g.getDescription().startsWith("Paginate")).singleElement()
                .satisfies(g -> assertThat(g.getStatus()).isEqualTo(GoalStatus.ACTIVE));
        assertThat(goals.getAllGoals()).noneMatch(g -> g.getDescription().startsWith("Export the catalogue"));
    }

    @Test
    void anUnmatchedTopGoalGoesUnderTheIntentGoal_withFreshIds() throws Exception {
        new GoalUnifier(goals, comparison, "spec", "input-2").unify(tree("""
                {"id":"r","description":"x","status":"COMPLETED","subGoals":[
                  {"id":"src-1","description":"Read CSV rows","status":"ACTIVE","subGoals":[]}]}"""), intent);

        assertThat(broughtBy("input-2")).singleElement().satisfies(g -> {
            assertThat(g.getParentId()).isEqualTo(intent);
            assertThat(g.getId()).as("a fresh id, never the source project's").isNotEqualTo("src-1");
            assertThat(g.getMetadata()).containsEntry("sourceGoalId", "src-1");
        });
    }

    @Test
    void theImportRootsMetCriteriaAreBankedOnTheIntentGoal() throws Exception {
        new GoalUnifier(goals, comparison, "spec", "input-2").unify(tree("""
                {"id":"r","description":"x","status":"COMPLETED",
                 "metadata":{"acceptanceCriteria":["the reader reads lines","the reader skips blanks"],"criteriaPassStatus":{"1":true,"2":false}},
                 "subGoals":[]}"""), intent);

        GoalEntity g = goals.getGoalById(intent);
        @SuppressWarnings("unchecked")
        List<Object> criteria = (List<Object>) g.getMetadata().get("acceptanceCriteria");
        assertThat(criteria).contains("the reader reads lines").doesNotContain("the reader skips blanks");
        assertThat(goals.getCriteriaPassStatus(g)).containsEntry(criteria.indexOf("the reader reads lines") + 1, true);
    }
}
