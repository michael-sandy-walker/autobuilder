package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T5-I: the goal_status_changed shadow-diff at the persist() chokepoint and the
 * subgoal_promoted emit on the standard promotion path. Creation and startup load must
 * seed the shadow silently — only real transitions reach the stream.
 */
class GoalPlannerServiceStatusEventTest {

    private GoalPlannerService service;
    private BehaviourLogger behaviourLogger;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        FileService fileService = mock(FileService.class);
        GoalGraphService goalGraphService = mock(GoalGraphService.class);
        GoalPlannerConfigProperties config = mock(GoalPlannerConfigProperties.class);

        when(config.isSyncFromGraph()).thenReturn(false);
        when(config.isMigrateToGraph()).thenReturn(false);

        doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).setParent(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).markCompleted(anyString());
        lenient().doNothing().when(goalGraphService).save(any(GoalEntity.class));

        Path goalFile = tempDir.resolve("GoalStack.json");
        when(fileService.resolveSecurePath("meta/GoalStack.json")).thenReturn(goalFile);
        when(fileService.existsResolved(goalFile)).thenReturn(false);

        behaviourLogger = mock(BehaviourLogger.class);
        service = new GoalPlannerService(fileService, goalGraphService, config);
        service.setBehaviourLogger(behaviourLogger);
        service.setCurrentCycleContext(CycleContextData.builder().cycleName("cycle-t5i").build());
        service.init();
    }

    @Test
    void goalCreation_seedsShadowSilently_noStatusEvent() {
        service.resetInitialGoal("root", "Main Goal");
        service.addSubGoal("sub-1", "Implement the Book resource", true, "root");

        verify(behaviourLogger, never()).goalStatusChanged(
                anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void transitionToCompleted_emitsActiveToCompleted_once() {
        service.resetInitialGoal("root", "Main Goal");
        service.addSubGoal("sub-1", "Implement the Book resource", true, "root");
        service.setAcceptanceCriteria("sub-1", List.of("endpoint answers 200"));

        service.transitionGoal("sub-1", GoalStatus.COMPLETED, "verified against code");

        verify(behaviourLogger, times(1)).goalStatusChanged(
                eq("cycle-t5i"), eq("sub-1"), eq("ACTIVE"), eq("COMPLETED"), any());
        // No phantom second transition on subsequent persists.
        service.addSubGoal("sub-2", "Implement the Member resource", false, "root");
        verify(behaviourLogger, times(1)).goalStatusChanged(
                anyString(), eq("sub-1"), anyString(), anyString(), any());
    }

    @Test
    void giveUpWithSubsumptionPromotion_emitsBothDurableTransitions() {
        service.resetInitialGoal("root", "Main Goal");
        service.addSubGoal("sub-1", "Implement the Book resource", true, "root");
        // All criteria banked → T2-DR promotes the give-up to SUBSUMED. The give-up itself
        // persists BEFORE the promotion does, so BOTH transitions are durable and both reach
        // the stream — the trace shows the T2-DR promotion instead of hiding it.
        service.setAcceptanceCriteria("sub-1", List.of("done"));
        service.saveCriteriaPassStatus("sub-1", java.util.Map.of(1, true));

        service.transitionGoal("sub-1", GoalStatus.GIVEN_UP, "retry budget exhausted");

        verify(behaviourLogger, times(1)).goalStatusChanged(
                eq("cycle-t5i"), eq("sub-1"), eq("ACTIVE"), eq("GIVEN_UP"), any());
        verify(behaviourLogger, times(1)).goalStatusChanged(
                eq("cycle-t5i"), eq("sub-1"), eq("GIVEN_UP"), eq("SUBSUMED"), any());
    }

    @Test
    void promoteCurrentSubGoal_emitsSubgoalPromoted_withFromAndTo() {
        service.resetInitialGoal("root", "Main Goal");
        service.addSubGoal("sub-1", "Implement the Book resource", true, "root");
        service.addSubGoal("sub-2", "Implement the Member resource", false, "root");
        service.setAcceptanceCriteria("sub-1", List.of("endpoint answers 200"));

        service.promoteCurrentSubGoal();

        verify(behaviourLogger, times(1)).subgoalPromoted(eq("cycle-t5i"), eq("sub-1"), eq("sub-2"));
    }

    @Test
    void promoteLastLeaf_emitsSubgoalPromoted_withNullTo() {
        service.resetInitialGoal("root", "Main Goal");
        service.addSubGoal("sub-1", "Implement the Book resource", true, "root");
        // Bank the criterion — an unbanked criterion gets the completed leaf reopened at the
        // closure edge (T1-FG) and re-picked as current, which is its own trace, not this one.
        service.setAcceptanceCriteria("sub-1", List.of("endpoint answers 200"));
        service.saveCriteriaPassStatus("sub-1", java.util.Map.of(1, true));

        service.promoteCurrentSubGoal();

        verify(behaviourLogger, times(1)).subgoalPromoted(eq("cycle-t5i"), eq("sub-1"), isNull());
    }

    @Test
    void closureEdgeReopen_isVisibleInTheStatusStream() {
        // The T1-FG shape: a leaf completed with UNBANKED criteria is reopened at the closure
        // edge and re-picked as current. Before T5-I this was reconstructible only from log
        // prose; now both flips and the self-promotion are events.
        service.resetInitialGoal("root", "Main Goal");
        service.addSubGoal("sub-1", "Implement the Book resource", true, "root");
        service.setAcceptanceCriteria("sub-1", List.of("endpoint answers 200"));

        service.promoteCurrentSubGoal();

        verify(behaviourLogger, times(1)).goalStatusChanged(
                eq("cycle-t5i"), eq("sub-1"), eq("ACTIVE"), eq("COMPLETED"), any());
        verify(behaviourLogger, times(1)).goalStatusChanged(
                eq("cycle-t5i"), eq("sub-1"), eq("COMPLETED"), eq("ACTIVE"), any());
        verify(behaviourLogger, times(1)).subgoalPromoted(eq("cycle-t5i"), eq("sub-1"), eq("sub-1"));
    }
}
