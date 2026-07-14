package com.walpro.autobuilder.goal;

import static org.assertj.core.api.Assertions.assertThat;

import com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery;
import com.walpro.autobuilder.goal.SpecTraceability.Binding;

import java.util.List;

import org.junit.jupiter.api.Test;

class SpecTraceabilityTest {

    private final SpecTraceability traceability = new SpecTraceability(new EndpointSurfaceQuery());

    private static final String NO_TEST_SPEC = """
            Build a library REST service.
            POST /books creates a book. GET /books lists books.
            POST /loans creates a loan.
            """;

    private static GoalEntity subgoal(String description, List<String> criteria) {
        GoalEntity g = new GoalEntity("s1", description, "planner", GoalType.SUBGOAL, "root");
        if (criteria != null) g.getMetadata().put("acceptanceCriteria", criteria);
        return g;
    }

    @Test
    void testFlavoredSubgoal_onSpecWithoutTests_isExtra() {
        // The T2-EJ field-run-1 fatal mis-bin: an unrequested unit-test subgoal stamped REQUIRED.
        GoalEntity g = subgoal("Add unit tests for the BookController endpoints", null);
        assertThat(traceability.classify(NO_TEST_SPEC, g)).isEqualTo(Binding.EXTRA);
    }

    @Test
    void testFlavoredSubgoal_whenSpecAsksForTests_isNotExtra() {
        String spec = NO_TEST_SPEC + "\nProvide unit tests for every endpoint.";
        GoalEntity g = subgoal("Add unit tests for the BookController endpoints", null);
        assertThat(traceability.classify(spec, g)).isNotEqualTo(Binding.EXTRA);
    }

    @Test
    void subgoalNamingDeclaredEndpoint_isRequired() {
        GoalEntity g = subgoal("Implement POST /loans returning 201", null);
        assertThat(traceability.classify(NO_TEST_SPEC, g)).isEqualTo(Binding.REQUIRED);
    }

    @Test
    void barePathMentionOfDeclaredEndpoint_isRequired() {
        GoalEntity g = subgoal("Create the controller serving /loans", null);
        assertThat(traceability.classify(NO_TEST_SPEC, g)).isEqualTo(Binding.REQUIRED);
    }

    @Test
    void endpointObservationInCriteria_countsToo() {
        GoalEntity g = subgoal("Loan creation work item",
                List.of("POST /loans returns 201 with the created loan"));
        assertThat(traceability.classify(NO_TEST_SPEC, g)).isEqualTo(Binding.REQUIRED);
    }

    @Test
    void undeclaredEndpointMention_isUnknown() {
        GoalEntity g = subgoal("Implement DELETE /reviews", null);
        assertThat(traceability.classify(NO_TEST_SPEC, g)).isEqualTo(Binding.UNKNOWN);
    }

    @Test
    void noObservationEitherWay_isUnknown() {
        GoalEntity g = subgoal("Refactor the persistence layer", null);
        assertThat(traceability.classify(NO_TEST_SPEC, g)).isEqualTo(Binding.UNKNOWN);
    }

    @Test
    void blankInputs_areUnknown() {
        assertThat(traceability.classify(null, subgoal("x", null))).isEqualTo(Binding.UNKNOWN);
        assertThat(traceability.classify("  ", subgoal("x", null))).isEqualTo(Binding.UNKNOWN);
        assertThat(traceability.classify(NO_TEST_SPEC, null)).isEqualTo(Binding.UNKNOWN);
    }
}
