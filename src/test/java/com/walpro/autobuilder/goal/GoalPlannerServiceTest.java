package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoalPlannerServiceTest {

    private GoalPlannerService service;
    private GoalGraphService goalGraphService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        FileService fileService = mock(FileService.class);
        goalGraphService = mock(GoalGraphService.class);
        GoalPlannerConfigProperties config = mock(GoalPlannerConfigProperties.class);

        when(config.isSyncFromGraph()).thenReturn(false);
        when(config.isMigrateToGraph()).thenReturn(false);

        Path goalFile = tempDir.resolve("GoalStack.json");
        when(fileService.resolveSecurePath("meta/GoalStack.json")).thenReturn(goalFile);
        when(fileService.exists(goalFile.toString())).thenReturn(false);

        service = new GoalPlannerService(fileService, goalGraphService, config);
        service.init(); // Manually initialize
    }

    @Test
    void testDeepGoalTreeAndSubgoalPromotion() {
        service.resetInitialGoal("root", "Main Goal");

        service.addSubGoal("a", "Step A", true, "root");
        service.addSubGoal("b", "Step B", true, "a");
        service.addSubGoal("c", "Step C", true, "b");

        assertEquals("c", service.getCurrentSubGoal().getId());

        // Promote repeatedly
        service.promoteCurrentSubGoal(); // c -> completed
        assertEquals("b", service.getCurrentSubGoal().getId());

        service.promoteCurrentSubGoal(); // b -> completed
        assertEquals("a", service.getCurrentSubGoal().getId());

        service.promoteCurrentSubGoal(); // a -> completed
        assertNull(service.getCurrentSubGoal());

        assertEquals(GoalStatus.COMPLETED, service.getRootGoal().getStatus());
    }

    @Test
    void testPlannerIsInvokedWhenBranchExhausted() {
        // Create a mock callback
        PlanningCallback mockCallback = mock(PlanningCallback.class);
        service.setPlanningCallback(mockCallback); // Inject the mock

        service.resetInitialGoal("root", "Milestone");

        service.addSubGoal("g1", "Single Goal", true, "root");
        assertEquals("g1", service.getCurrentSubGoal().getId());

        service.promoteCurrentSubGoal(); // g1 -> completed, no more subgoals

        // Trigger planner logic (will hit mock)
        service.promoteCurrentSubGoal();

        // Verify the callback was invoked
        verify(mockCallback, atLeastOnce()).planInto("root");
    }

    @Test
    void testStructureWithMultipleBranches() {
        service.resetInitialGoal("root", "Root Goal");

        service.addSubGoal("a", "Branch A", false, "root");
        service.addSubGoal("b", "Branch B", true, "root"); // Set as current
        service.addSubGoal("b1", "Sub B.1", true, "b");
        service.addSubGoal("b2", "Sub B.2", true, "b1");

        // Should promote back up through B.2 → B.1 → B → then continue to A
        assertEquals("b2", service.getCurrentSubGoal().getId());
        service.promoteCurrentSubGoal();
        assertEquals("b1", service.getCurrentSubGoal().getId());
        service.promoteCurrentSubGoal();
        assertEquals("b", service.getCurrentSubGoal().getId());
        service.promoteCurrentSubGoal();
        assertEquals("a", service.getCurrentSubGoal().getId());
    }

    @Test
    void testMigrateToGraphWritesGoals() {
        service.resetInitialGoal("root", "Main Goal"); // 1 save
        service.addSubGoal("a", "A", true); // 1 save
        service.addSubGoal("b", "B under A", true); // 1 save
        service.addSubGoal("c", "C under B", true); // 1 save

        service.migrateToGraph(); // 4 saves

        verify(goalGraphService, times(8)).save(any(GoalEntity.class));
    }

    @Test
    void testPruneShouldRemoveCompletedOrGivenUpBranches() {
        service.resetInitialGoal("root", "Goal");

        service.addSubGoal("g1", "G1", true, "root");
        service.addSubGoal("g2", "G2", true, "g1");
        service.addSubGoal("g3", "G3", true, "g2");

        // Mark all as completed
        service.markGoalCompleted("g3");
        service.markGoalCompleted("g2");
        service.markGoalCompleted("g1");

        // Trigger pruning
        service.pruneCompletedBranches();

        GoalEntity root = service.getRootGoal();
        List<GoalEntity> remaining = root.getSubGoals();
        assertTrue(remaining.isEmpty(), "Expected all pruned goals to be removed from tree.");
        assertNull(service.getGoalById("g1"));
        assertNull(service.getGoalById("g2"));
        assertNull(service.getGoalById("g3"));
    }

}
