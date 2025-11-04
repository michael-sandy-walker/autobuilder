package com.walpro.autobuilder.core.service;

import java.io.IOException;
import java.util.Optional;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.walpro.autobuilder.config.AutobuilderConfigProperties;
import com.walpro.autobuilder.core.lifecycle.BootstrapCoordinator;
import com.walpro.autobuilder.core.lifecycle.CycleContextCoordinator;
import com.walpro.autobuilder.core.init.SystemResetConfig;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.CycleExecutor;
import com.walpro.autobuilder.core.persistence.CyclePersistenceService;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class AutobuilderService {

    private final BootstrapCoordinator bootstrapCoordinator;
    private final FileService fileService;
    private final AutobuilderConfigProperties autobuilderConfig;
    private final SystemResetConfig systemResetConfig;
    private final CycleContextCoordinator contextCoordinator;
    private final CycleExecutor cycleExecutor;
    private final CyclePersistenceService cyclePersistenceService;

    private CycleContextData context = null;

    @PostConstruct
    public void init() throws IOException {
        if (autobuilderConfig.isResetOnStart()) {
            log.warn("⚠️ Autobuilder system reset triggered via config (AutobuilderService)");
            systemResetConfig.resetSystem();
        }

        boolean bootstrapped = bootstrapCoordinator.bootstrapIfNecessary();
        if (!bootstrapped) return;

        fileService.ensureFolderExists("output");

        Optional<CycleContextData> initialContext = bootstrapCoordinator.prepareContext(context);
        if (initialContext.isEmpty()) {
            log.warn("Aborting bootstrap cycle: unable to prepare context.");
            return;
        }

        context = cycleExecutor.bootstrapCycle(initialContext.get());
        cyclePersistenceService.writeMetadata(context);
        log.info("✅ Bootstrap completed. First files written.");    
    }

    @Scheduled(fixedDelay = 60000)
    public void runAutobuilderCycle() {
        Optional<CycleContextData> initialContext = contextCoordinator.prepareRuntimeContext(context);
        if (initialContext.isEmpty()) {
            log.warn("Aborting cycle: unable to prepare context.");
            return;
        }

        context = cycleExecutor.runCycle(initialContext.orElse(context));

        cyclePersistenceService.persistSnapshot(context);

        log.info("🧬 Cycle {} complete. Result: {}, Merged: {}", context.cycleName(), context.cycleResultState(), context.merged());
    }
}
