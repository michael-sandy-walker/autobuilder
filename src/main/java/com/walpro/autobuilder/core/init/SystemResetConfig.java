package com.walpro.autobuilder.core.init;

import com.walpro.autobuilder.config.AutobuilderConfigProperties;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.core.service.FileService;

import com.walpro.autobuilder.graph.goal.GoalGraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class SystemResetConfig {

    private final AutobuilderConfigProperties config;
    private final FileService fileService;
    private final GoalPlannerService goalPlannerService;
    private final GoalGraphService goalGraphService;
    private final com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService criteriaVerificationService;

    /**
     * Startup-time entry point: only resets when {@code autobuilder.service.reset-on-start=true}.
     * Other call sites that need to force a reset (e.g. the manual "Reset" button in the graph
     * browser) should invoke {@link #resetSystemForce()} directly.
     */
    public void resetSystem() throws IOException {
        if (!config.isResetOnStart()) return;
        resetSystemForce();
    }

    /**
     * Performs the full reset (graph purge, filesystem cleanup, root-goal re-seed) regardless
     * of the {@code reset-on-start} flag. Exposed so the graph browser can offer a manual reset
     * action without needing to restart the JVM.
     */
    public void resetSystemForce() throws IOException {
        log.warn("Autobuilder system reset triggered (early phase)");

        // T4-BP step 3: clear the completion-verification result cache + call counter so a manual
        // reset starts a clean cost measurement (a fresh JVM already starts at zero).
        criteriaVerificationService.resetCostMetrics();

        try {
            goalGraphService.purgeAll();
        } catch (Exception e) {
            // Keep reset resilient; filesystem reset may still be valuable.
            log.warn("Failed to purge goal graph (continuing reset): {}", e.getMessage(), e);
        }

        fileService.deleteFile("meta/bootstrap.done");
        String seedContent = fileService.readFile("bootstrap/seed.txt");

        // T4-BM (step 6a): "cache" joins the wipe — the SymbolImportCache previously survived
        // resets keyed by bare simple names, carrying one target project's domain-type NONE
        // rows and stale-version pins into the next run's worldview (22 of 25 inspected
        // entries were exactly that).
        for (String dir : List.of("output", "mutations", "snapshots", "meta", "cache")) {
            Path dirPath = fileService.resolveSecurePath(dir);
            if (dirPath.toFile().exists()) {
                deleteRecursive(dirPath);
                log.info("Cleaned: {}", dir);
            }
        }
        
        // Recreate base folders after clean reset
        for (String dir : List.of("output", "mutations", "snapshots", "meta", "responses/bootstrap")) {
            fileService.ensureFolderExists(dir);
            log.info("Created folder: {}", dir);
        }

        String goalId = "goal-" + java.util.UUID.randomUUID();
        goalPlannerService.resetInitialGoal(goalId, seedContent);
        log.info("Goal stack reset with root goal: {}", seedContent);
    }

    private void deleteRecursive(Path dir) throws IOException {
        try (Stream<Path> walk = java.nio.file.Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            java.nio.file.Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to delete: " + path, e);
                        }
                    });
        }
    }
}
