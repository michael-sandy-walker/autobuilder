package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalSimilarityService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.core.service.FileService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeedCoordinator {
    private final FileService fileService;
    private final GoalPlannerService goalPlannerService;
    private final GoalSimilarityService similarityService;

    /**
     * Seeds the root goal from bootstrap/seed.txt exactly once (guarded by meta/bootstrap.done).
     * Returns true if seeding was performed, false if skipped or aborted.
     */
    public void seedFromFileIfNecessary() throws IOException {
        Path seedPath = fileService.resolveSecurePath("bootstrap/seed.txt");
        if (!fileService.existsResolved(seedPath)) {
            log.info("No seed file found. Skipping bootstrap.");
            return;
        }

        Path bootstrapFlag = fileService.resolveSecurePath("meta/bootstrap.done");
        if (fileService.existsResolved(bootstrapFlag)) {
            log.info("Bootstrap already completed. Skipping.");
            return;
        }

        try {
            String seedPrompt = fileService.readFileResolved(seedPath).trim();
            if (seedPrompt.isEmpty()) {
                log.warn("Seed prompt is empty. Aborting bootstrap.");
                return;
            }

            String goalId = "goal-" + java.util.UUID.randomUUID();
            GoalEntity existingRoot = goalPlannerService.getRootGoal();

            if (existingRoot == null) {
                goalPlannerService.addInitialGoal(goalId, seedPrompt);
                log.info("Initial goal injected from seed prompt.");
            } else if (!existingRoot.getDescription().equals(seedPrompt)) {
                log.warn("Detected mismatched or legacy root goal. Overwriting goal stack with seed prompt.");
                goalPlannerService.resetInitialGoal(goalId, seedPrompt);
            }

            fileService.writeFileResolved(bootstrapFlag, Instant.now().toString());
//            goalPlannerService.getAllGoals().forEach(g -> similarityService.register(g.getDescription()));

        } catch (Exception e) {
            log.error("Seeding failed", e);
        }
    }
}
