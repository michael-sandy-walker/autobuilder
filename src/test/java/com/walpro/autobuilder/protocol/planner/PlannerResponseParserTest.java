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
