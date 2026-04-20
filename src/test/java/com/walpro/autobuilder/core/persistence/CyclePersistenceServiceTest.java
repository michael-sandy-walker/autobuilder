package com.walpro.autobuilder.core.persistence;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CyclePersistenceServiceTest {

    @Mock
    private MutationSnapshotService mutationSnapshotService;

    @InjectMocks
    private CyclePersistenceService cyclePersistenceService;

    private CycleContextData ctx(String cycleName, boolean evolutionRan) {
        return CycleContextData.builder()
                .cycleName(cycleName)
                .signals(new LinkedHashMap<>())
                .evolutionRanThisCycle(evolutionRan)
                .build();
    }

    @Test
    void persistSnapshot_whenEvolutionRan_doesNotCallWriteMutationMeta() throws IOException {
        // T1-CT: CyclePersistenceService must NOT write meta.json — MutationApplier already wrote
        // it with the correct pre-promotion subgoalId. A second write would overwrite it with the
        // post-promotion context (wrong subgoalId, wrong merged=true flag).
        CycleContextData input = ctx("cycle-42", true);
        when(mutationSnapshotService.writeSnapshot(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));

        cyclePersistenceService.persistSnapshot(input);

        verify(mutationSnapshotService, never()).writeMutationMeta(any());
    }

    @Test
    void persistSnapshot_whenEvolutionRan_setsMutationPathOnReturnedContext() throws IOException {
        // Even without calling writeMutationMeta, the returned context must carry mutationPath
        // so downstream consumers (e.g. SmsCycleIngestor) can reference the folder.
        CycleContextData input = ctx("cycle-42", true);
        when(mutationSnapshotService.writeSnapshot(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));

        CycleContextData out = cyclePersistenceService.persistSnapshot(input);

        assertThat(out.mutationPath()).isEqualTo("mutations/cycle-42");
    }

    @Test
    void persistSnapshot_whenEvolutionDidNotRun_doesNotCallWriteMutationMeta() throws IOException {
        CycleContextData input = ctx("cycle-10", false);
        when(mutationSnapshotService.writeSnapshot(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));

        cyclePersistenceService.persistSnapshot(input);

        verify(mutationSnapshotService, never()).writeMutationMeta(any());
    }

    @Test
    void persistSnapshot_whenEvolutionDidNotRun_doesNotSetMutationPath() throws IOException {
        CycleContextData input = ctx("cycle-10", false);
        when(mutationSnapshotService.writeSnapshot(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));

        CycleContextData out = cyclePersistenceService.persistSnapshot(input);

        assertThat(out.mutationPath()).isNull();
    }

    @Test
    void persistSnapshot_alwaysCallsWriteSnapshot() throws IOException {
        CycleContextData input = ctx("cycle-7", true);
        when(mutationSnapshotService.writeSnapshot(any()))
                .thenAnswer(inv -> inv.getArgument(0, CycleContextData.class));

        cyclePersistenceService.persistSnapshot(input);

        verify(mutationSnapshotService).writeSnapshot(any());
    }

    @Test
    void persistSnapshot_whenWriteSnapshotFails_returnsOriginalContext() throws IOException {
        CycleContextData input = ctx("cycle-99", false);
        when(mutationSnapshotService.writeSnapshot(any())).thenThrow(new IOException("disk full"));

        CycleContextData out = cyclePersistenceService.persistSnapshot(input);

        // On error, must return the original context unchanged (no exception propagation).
        assertThat(out).isSameAs(input);
    }
}
