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

    public void resetSystem() throws IOException {
        if (!config.isResetOnStart()) return;

        log.warn("Autobuilder system reset triggered via config (early phase)");

        try {
            goalGraphService.purgeAll();
        } catch (Exception e) {
            // Keep reset resilient; filesystem reset may still be valuable.
            log.warn("Failed to purge goal graph (continuing reset): {}", e.getMessage(), e);
        }

        fileService.deleteFile("meta/bootstrap.done");
        String seedContent = fileService.readFile("bootstrap/seed.txt");

        for (String dir : List.of("output", "mutations", "snapshots", "meta")) {
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
