package com.walpro.autobuilder.core.service;

import java.io.IOException;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
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

    /**
     * Controls whether the Autobuilder bootstrap cycle is executed on startup.
     * Default: true (enabled in normal runtime).
     * In tests, we set this to false via application-test.properties.
     */
    @Value("${autobuilder.bootstrap.enabled:true}")
    private boolean bootstrapEnabled = true;

    /**
     * Controls whether the scheduled runAutobuilderCycle() should actually run.
     * Default: true in normal runtime. In tests: false.
     */
    @Value("${autobuilder.scheduler.enabled:true}")
    private boolean schedulerEnabled = true;

    @PostConstruct
    public void init() throws IOException {
        if (!bootstrapEnabled) {
            log.warn("⚠️ Autobuilder bootstrap disabled via configuration (autobuilder.bootstrap.enabled=false)");
            return;
        }

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
        if (!schedulerEnabled) {
            log.warn("⚠️ Autobuilder scheduler disabled via configuration (autobuilder.scheduler.enabled=false)");
            return;
        }

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
