package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.config.GoalPlannerConfig;
import com.walpro.autobuilder.goal.graph.GoalGraphService;
import com.walpro.autobuilder.service.FileService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoalPlannerServiceTest {

    private GoalPlannerService service;
    private FileService fileService;
    private GoalGraphService goalGraphService;
    private GoalPlannerConfig config;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        fileService = mock(FileService.class);
        goalGraphService = mock(GoalGraphService.class);
        config = mock(GoalPlannerConfig.class);

        when(config.isSyncFromGraph()).thenReturn(false);
        when(config.isMigrateToGraph()).thenReturn(false);

        Path goalFile = tempDir.resolve("GoalStack.json");
        when(fileService.resolveSecurePath("meta/GoalStack.json")).thenReturn(goalFile);
        when(fileService.exists(goalFile)).thenReturn(false);

        service = new GoalPlannerService(fileService, goalGraphService, config);
        service.init(); // manually trigger @PostConstruct
    }

    @Test
    void testRecursiveGoalTreeStorageAndResolution() {
        // Build goal tree
        service.resetInitialGoal("root", "Main Project Goal");
        service.addSubGoal("goal1", "SubGoal A");
        service.addSubGoal("goal2", "SubGoal B");
        service.addSubGoal("goal3", "SubSubGoal B.1");

        // Check currentSubGoal is deepest leaf (goal3)
        GoalEntity current = service.getCurrentSubGoal();
        assertNotNull(current);
        assertEquals("goal3", current.getId());

        // Check structure: root > goal1 > goal2 > goal3
        GoalEntity root = service.getRootGoal();
        assertEquals("root", root.getId());
        assertEquals(1, root.getSubGoals().size());
        GoalEntity g1 = root.getSubGoals().get(0);
        assertEquals("goal1", g1.getId());

        GoalEntity g2 = g1.getSubGoals().get(0);
        assertEquals("goal2", g2.getId());

        GoalEntity g3 = g2.getSubGoals().get(0);
        assertEquals("goal3", g3.getId());

        // Log tree
        System.out.println("\nGoal Tree:");
        service.logGoalTree();
    }

    @Test
    void testPromoteGoalCompletesLeaves() {
        service.resetInitialGoal("root", "Main Goal");
        service.addSubGoal("a", "A");
//        service.setCurrentSubGoal("a");
        service.addSubGoal("b", "B under A");
//        service.setCurrentSubGoal("b");
        service.addSubGoal("c", "C under B");
//        service.setCurrentSubGoal("c");

        assertEquals("c", service.getCurrentSubGoal().getId());

        service.promoteCurrentSubGoal(); // completes "c"
        assertEquals("b", service.getCurrentSubGoal().getId());

        service.promoteCurrentSubGoal(); // completes "b"
        assertEquals("a", service.getCurrentSubGoal().getId());

        service.promoteCurrentSubGoal(); // completes "a"
        assertNull(service.getCurrentSubGoal());

        assertEquals(GoalStatus.COMPLETED, service.getRootGoal().getStatus());
    }
}
