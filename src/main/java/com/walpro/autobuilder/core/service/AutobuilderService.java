package com.walpro.autobuilder.core.service;

import java.io.IOException;
import java.util.Optional;

import com.walpro.autobuilder.memory.sms.SmsCycleIngestor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.walpro.autobuilder.config.AutobuilderConfigProperties;
import com.walpro.autobuilder.core.lifecycle.SeedCoordinator;
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

    private final SeedCoordinator seedCoordinator;
    private final FileService fileService;
    private final AutobuilderConfigProperties autobuilderConfig;
    private final SystemResetConfig systemResetConfig;
    private final CycleContextCoordinator contextCoordinator;
    private final CycleExecutor cycleExecutor;
    private final CyclePersistenceService cyclePersistenceService;
    private final CycleLoggingService cycleLoggingService;
    private final SmsCycleIngestor smsCycleIngestor;

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

    /**
     * Last fully executed context (bootstrap or normal cycle).
     * May be {@code null} before the first successful run.
     */
    private volatile CycleContextData lastContext;

    public Optional<CycleContextData> getLastContext() {
        return Optional.ofNullable(lastContext);
    }

    @PostConstruct
    public void init() throws IOException {
        if (!bootstrapEnabled) {
            log.warn("Autobuilder bootstrap disabled via configuration (autobuilder.bootstrap.enabled=false)");
            return;
        }

        if (autobuilderConfig.isResetOnStart()) {
            log.warn("Autobuilder system reset triggered via config (AutobuilderService)");
            systemResetConfig.resetSystem();
        }

        boolean bootstrapped = seedCoordinator.seedFromFileIfNecessary();
        if (!bootstrapped) return;

        fileService.ensureFolderExists("output");

        runAutobuilderCycle();
    }

    @Scheduled(fixedDelay = 60000)
    public void runAutobuilderCycle() {
        if (context != null && context.cycleResultState() != null) {
            switch (context.cycleResultState()) {
                case GOAL_FULFILLED -> {
                    log.info("Goal fulfilled. Pausing Autobuilder.");
                    return;
                }
                case GOAL_EXHAUSTED -> {
                    log.warn("Goal exhausted. Awaiting new goal.");
                    return;
                }
                case STAGNATING -> {
                    // TODO: e.g., allow one recombination cycle
                }
                case CONTINUE -> {
                    // next scheduled run
                }
            }
        }

        if (!schedulerEnabled) {
            log.warn("Autobuilder scheduler disabled via configuration (autobuilder.scheduler.enabled=false)");
            return;
        }

        Optional<CycleContextData> initialContext = contextCoordinator.prepareRuntimeContext(context);
        if (initialContext.isEmpty()) {
            log.warn("Aborting cycle: unable to prepare context.");
            return;
        }

        context = cycleExecutor.runCycle(initialContext.orElse(context));

        context = cyclePersistenceService.persistSnapshot(context);
        cycleLoggingService.logCycleSummary(context);

        smsCycleIngestor.ingestCycle(context);

        log.info("Cycle {} complete. Result: {}, Merged: {}", context.cycleName(), context.cycleResultState(), context.merged());

        this.lastContext = context;
    }

    public void bootstrapOnce() {
        try {
            init(); // respects reset-on-start flag and existing bootstrap logic
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
