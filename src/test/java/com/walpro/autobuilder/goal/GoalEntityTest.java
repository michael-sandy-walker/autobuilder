package com.walpro.autobuilder.goal;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import java.time.temporal.ChronoUnit;

class GoalEntityTest {

    @Test
    void markGivenUp_setsStatusAndRecordsTimestamp() {
        GoalEntity g = new GoalEntity("id", "desc", "planner", GoalType.SUBGOAL, "parent");

        Instant before = Instant.now();
        g.markGivenUp();
        Instant after = Instant.now();

        assertThat(g.getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
        assertThat(g.getGivenUp())
                .isNotNull()
                .isBetween(before, after);
    }

    @Test
    void markCompleted_setsStatusAndRecordsTimestamp() {
        GoalEntity g = new GoalEntity("id", "desc", "planner", GoalType.SUBGOAL, "parent");

        Instant before = Instant.now();
        g.markCompleted();
        Instant after = Instant.now();

        assertThat(g.getStatus()).isEqualTo(GoalStatus.COMPLETED);
        assertThat(g.getCompleted())
                .isNotNull()
                .isBetween(before, after);
    }

    @Test
    void markGivenUp_doesNotSetCompletedTimestamp() {
        GoalEntity g = new GoalEntity("id", "desc", "planner", GoalType.SUBGOAL, "parent");
        g.markGivenUp();
        assertThat(g.getCompleted()).isNull();
    }

    @Test
    void markCompleted_doesNotSetGivenUpTimestamp() {
        GoalEntity g = new GoalEntity("id", "desc", "planner", GoalType.SUBGOAL, "parent");
        g.markCompleted();
        assertThat(g.getGivenUp()).isNull();
    }
}
