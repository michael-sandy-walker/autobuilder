package com.walpro.autobuilder.init;

import com.walpro.autobuilder.config.AutobuilderConfig;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.service.FileService;

import jakarta.annotation.PostConstruct;
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

    private final AutobuilderConfig config;
    private final FileService fileService;
    private final GoalPlannerService goalPlannerService;

    @PostConstruct
    public void resetSystem() throws IOException {
        if (!config.isResetOnStart()) return;

        log.warn("⚠️ Autobuilder system reset triggered via config (early phase)");

//        fileService.deleteFile("meta/bootstrap.done");

        for (String dir : List.of("output", "mutations", "snapshots", "meta")) {
            Path dirPath = fileService.resolveSecurePath(dir);
            if (dirPath.toFile().exists()) {
                deleteRecursive(dirPath);
                log.info("Cleaned: {}", dir);
            }
        }

//        String defaultGoal = "Build a self-evolving Spring Boot multi-agent scaffold using the OpenAI LLM API.";
//        String goalId = "goal-" + Instant.now().toEpochMilli();
//        goalPlannerService.resetInitialGoal(goalId, defaultGoal);
//        log.info("✔️ Goal stack reset with root goal: {}", defaultGoal);
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
