package com.walpro.autobuilder.protocol.planner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;

class PlannerResponseParserTest {

    @Test
    void parseDetectsAppendedSubGoalsAndMessage() {
        String content = """
                #SubGoalAppend
                - Implement persistence layer
                - Add evaluation tests

                Message: Focus on evaluation and evolution first.
                """;

        PlannerResponseParser parser = new PlannerResponseParser();
        PlannerOutcome outcome = parser.parse(content);

        assertThat(outcome.updated()).isTrue();
        assertThat(outcome.newGoals())
                .containsExactly(
                        "Implement persistence layer",
                        "Add evaluation tests"
                );
        assertThat(outcome.message())
                .isEqualTo("Focus on evaluation and evolution first.");
    }

    @Test
    void parseRemoveParsesGoalsToRemoveList() {
        String content = """
                #SubGoalRemove
                - Some old goal
                - Another stale goal

                Message: Remove legacy goals only.
                """;

        PlannerResponseParser parser = new PlannerResponseParser();
        PlannerOutcome outcome = parser.parse(content);

        assertThat(outcome.updated()).isTrue();
        assertThat(outcome.newGoals()).isEmpty();
        assertThat(outcome.goalsToRemove())
                .containsExactly("Some old goal", "Another stale goal");
        assertThat(outcome.message()).isEqualTo("Remove legacy goals only.");
    }

    @Test
    void parseRemoveAndAppendInSameResponse() {
        String content = """
                #SubGoalRemove
                - Old goal A

                #SubGoalAppend
                - New goal B

                Message: Replaced old with new.
                """;

        PlannerResponseParser parser = new PlannerResponseParser();
        PlannerOutcome outcome = parser.parse(content);

        assertThat(outcome.updated()).isTrue();
        assertThat(outcome.goalsToRemove()).containsExactly("Old goal A");
        assertThat(outcome.newGoals()).containsExactly("New goal B");
        assertThat(outcome.message()).isEqualTo("Replaced old with new.");
    }

    @Test
    void parseWithoutDirectivesYieldsNoUpdateAndOptionalMessage() {
        String content = """
                Message: Nothing to change here.
                """;

        PlannerResponseParser parser = new PlannerResponseParser();
        PlannerOutcome outcome = parser.parse(content);

        assertThat(outcome.updated()).isFalse();
        assertThat(outcome.newGoals()).isEmpty();
        assertThat(outcome.message())
                .isEqualTo("Nothing to change here.");
    }

    @Test
    void parseMultipleMessagesUsesLastOne() {
        String content = """
                #SubGoalAppend
                - First goal

                Message: First message.
                Message: Second (final) message.
                """;

        PlannerResponseParser parser = new PlannerResponseParser();
        PlannerOutcome outcome = parser.parse(content);

        assertThat(outcome.updated()).isTrue();
        assertThat(outcome.newGoals()).containsExactly("First goal");
        // Parser overwrites messageBuilder each time it sees "Message:"
        assertThat(outcome.message())
                .isEqualTo("First message.\nSecond (final) message.");
    }

    @Test
    void parseAppend_acceptsStarBulletsAndTrimsWhitespace() {
        String content = """
            #SubGoalAppend
            - First goal with spaces     
            """;

        PlannerResponseParser parser = new PlannerResponseParser();
        PlannerOutcome outcome = parser.parse(content);

        assertThat(outcome.updated()).isTrue();
        assertThat(outcome.newGoals()).containsExactly(
                "First goal with spaces"
        );
    }

    @Test
    void parseRemove_stripsGoalIdPrefix_whenLLMEchoesFormattedGoalList() {
        // PlannerProtocolContract renders goals as "- goal-<uuid>: <description>".
        // The LLM naturally copies this format into #SubGoalRemove entries.
        // Parser should extract just the ID so processRemovals can match reliably.
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        String content = """
                #SubGoalRemove
                - goal-%s: Implement persistence layer
                - Plain description only

                Message: Pruned.
                """.formatted(uuid);

        PlannerOutcome outcome = new PlannerResponseParser().parse(content);

        assertThat(outcome.updated()).isTrue();
        assertThat(outcome.goalsToRemove()).containsExactly(
                "goal-" + uuid,
                "Plain description only"
        );
    }

    @Test
    void parseCriteriaBlock_associatesWithPrecedingGoal() {
        String content = """
                #SubGoalAppend
                - Implement POST /tasks with validation
                Criteria:
                * Returns HTTP 201 with task body
                * Returns HTTP 400 with {"message":"..."} on invalid input
                - Implement GET /tasks
                Message: Added subgoals.
                """;

        PlannerOutcome outcome = new PlannerResponseParser().parse(content);

        assertThat(outcome.updated()).isTrue();
        assertThat(outcome.newGoals()).containsExactly(
                "Implement POST /tasks with validation",
                "Implement GET /tasks"
        );
        assertThat(outcome.goalCriteria()).isNotNull();
        assertThat(outcome.goalCriteria().get("Implement POST /tasks with validation"))
                .containsExactly(
                        "Returns HTTP 201 with task body",
                        "Returns HTTP 400 with {\"message\":\"...\"} on invalid input"
                );
        assertThat(outcome.goalCriteria()).doesNotContainKey("Implement GET /tasks");
    }

    @Test
    void parseCriteriaBlock_noCriteria_yieldNullMap() {
        String content = """
                #SubGoalAppend
                - Implement GET /tasks
                Message: No criteria.
                """;

        PlannerOutcome outcome = new PlannerResponseParser().parse(content);

        assertThat(outcome.goalCriteria()).isNull();
    }

    @Test
    void parseCriteriaBlock_multipleCriteriaBlocks() {
        String content = """
                #SubGoalAppend
                - Implement POST /tasks
                Criteria:
                * Returns HTTP 201
                - Implement DELETE /tasks/{id}
                Criteria:
                * Returns HTTP 204 on success
                * Returns HTTP 404 for unknown id
                """;

        PlannerOutcome outcome = new PlannerResponseParser().parse(content);

        assertThat(outcome.goalCriteria()).hasSize(2);
        assertThat(outcome.goalCriteria().get("Implement POST /tasks"))
                .containsExactly("Returns HTTP 201");
        assertThat(outcome.goalCriteria().get("Implement DELETE /tasks/{id}"))
                .containsExactly("Returns HTTP 204 on success", "Returns HTTP 404 for unknown id");
    }

    @Test
    void parseDirective_isCaseSensitive() {
        String content = """
            #subgoalappend
            - Do the thing

            Message: ok
            """;

        PlannerResponseParser parser = new PlannerResponseParser();
        PlannerOutcome outcome = parser.parse(content);

        assertThat(outcome.updated()).isFalse();
        assertThat(outcome.newGoals()).isEmpty();
    }
}
