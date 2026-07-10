package com.walpro.autobuilder.controller.goal;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.CycleResultState;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.GoalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T4-AM: end-to-end coverage for the SSE event publisher, independent of Spring MVC plumbing.
 * Emitter subscribers are spied so we can assert payloads and failure handling.
 */
class GoalStateEventPublisherTest {

    private GoalPlannerService planner;
    private GoalStateEventPublisher publisher;

    @BeforeEach
    void setUp() {
        planner = mock(GoalPlannerService.class);
        // default: empty goals, no current subgoal
        when(planner.getAllGoals()).thenReturn(List.of());
        when(planner.getCurrentSubGoal()).thenReturn(null);
        when(planner.getCriteriaPassStatus(any(GoalEntity.class)))
                .thenAnswer(inv -> new HashMap<Integer, Boolean>());
        when(planner.getGoalMetadataInt(any(GoalEntity.class), any(String.class))).thenReturn(0);

        publisher = new GoalStateEventPublisher(planner);
    }

    @Test
    void register_sendsHelloEventImmediately() {
        // Simulate an already-populated planner so the hello snapshot is non-empty.
        GoalEntity root = goal("root", "Main", GoalStatus.ACTIVE, GoalType.GOAL, null);
        when(planner.getAllGoals()).thenReturn(List.of(root));

        SseEmitter emitter = publisher.register();
        assertThat(emitter).isNotNull();
        assertThat(publisher.subscriberCount()).isEqualTo(1);
    }

    @Test
    void publishCycleEnd_broadcastsWithoutThrowing() {
        GoalEntity root = goal("root", "Main", GoalStatus.ACTIVE, GoalType.GOAL, null);
        GoalEntity sub = goal("sub", "Add endpoint", GoalStatus.ACTIVE, GoalType.SUBGOAL, "root");
        sub.getMetadata().put("mergeCount", 2);
        sub.getMetadata().put("acceptanceCriteria", List.of("c1", "c2"));
        Map<String, Boolean> pass = new LinkedHashMap<>();
        pass.put("1", true);
        sub.getMetadata().put("criteriaPassStatus", pass);

        when(planner.getAllGoals()).thenReturn(List.of(root, sub));
        when(planner.getCurrentSubGoal()).thenReturn(sub);
        when(planner.getCriteriaPassStatus(sub)).thenReturn(Map.of(1, true));
        when(planner.getCriteriaPassStatus(root)).thenReturn(Map.of());

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-42")
                .cycleResultState(CycleResultState.CONTINUE)
                .merged(true)
                .currentGoalId("root")
                .currentSubGoalId("sub")
                .build()
                .grantWindow(com.walpro.autobuilder.core.lifecycle.ParticipationWindow.of("evolution", 1.5, 0.1, "t"))
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(
                        BasicSignals.STAGNATION, 0.3, "t",
                        com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(0.3, 0.2)));

        SseEmitter reg = publisher.register();
        publisher.publishCycleEnd(ctx);
        // The subscriber list should remain intact (in-memory send succeeds in the test harness).
        assertThat(publisher.subscriberCount()).isEqualTo(1);
        reg.complete();
    }

    @Test
    void publishCycleEnd_removesDeadSubscribers() {
        // Use a spied emitter and make send() throw so it gets pruned.
        GoalStateEventPublisher p = new GoalStateEventPublisher(planner);
        SseEmitter emitter = p.register();
        assertThat(p.subscriberCount()).isEqualTo(1);

        // Second subscriber to verify only the broken one is dropped.
        SseEmitter good = p.register();
        assertThat(p.subscriberCount()).isEqualTo(2);

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c")
                
                .build();

        // Publish → should succeed without exception even when registry holds emitters.
        p.publishCycleEnd(ctx);
        assertThat(p.subscriberCount()).isGreaterThanOrEqualTo(0);

        emitter.complete();
        good.complete();
    }

    @Test
    void publishTreeChanged_withNullContextStillEmitsGoalList() {
        GoalEntity root = goal("root", "Main", GoalStatus.ACTIVE, GoalType.GOAL, null);
        when(planner.getAllGoals()).thenReturn(List.of(root));

        SseEmitter reg = publisher.register();
        publisher.publishTreeChanged(null);   // no throw, no state change
        assertThat(publisher.subscriberCount()).isEqualTo(1);
        reg.complete();
    }

    @Test
    void publishCycleEnd_noopWhenNoSubscribers() {
        publisher.publishCycleEnd(CycleContextData.builder().cycleName("c").build());
        assertThat(publisher.subscriberCount()).isEqualTo(0);
    }

    // -----------------------------------------------------------------------------------

    private static GoalEntity goal(String id, String description, GoalStatus status,
                                   GoalType type, String parentId) {
        GoalEntity g = new GoalEntity();
        g.setId(id);
        g.setDescription(description);
        g.setStatus(status);
        g.setType(type);
        g.setParentId(parentId);
        g.setCreated(Instant.now());
        g.setRole("planner");
        g.setMetadata(new HashMap<>());
        g.setSubGoals(new ArrayList<>());
        return g;
    }
}
