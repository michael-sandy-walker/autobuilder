package com.walpro.autobuilder.init;

import com.walpro.autobuilder.config.AutobuilderConfigProperties;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.service.FileService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
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

    public void resetSystem() throws IOException {
        if (!config.isResetOnStart()) return;

        log.warn("⚠️ Autobuilder system reset triggered via config (early phase)");

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

        String goalId = "goal-" + Instant.now().toEpochMilli();
        goalPlannerService.resetInitialGoal(goalId, seedContent);
        log.info("✔️ Goal stack reset with root goal: {}", seedContent);
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
