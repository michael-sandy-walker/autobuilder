package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T5-BO: every durable goal-tree change tells the live view, so nodes appear, change and disappear as
 * they happen -- not at the next cycle end. The case that exposed it: a root goal set by request on an
 * adopted project stayed invisible until the first cycle ended (2026-11-08).
 */
class GoalPlannerServiceTreeChangeEventTest {

    private GoalPlannerService service;
    private ApplicationEventPublisher events;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        FileService fileService = mock(FileService.class);
        GoalGraphService goalGraphService = mock(GoalGraphService.class);
        GoalPlannerConfigProperties config = mock(GoalPlannerConfigProperties.class);
        when(config.isSyncFromGraph()).thenReturn(false);
        when(config.isMigrateToGraph()).thenReturn(false);
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).setParent(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).save(any(GoalEntity.class));
        Path goalFile = tempDir.resolve("GoalStack.json");
        when(fileService.resolveSecurePath("meta/GoalStack.json")).thenReturn(goalFile);
        when(fileService.existsResolved(goalFile)).thenReturn(false);

        events = mock(ApplicationEventPublisher.class);
        service = new GoalPlannerService(fileService, goalGraphService, config);
        service.setApplicationEventPublisher(events);
        service.init();
    }

    @Test
    void aRootGoalSetOutsideACycle_isRelayedAtOnce_withNoCycle() {
        clearInvocations(events);

        service.addInitialGoal("goal-root", "Grow the parser.", "operator");

        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(events, atLeastOnce()).publishEvent(sent.capture());
        assertThat(sent.getAllValues()).hasAtLeastOneElementOfType(GoalTreePersistedEvent.class);
        assertThat(sent.getAllValues()).filteredOn(GoalTreePersistedEvent.class::isInstance)
                .allSatisfy(e -> assertThat(((GoalTreePersistedEvent) e).context()).isNull());
    }

    @Test
    void aNewSubgoal_isRelayed_withTheCycleItHappenedIn() {
        service.resetInitialGoal("root", "Main Goal");
        CycleContextData cycle = CycleContextData.builder().cycleName("cycle-t5bo").build();
        service.setCurrentCycleContext(cycle);
        clearInvocations(events);

        service.addSubGoal("sub-1", "Read the deleted-files section", true, "root");

        ArgumentCaptor<GoalTreePersistedEvent> sent = ArgumentCaptor.forClass(GoalTreePersistedEvent.class);
        verify(events, atLeastOnce()).publishEvent(sent.capture());
        assertThat(sent.getValue().context()).isSameAs(cycle);
    }

    @Test
    void aMetadataOnlyChange_isRelayed_soCriteriaCountsUpdateLive() {
        service.resetInitialGoal("root", "Main Goal");
        service.addSubGoal("sub-1", "Read the deleted-files section", true, "root");
        clearInvocations(events);

        service.saveCriteriaPassStatus("sub-1", java.util.Map.of(0, true));

        verify(events, atLeastOnce()).publishEvent(any(GoalTreePersistedEvent.class));
    }

    @Test
    void aViewThatCannotBeTold_neverFailsTheWrite() {
        doThrow(new IllegalStateException("context closing")).when(events).publishEvent(any(Object.class));

        assertThatCode(() -> service.resetInitialGoal("root", "Main Goal")).doesNotThrowAnyException();
        assertThat(service.getRootGoal()).isNotNull();
    }
}
