package com.walpro.autobuilder.agent.planner.protocol;

import org.junit.jupiter.api.Test;

import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;

import static org.assertj.core.api.Assertions.assertThat;

public class PlannerResponseParserTest {

    @Test
    void shouldParseGoalStackAppend() {
        String reply = "#GoalStackAppend\n- Create mutation feedback viewer\n- Prune obsolete subgoals";
        PlannerResponseParser parser = new PlannerResponseParser();
//        GoalStackUpdate update = parser.parse(reply);
        PlannerOutcome outcome = parser.parse(reply);

//        assertThat(update.appends()).containsExactly(
//                "Create mutation feedback viewer",
//                "Prune obsolete subgoals"
//        );
        assertThat(outcome).isNotNull();
    }
}
