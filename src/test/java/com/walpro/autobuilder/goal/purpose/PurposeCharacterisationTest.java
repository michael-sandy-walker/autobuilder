package com.walpro.autobuilder.goal.purpose;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * T4-EC slice 1, the proof that nothing moved: over every existing target's seed, the purpose every reader now
 * works from is byte-identical to the root goal's text the readers used to read -- for a seed-file bootstrap (the
 * root is the trimmed file), for an operator's {@code POST /goal} (the root is the text as given), and after a
 * restart, with and without the ledger's file.
 */
class PurposeCharacterisationTest {

    static Stream<Path> seeds() throws Exception {
        try (Stream<Path> dirs = Files.list(Path.of("data"))) {
            return dirs.map(d -> d.resolve("bootstrap/seed.txt")).filter(Files::isRegularFile).sorted().toList().stream();
        }
    }

    private static GoalPlannerService planner(Path dir) throws Exception {
        GoalPlannerService s = new GoalPlannerService(new FileService(dir.toString(), new ObjectMapper()),
                mock(GoalGraphService.class), mock(GoalPlannerConfigProperties.class));
        s.init();
        return s;
    }

    private static Path project(Path seedFile) throws Exception {
        Path dir = Files.createTempDirectory("purpose-");
        Files.createDirectories(dir.resolve("bootstrap"));
        Files.writeString(dir.resolve("bootstrap/seed.txt"), Files.readString(seedFile));
        return dir;
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void aSeedFileBootstrapReadsTheRootsText(Path seedFile) throws Exception {
        String raw = Files.readString(seedFile);
        GoalPlannerService planner = planner(project(seedFile));
        planner.addInitialGoal("goal-1", raw.trim());

        assertThat(planner.specification()).isEqualTo(planner.getRootGoal().getDescription());
        assertThat(planner.purpose().ledger().seed().addedBy()).isEqualTo("seed-file");
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void anOperatorsGoalReadsTheTextAsGiven(Path seedFile) throws Exception {
        String raw = Files.readString(seedFile);
        GoalPlannerService planner = planner(project(seedFile));
        planner.addInitialGoal("goal-1", raw, "operator");

        assertThat(planner.specification()).isEqualTo(raw).isEqualTo(planner.getRootGoal().getDescription());
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void aRestartKeepsTheSameSeedEntry_andAProjectWithoutTheFileGetsOneFromItsRoot(Path seedFile) throws Exception {
        Path dir = project(seedFile);
        GoalPlannerService first = planner(dir);
        first.addInitialGoal("goal-1", Files.readString(seedFile).trim());
        InputEntry seed = first.purpose().ledger().seed();

        GoalPlannerService restarted = planner(dir);
        assertThat(restarted.purpose().ledger().seed()).isEqualTo(seed);
        assertThat(restarted.specification()).isEqualTo(restarted.getRootGoal().getDescription());

        Files.delete(dir.resolve(PurposeService.PATH));
        GoalPlannerService predatesLedger = planner(dir);
        assertThat(predatesLedger.purpose().ledger().seed().addedBy()).isEqualTo("migrated");
        assertThat(predatesLedger.specification()).isEqualTo(predatesLedger.getRootGoal().getDescription());
    }
}
