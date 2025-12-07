package com.walpro.autobuilder.core.service;

import com.walpro.autobuilder.config.AutobuilderConfigProperties;
import com.walpro.autobuilder.core.init.SystemResetConfig;
import com.walpro.autobuilder.core.lifecycle.BootstrapCoordinator;
import com.walpro.autobuilder.core.lifecycle.CycleContextCoordinator;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.CycleExecutor;
import com.walpro.autobuilder.core.persistence.CyclePersistenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutobuilderServiceTest {

    @Mock
    private BootstrapCoordinator bootstrapCoordinator;

    @Mock
    private FileService fileService;

    @Mock
    private AutobuilderConfigProperties autobuilderConfig;

    @Mock
    private SystemResetConfig systemResetConfig;

    @Mock
    private CycleContextCoordinator contextCoordinator;

    @Mock
    private CycleExecutor cycleExecutor;

    @Mock
    private CyclePersistenceService cyclePersistenceService;

    @InjectMocks
    private AutobuilderService autobuilderService;

    @Test
    void init_abortsBootstrapWhenPrepareContextEmpty() throws Exception {
        when(autobuilderConfig.isResetOnStart()).thenReturn(false);
        when(bootstrapCoordinator.bootstrapIfNecessary()).thenReturn(true);
        when(bootstrapCoordinator.prepareContext(null)).thenReturn(Optional.empty());

        autobuilderService.init();

        verify(fileService).ensureFolderExists("output");
        verify(cycleExecutor, never()).bootstrapCycle(any());
        verify(cyclePersistenceService, never()).writeMetadata(any());
    }

    @Test
    void init_runsBootstrapAndWritesMetadataWhenContextAvailable() throws Exception {
        when(autobuilderConfig.isResetOnStart()).thenReturn(false);
        when(bootstrapCoordinator.bootstrapIfNecessary()).thenReturn(true);

        CycleContextData prepared = emptyContext();
        CycleContextData bootstrapped = emptyContext();

        when(bootstrapCoordinator.prepareContext(null)).thenReturn(Optional.of(prepared));
        when(cycleExecutor.bootstrapCycle(prepared)).thenReturn(bootstrapped);

        autobuilderService.init();

        verify(fileService).ensureFolderExists("output");
        verify(cycleExecutor).bootstrapCycle(prepared);
        verify(cyclePersistenceService).writeMetadata(bootstrapped);
    }

    @Test
    void init_skipsBootstrapWhenBootstrapNotNecessary() throws Exception {
        when(autobuilderConfig.isResetOnStart()).thenReturn(false);
        when(bootstrapCoordinator.bootstrapIfNecessary()).thenReturn(false);

        autobuilderService.init();

        // bootstrap was checked
        verify(bootstrapCoordinator).bootstrapIfNecessary();

        // nothing else should happen
        verify(bootstrapCoordinator, never()).prepareContext(any());
        verify(cycleExecutor, never()).bootstrapCycle(any());
        verify(cyclePersistenceService, never()).writeMetadata(any());
        verifyNoInteractions(fileService);
    }

    @Test
    void runAutobuilderCycle_abortsWhenRuntimeContextNotAvailable() {
        when(contextCoordinator.prepareRuntimeContext(any())).thenReturn(Optional.empty());

        autobuilderService.runAutobuilderCycle();

        verify(cycleExecutor, never()).runCycle(any());
        verify(cyclePersistenceService, never()).persistSnapshot(any());
    }

    @Test
    void runAutobuilderCycle_runsCycleAndPersistsSnapshot() {
        CycleContextData prepared = emptyContext();
        CycleContextData afterRun = emptyContext();

        when(contextCoordinator.prepareRuntimeContext(any()))
                .thenReturn(Optional.of(prepared));
        when(cycleExecutor.runCycle(prepared)).thenReturn(afterRun);

        autobuilderService.runAutobuilderCycle();

        verify(cycleExecutor).runCycle(prepared);
        verify(cyclePersistenceService).persistSnapshot(afterRun);

        // Optional: assert that AutobuilderService.context was updated
        // (uses reflection to access the private field)
        try {
            var field = AutobuilderService.class.getDeclaredField("context");
            field.setAccessible(true);
            CycleContextData stored = (CycleContextData) field.get(autobuilderService);
            assertThat(stored).isSameAs(afterRun);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static CycleContextData emptyContext() {
        try {
            Constructor<?> ctor = CycleContextData.class.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            Class<?>[] paramTypes = ctor.getParameterTypes();
            Object[] args = new Object[paramTypes.length];

            for (int i = 0; i < paramTypes.length; i++) {
                Class<?> type = paramTypes[i];
                if (!type.isPrimitive()) {
                    args[i] = null;
                } else if (type == boolean.class) {
                    args[i] = false;
                } else if (type == int.class || type == long.class
                        || type == short.class || type == byte.class) {
                    args[i] = 0;
                } else if (type == double.class || type == float.class) {
                    args[i] = 0.0;
                } else if (type == char.class) {
                    args[i] = '\0';
                } else {
                    args[i] = null;
                }
            }

            return (CycleContextData) ctor.newInstance(args);
        } catch (Exception e) {
            throw new RuntimeException("Failed to construct empty CycleContextData for tests", e);
        }
    }
}
