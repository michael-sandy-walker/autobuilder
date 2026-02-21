//package com.walpro.autobuilder.goal;
//
//import static org.assertj.core.api.Assertions.assertThat;
//import static org.mockito.Mockito.doNothing;
//import static org.mockito.Mockito.times;
//import static org.mockito.Mockito.verify;
//import static org.mockito.Mockito.when;
//
//import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
//import com.walpro.autobuilder.core.service.FileService;
//import com.walpro.autobuilder.graph.goal.GoalGraphService;
//
//import java.io.IOException;
//import java.util.LinkedHashMap;
//import java.util.Map;
//
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.springframework.test.util.ReflectionTestUtils;
//
//@ExtendWith(MockitoExtension.class)
//public class GoalPlannerServiceClosureTest {
//
//    @Mock
//    private FileService fileService;
//
//    @Mock
//    private GoalGraphService goalGraphService;
//
//    @Mock
//    private GoalPlannerConfigProperties goalPlannerConfig;
//
//    @Test
//    void promoteCurrentSubGoal_closesFinishedAncestors_andAdvancesToNextLeaf() throws IOException {
//        doNothing().when(fileService).writeFile(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
//        doNothing().when(goalGraphService).setState(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
//        doNothing().when(goalGraphService).markCompleted(org.mockito.ArgumentMatchers.anyString());
//
//        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
//
//        // Build goal tree:
//        // root(G) -> parent(P) -> leaves(A,B)
//        // root(G) -> X (active)   (keeps root open so only P closes)
//        GoalEntity root = new GoalEntity("G", "root", "system", GoalType.GOAL, null);
//        root.setStatus(GoalStatus.ACTIVE);
//
//        GoalEntity parent = new GoalEntity("P", "parent", "planner", GoalType.SUBGOAL, "G");
//        parent.setStatus(GoalStatus.ACTIVE);
//
//        GoalEntity leafA = new GoalEntity("A", "leafA", "planner", GoalType.SUBGOAL, "P");
//        leafA.setStatus(GoalStatus.ACTIVE);
//
//        GoalEntity leafB = new GoalEntity("B", "leafB", "planner", GoalType.SUBGOAL, "P");
//        leafB.setStatus(GoalStatus.COMPLETED);
//
//        GoalEntity leafX = new GoalEntity("X", "leafX", "planner", GoalType.SUBGOAL, "G");
//        leafX.setStatus(GoalStatus.ACTIVE);
//
//        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
//        goalMap.put(root.getId(), root);
//        goalMap.put(parent.getId(), parent);
//        goalMap.put(leafA.getId(), leafA);
//        goalMap.put(leafB.getId(), leafB);
//        goalMap.put(leafX.getId(), leafX);
//
//        ReflectionTestUtils.setField(service, "goalMap", goalMap);
//        ReflectionTestUtils.setField(service, "rootGoalId", "G");
//        ReflectionTestUtils.setField(service, "currentSubGoalId", "A");
//
//        boolean adopted = service.promoteCurrentSubGoal();
//
//        assertThat(adopted).isTrue();
//
//        // Leaf A completed
//        assertThat(service.getGoalById("A").getStatus()).isEqualTo(GoalStatus.COMPLETED);
//
//        // Parent P should be closed (this is the new closure behavior)
//        assertThat(service.getGoalById("P").getStatus()).isEqualTo(GoalStatus.COMPLETED);
//
//        // Root remains open because X is still active
//        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.ACTIVE);
//
//        // Current subgoal should advance to X (next active leaf under root)
//        assertThat(service.getCurrentSubGoal().getId()).isEqualTo("X");
//
//        // Graph completion should be invoked for A and P
//        verify(goalGraphService, times(1)).markCompleted("A");
//        verify(goalGraphService, times(1)).markCompleted("P");
//    }
//}
