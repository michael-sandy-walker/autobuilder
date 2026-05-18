package com.walpro.autobuilder.core.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.merge.MergeRollbackPoint;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MutationSnapshotServiceTest {

    @Mock FileService fileService;
    @Mock ObjectMapper objectMapper;
    @Mock BehaviourLogger behaviourLogger;

    MutationSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new MutationSnapshotService(fileService, objectMapper);
    }

    // --- persistMutationState ------------------------------------------------

    @Test
    void persistMutationState_noOp_whenMutationStateNull() throws Exception {
        CycleContextData ctx = CycleContextData.builder().cycleName("c").build();
        service.persistMutationState(ctx, null);
        verifyNoInteractions(fileService);
    }

    @Test
    void persistMutationState_noOp_whenNoEvolutionAndNoMutationPath() throws Exception {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c")
                .mutationState(MutationAcceptanceState.ACCEPTED)
                .evolutionRanThisCycle(false)
                .build();
        service.persistMutationState(ctx, null);
        verifyNoInteractions(fileService);
    }

    @Test
    void persistMutationState_updatesExistingMeta_withMutationState() throws Exception {
        // Normal path: meta.json exists → read it, update mutationState, write back.
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-16")
                .mutationState(MutationAcceptanceState.ACCEPTED)
                .evolutionRanThisCycle(true)
                .build();

        Path metaPath = Path.of("/abs/mutations/cycle-16/meta.json");
        when(fileService.resolveSecurePath("mutations/cycle-16/meta.json")).thenReturn(metaPath);
        when(fileService.existsResolved(metaPath)).thenReturn(true);

        String existingJson = "{}";
        when(fileService.readStringResolved(metaPath)).thenReturn(existingJson);
        CycleContextData existingMeta = CycleContextData.builder()
                .cycleName("cycle-16")
                .currentSubGoalId("goal-X")
                .build();
        when(objectMapper.readValue(existingJson, CycleContextData.class)).thenReturn(existingMeta);

        service.persistMutationState(ctx, null);

        ArgumentCaptor<Object> written = ArgumentCaptor.forClass(Object.class);
        verify(fileService).writeJson(eq("mutations/cycle-16/meta.json"), written.capture());
        CycleContextData saved = (CycleContextData) written.getValue();
        assertThat(saved.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(saved.currentSubGoalId()).isEqualTo("goal-X");
    }

    @Test
    void persistMutationState_fallbackCreate_whenMetaMissing_cycle2Scenario() throws Exception {
        // T1-ED regression (cycle-2): meta.json doesn't exist when persistMutationState fires
        // but evolution actually produced mutation files this cycle (applyRegularMutations may
        // have written the files and then failed at the meta write). Fallback must create
        // meta.json from the current context with mutationState=ACCEPTED and emit a behaviour
        // event rather than swallowing the IOException silently.
        com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome outcome =
                new com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome(
                        java.util.List.of(new com.walpro.autobuilder.agent.evolution.model.FileMutation(
                                "src/BookController.java", "class BookController {}")),
                        java.util.List.of(), "summary", "raw");
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-2")
                .currentSubGoalId("goal-ce1310f7")
                .mutationState(MutationAcceptanceState.ACCEPTED)
                .evolutionRanThisCycle(true)
                .evolutionOutcome(outcome)
                .build();

        Path metaPath = Path.of("/abs/mutations/cycle-2/meta.json");
        when(fileService.resolveSecurePath("mutations/cycle-2/meta.json")).thenReturn(metaPath);
        when(fileService.existsResolved(metaPath)).thenReturn(false);

        service.persistMutationState(ctx, behaviourLogger);

        // fallback write: writeJson must be called with the current context
        ArgumentCaptor<Object> written = ArgumentCaptor.forClass(Object.class);
        verify(fileService).writeJson(eq("mutations/cycle-2/meta.json"), written.capture());
        CycleContextData saved = (CycleContextData) written.getValue();
        assertThat(saved.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(saved.cycleName()).isEqualTo("cycle-2");

        // behaviour event must be emitted so the operator can see this in behaviour-*.ndjson
        verify(behaviourLogger).mutationMetaPersistFailed(eq("cycle-2"),
                eq("mutations/cycle-2/meta.json"), contains("not_found_fallback_created"));
    }

    @Test
    void persistMutationState_silentForCrossCycleCarryoverMissing() throws Exception {
        // T1-CX: when evolution did NOT run this cycle and the carried-over mutationPath points
        // to a meta.json that no longer exists, the failure should be silent (debug only).
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-5")
                .mutationPath("mutations/cycle-3")
                .mutationState(MutationAcceptanceState.ACCEPTED)
                .evolutionRanThisCycle(false)
                .build();

        Path metaPath = Path.of("/abs/mutations/cycle-3/meta.json");
        when(fileService.resolveSecurePath("mutations/cycle-3/meta.json")).thenReturn(metaPath);
        when(fileService.existsResolved(metaPath)).thenReturn(false);

        service.persistMutationState(ctx, behaviourLogger);

        // no write, no behaviour event
        verify(fileService, never()).writeJson(any(), any());
        verifyNoInteractions(behaviourLogger);
    }

    @Test
    void persistMutationState_skipsFallback_onNoOpCycle_noEvolutionContent() throws Exception {
        // T1-ED bug fix (2026-05-14 22:20 run): EvolutionAgent's #NoDiff path sets
        // evolutionRanThisCycle=true but does NOT call applyRegularMutations — so no mutation
        // files AND no meta.json land on disk. The original T1-ED fallback created a ghost
        // meta.json with mutationState=ACCEPTED, but FileVariantCollector then couldn't find
        // any variant files to merge → merge silently skipped with can_execute_false.
        // The fix: require actual mutation content before falling back.
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-noOp")
                .currentSubGoalId("goal-X")
                .mutationState(com.walpro.autobuilder.model.MutationAcceptanceState.ACCEPTED)
                .evolutionRanThisCycle(true)
                // evolutionOutcome=null OR empty → no mutation content this cycle
                .build();

        Path metaPath = Path.of("/abs/mutations/cycle-noOp/meta.json");
        when(fileService.resolveSecurePath("mutations/cycle-noOp/meta.json")).thenReturn(metaPath);
        when(fileService.existsResolved(metaPath)).thenReturn(false);

        service.persistMutationState(ctx, behaviourLogger);

        // No ghost meta.json created, no behaviour event.
        verify(fileService, never()).writeJson(any(), any());
        verifyNoInteractions(behaviourLogger);
    }

    // --- JSON round-trip regression -----------------------------------------

    @Test
    void cycleContextData_roundTrips_withLastRollbackPointPopulated() throws Exception {
        // T1-ED regression (2026-05-14 21:58 run): MergeRollbackPoint.isEmpty() was serialised
        // by Jackson as an "empty" JSON field because Jackson treats isXxx() as a JavaBean getter.
        // On deserialisation the 3-component record rejected it (UnrecognizedPropertyException),
        // which our persistMutationState fallback caught as IOException → wrote another corrupt
        // meta.json → infinite cascade. T1-EC sets ctx.lastRollbackPoint on every probe, so any
        // meta.json write after the probe runs hits this path.
        // Strict mode: any extraneous field produced by serialisation will trip deserialisation.
        // Validates that no isXxx() bean-getter (e.g. MergeRollbackPoint.isEmpty) leaks into JSON.
        ObjectMapper realMapper = new ObjectMapper().findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

        Map<String, String> originals = new LinkedHashMap<>();
        originals.put("src/Main.java", "class Main {}");
        Set<String> newlyAdded = new LinkedHashSet<>();
        newlyAdded.add("src/NewFile.java");
        MergeRollbackPoint rollbackPoint = new MergeRollbackPoint(
                "cycle-2-probe", originals, newlyAdded);

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-2")
                .outputPath("output")
                .currentSubGoalId("goal-ce1310f7")
                .mutationState(MutationAcceptanceState.ACCEPTED)
                .evolutionRanThisCycle(true)
                .lastRollbackPoint(rollbackPoint)
                .build();

        String json = realMapper.writeValueAsString(ctx);
        CycleContextData roundTripped = realMapper.readValue(json, CycleContextData.class);

        assertThat(roundTripped.cycleName()).isEqualTo("cycle-2");
        assertThat(roundTripped.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(roundTripped.lastRollbackPoint()).isNotNull();
        assertThat(roundTripped.lastRollbackPoint().cycleId()).isEqualTo("cycle-2-probe");
        assertThat(roundTripped.lastRollbackPoint().originalContents()).containsEntry("src/Main.java", "class Main {}");
        assertThat(roundTripped.lastRollbackPoint().newlyAddedPaths()).contains("src/NewFile.java");
    }
}
