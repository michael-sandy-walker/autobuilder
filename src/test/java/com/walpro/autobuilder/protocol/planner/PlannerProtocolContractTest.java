package com.walpro.autobuilder.protocol.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;

class PlannerProtocolContractTest {

    private static CycleContextData.CycleContextDataBuilder base() {
        return CycleContextData.builder()
                .currentGoalDescription("Implement a REST API")
                .currentSubGoalDescription("Implement POST /books");
    }

    // -------------------------------------------------------------------------
    // T3-BG: --- CURRENT SUBGOAL STATUS --- block
    // -------------------------------------------------------------------------

    @Test
    void statusBlock_absent_whenNoCurrentSubGoal() {
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalDescription("Implement a REST API")
                .build();
        String prompt = PlannerProtocolContract.getUserPrompt(ctx);
        assertThat(prompt).doesNotContain("--- CURRENT SUBGOAL STATUS ---");
    }

    @Test
    void statusBlock_present_withStagnationCount() {
        CycleContextData ctx = base()
                .cumulativeStagnatingCycles(2)
                .build();
        String prompt = PlannerProtocolContract.getUserPrompt(ctx);
        assertThat(prompt).contains("--- CURRENT SUBGOAL STATUS ---");
        assertThat(prompt).contains("Stagnating cycles: 2");
    }

    @Test
    void statusBlock_scoreAndDelta_shownWhenLastScorePresent() {
        CycleContextData ctx = base()
                .score(0.75)
                .lastScore(0.68)
                .build();
        String prompt = PlannerProtocolContract.getUserPrompt(ctx);
        assertThat(prompt).contains("0.680 \u2192 0.750");
    }

    @Test
    void statusBlock_criteria_markedWithTick_crossOrQuestion() {
        CycleContextData ctx = base()
                .currentSubGoalCriteria(List.of("Returns HTTP 201", "Title stored in response", "HTTP 400 on missing title"))
                .criteriaAssessments(List.of("[1] \u2713 Returns HTTP 201", "[3] \u2717 HTTP 400 on missing title \u2014 validation not implemented"))
                .build();
        String prompt = PlannerProtocolContract.getUserPrompt(ctx);
        assertThat(prompt).contains("[1] \u2713 Returns HTTP 201");
        assertThat(prompt).contains("[2] ? Title stored in response");  // not assessed
        assertThat(prompt).contains("[3] \u2717 HTTP 400 on missing title");
    }

    @Test
    void statusBlock_persistentHeuristicWarnings_included() {
        CycleContextData ctx = base()
                .persistentHeuristicWarnings(List.of("NoMeaningfulChangeHeuristic fired 3 consecutive cycles"))
                .build();
        String prompt = PlannerProtocolContract.getUserPrompt(ctx);
        assertThat(prompt).contains("NoMeaningfulChangeHeuristic fired 3 consecutive cycles");
    }

    @Test
    void decomposeCoachingNote_absent_belowThreshold() {
        CycleContextData ctx = base()
                .cumulativeStagnatingCycles(2)
                .build();
        String prompt = PlannerProtocolContract.getUserPrompt(ctx);
        assertThat(prompt).doesNotContain("Consider decomposing");
    }

    @Test
    void decomposeCoachingNote_present_atStagnationThreshold() {
        CycleContextData ctx = base()
                .cumulativeStagnatingCycles(3)
                .build();
        String prompt = PlannerProtocolContract.getUserPrompt(ctx);
        assertThat(prompt).contains("3 stagnating cycles");
        assertThat(prompt).contains("Consider decomposing");
        assertThat(prompt).contains("#SubGoalRemove");
    }

    @Test
    void decomposeCoachingNote_present_whenReplanRequestedStrong() {
        // T2-BG: REPLAN_REQUESTED >= 0.50 triggers the coaching note even with stagnating=0,
        // because the subgoal is stuck regardless of formal STAGNATING cycle count.
        CycleContextData ctx = base()
                .cumulativeStagnatingCycles(0)
                .build();
        ctx = ctx.withSignal(Signal.of(BasicSignals.REPLAN_REQUESTED, 0.75, 0.10, "test"));
        String prompt = PlannerProtocolContract.getUserPrompt(ctx);
        assertThat(prompt).contains("REPLAN_REQUESTED signal");
        assertThat(prompt).contains("Consider decomposing");
        assertThat(prompt).contains("#SubGoalRemove");
    }

    @Test
    void decomposeCoachingNote_absent_whenReplanRequestedWeak() {
        // REPLAN_REQUESTED below threshold — coaching note must not appear.
        CycleContextData ctx = base()
                .cumulativeStagnatingCycles(0)
                .build();
        ctx = ctx.withSignal(Signal.of(BasicSignals.REPLAN_REQUESTED, 0.30, 0.10, "test"));
        String prompt = PlannerProtocolContract.getUserPrompt(ctx);
        assertThat(prompt).doesNotContain("Consider decomposing");
    }

    // -------------------------------------------------------------------------
    // T3-BG: --- PROJECT STRUCTURE --- block
    // -------------------------------------------------------------------------

    @Test
    void projectStructure_absent_whenSummaryNull() {
        CycleContextData ctx = base().build();
        String prompt = PlannerProtocolContract.getUserPrompt(ctx);
        assertThat(prompt).doesNotContain("--- PROJECT STRUCTURE ---");
    }

    @Test
    void projectStructure_present_withSummaryContent() {
        CycleContextData ctx = base()
                .projectSummary("src/main/java/com/example/BookController.java\nsrc/main/java/com/example/BookService.java")
                .build();
        String prompt = PlannerProtocolContract.getUserPrompt(ctx);
        assertThat(prompt).contains("--- PROJECT STRUCTURE ---");
        assertThat(prompt).contains("BookController.java");
    }

    @Test
    void projectStructure_truncated_atThirtyLines() {
        String longSummary = "line\n".repeat(35).stripTrailing();
        CycleContextData ctx = base()
                .projectSummary(longSummary)
                .build();
        String prompt = PlannerProtocolContract.getUserPrompt(ctx);
        assertThat(prompt).contains("--- PROJECT STRUCTURE ---");
        assertThat(prompt).contains("(truncated)");
        // 30 non-empty lines + truncation marker; must not include all 35
        long lineCount = prompt.lines()
                .dropWhile(l -> !l.contains("--- PROJECT STRUCTURE ---"))
                .skip(1)
                .takeWhile(l -> !l.contains("---") || l.contains("PROJECT"))
                .filter(l -> l.equals("line"))
                .count();
        assertThat(lineCount).isEqualTo(30);
    }
}
