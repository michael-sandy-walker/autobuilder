package com.walpro.autobuilder.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.List;

import com.walpro.autobuilder.graph.goal.GoalGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GoalSimilarityServiceTest {

    private GoalGraphService goalGraphService;
    private GoalSimilarityService service;

    @BeforeEach
    void setUp() {
        goalGraphService = mock(GoalGraphService.class);
        service = new GoalSimilarityService(goalGraphService);
    }

    @Test
    void isDuplicate_returnsFalse_forNullOrBlank() {
        assertThat(service.isDuplicate(null)).isFalse();
        assertThat(service.isDuplicate("   ")).isFalse();

        verifyNoInteractions(goalGraphService);
    }

    @Test
    void isDuplicate_callsFindAllDescriptions_once() {
        when(goalGraphService.findAllDescriptions(anyInt())).thenReturn(List.of());

        service.isDuplicate("Implement persistence layer now");

        verify(goalGraphService, times(1)).findAllDescriptions(anyInt());
    }

    @Test
    void isDuplicate_returnsTrue_whenAnyCandidateExceedsThreshold() {
        String candidate = "Add unit tests for planner throttling";
        when(goalGraphService.findAllDescriptions(anyInt()))
                .thenReturn(List.of("Add unit tests for planner throttling"));

        assertThat(service.isDuplicate(candidate)).isTrue();
    }

    @Test
    void isDuplicate_ignoresNullCandidatesFromGraph() {
        String candidate = "Improve merge determinism";
        when(goalGraphService.findAllDescriptions(anyInt()))
                .thenReturn(Arrays.asList(null, "something else"));

        assertThat(service.isDuplicate(candidate)).isFalse();
    }

    @Test
    void isDuplicate_matchesGivenUpGoals() {
        // GIVEN_UP goals must be included so re-proposals of failed goals are blocked
        String candidate = "Refactor HelloController to add REST endpoint";
        when(goalGraphService.findAllDescriptions(anyInt()))
                .thenReturn(List.of("Refactor HelloController to add REST endpoint"));

        assertThat(service.isDuplicate(candidate)).isTrue();
    }

    @Test
    void similarity_returnsZero_whenEitherBlank() {
        assertThat(service.similarity(null, "x")).isZero();
        assertThat(service.similarity("x", null)).isZero();
        assertThat(service.similarity("   ", "x")).isZero();
        assertThat(service.similarity("x", "   ")).isZero();
    }

    @Test
    void similarity_isOne_forIdenticalAfterNormalization() {
        float s = service.similarity(" A   B  ", "A B");
        assertThat(s).isGreaterThan(0.99f);
    }

    // ---------------------------------------------------------------------------------
    // T2-DO: findEquivalent — match across ALL statuses
    // ---------------------------------------------------------------------------------

    @Test
    void findEquivalent_returnsGivenUpMatch_evenWhenIsDuplicateExcludesIt() {
        // Build a GIVEN_UP candidate.
        GoalEntity givenUp = new GoalEntity();
        givenUp.setId("g-1");
        givenUp.setDescription("POST /members endpoint missing validation for duplicate email (409 response)");
        givenUp.setStatus(GoalStatus.GIVEN_UP);

        String candidate = "POST /members endpoint missing validation for duplicate email (409 response)";
        GoalEntity match = service.findEquivalent(candidate, List.of(givenUp));
        assertThat(match).isNotNull();
        assertThat(match.getId()).isEqualTo("g-1");
        assertThat(match.getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
    }

    @Test
    void findEquivalent_returnsHighestSimilarityMatch_acrossStatuses() {
        GoalEntity active = new GoalEntity();
        active.setId("g-active");
        active.setDescription("Add GET /books endpoint that returns all books");
        active.setStatus(GoalStatus.ACTIVE);
        GoalEntity givenUp = new GoalEntity();
        givenUp.setId("g-given-up");
        givenUp.setDescription("Add GET /books endpoint returning all books");
        givenUp.setStatus(GoalStatus.GIVEN_UP);
        GoalEntity completed = new GoalEntity();
        completed.setId("g-complete");
        completed.setDescription("Implement POST /loans endpoint");
        completed.setStatus(GoalStatus.COMPLETED);

        String candidate = "Add GET /books endpoint that returns all books";
        GoalEntity match = service.findEquivalent(candidate, List.of(active, givenUp, completed));
        assertThat(match).isNotNull();
        // ACTIVE description is the exact-text match — must win the tie-break.
        assertThat(match.getId()).isEqualTo("g-active");
    }

    @Test
    void findEquivalent_returnsNull_belowThreshold() {
        GoalEntity g = new GoalEntity();
        g.setId("g-1");
        g.setDescription("Something completely unrelated to the candidate");
        g.setStatus(GoalStatus.GIVEN_UP);

        assertThat(service.findEquivalent("Implement DELETE /users/{id}", List.of(g))).isNull();
    }

    @Test
    void findEquivalent_returnsNull_forBlankOrNullInputs() {
        assertThat(service.findEquivalent(null, List.of())).isNull();
        assertThat(service.findEquivalent("   ", List.of())).isNull();
        assertThat(service.findEquivalent("foo", null)).isNull();
        assertThat(service.findEquivalent("foo", List.of())).isNull();
    }

    /** T4-CQ: invented coordinates; the shape is structdiff run 3's two lost work items. */
    @Test
    void itemsNamingDifferentClassesAreNotDuplicates_howeverMuchWordingTheyShare() {
        String done = "Create a test class `WidgetParserTest` under `src/test/java` (in the package matching the "
                + "main code, e.g. `shop.widget`) that calls `WidgetParser.parse` with inline source and asserts "
                + "the exact returned values.";
        String candidate = "Create `src/test/java/shop/widget/util/GadgetFormatterTest.java` in package "
                + "`shop.widget.util` that calls `GadgetFormatter.format` and `formatPlainText` with a "
                + "constructed tree and asserts the exact returned String content.";

        assertThat(service.similarity(candidate, done)).isGreaterThan(0.88f);   // the metric alone says "same"
        assertThat(service.isDuplicate(candidate, List.of(done))).isFalse();
    }

    @Test
    void aParaphraseThatOnlyAddsADetailIsStillTheSameItem() {
        String done = "Write tests for WidgetParser covering the parse method with inline source.";
        String candidate = "Write tests for WidgetParser covering the parse method with inline source in WidgetParserTest.";

        assertThat(service.isDuplicate(candidate, List.of(done))).isTrue();
    }

    @Test
    void textNamingNoCodeIsJudgedByTheMetricAlone() {
        assertThat(GoalSimilarityService.nameDifferentCode("Implement the persistence layer",
                "Implement the storage layer")).isFalse();
    }
}
