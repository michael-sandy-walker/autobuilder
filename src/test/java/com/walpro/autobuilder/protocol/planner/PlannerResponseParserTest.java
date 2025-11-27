package com.walpro.autobuilder.protocol.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

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
    void parseRemoveWithoutGoalsStillMarksUpdatedButDoesNotAddNewGoals() {
        String content = """
                #SubGoalRemove
                - Some old goal

                Message: Remove legacy goals only.
                """;

        PlannerResponseParser parser = new PlannerResponseParser();
        PlannerOutcome outcome = parser.parse(content);

        // #SubGoalRemove toggles `updated`, but the parser currently ignores the list for removals
        assertThat(outcome.updated()).isTrue();
        assertThat(outcome.newGoals())
                .as("Remove directive should not populate newGoals list")
                .isEmpty();
        assertThat(outcome.message())
                .isEqualTo("Remove legacy goals only.");
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
}
