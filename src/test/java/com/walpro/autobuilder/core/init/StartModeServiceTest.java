package com.walpro.autobuilder.core.init;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-DP: a start mode is only worth naming if it can be refused.
 *
 * <p>The case these exist for happened on 2026-09-03: the documented way to start a run defaults to
 * the one mode that clears {@code output/}, and the target was a codebase the loop had not written.
 * Nothing checked, so nothing would have stopped it.
 */
class StartModeServiceTest {

    @TempDir
    Path tmp;

    private StartModeService service;

    @BeforeEach
    void setUp() {
        service = new StartModeService(new FileService(tmp.toString(), new ObjectMapper()));
    }

    @Test
    @DisplayName("starting fresh on a codebase the loop did not write is REFUSED, not warned about")
    void aForeignTreeIsNotCleared() throws IOException {
        someoneElsesProject();
        withSeed();
        service.setMode(StartMode.NEW_PROJECT);

        StartModeService.Decision d = service.decide();

        assertThat(d.situation()).isEqualTo(StartModeService.Situation.FOREIGN);
        assertThat(d.allowed())
                .as("this is the 2026-09-03 near-miss: NEW_PROJECT clears output/, and output/ is theirs")
                .isFalse();
        assertThat(d.explanation()).contains("did not write").contains("ADOPT_PROJECT");
    }

    @Test
    @DisplayName("the same tree can be adopted, which is the mode that was missing")
    void aForeignTreeCanBeAdopted() throws IOException {
        someoneElsesProject();
        service.setMode(StartMode.ADOPT_PROJECT);

        StartModeService.Decision d = service.decide();

        assertThat(d.allowed()).isTrue();
        assertThat(d.explanation()).contains("cold-read");
    }

    @Test
    @DisplayName("discarding someone else's tree stays possible, but only by saying exactly that")
    void discardingAForeignTreeNeedsItsOwnConfirmation() throws IOException {
        someoneElsesProject();
        withSeed();
        service.setMode(StartMode.NEW_PROJECT);
        service.setConfirmDiscardForeignTree(true);

        assertThat(service.decide().allowed())
                .as("an operator may still choose it; they may not arrive at it by default")
                .isTrue();
    }

    @Test
    @DisplayName("the loop's own tree is cleared without ceremony, exactly as before")
    void itsOwnTreeIsStillClearable() throws IOException {
        someoneElsesProject();
        withSeed();
        Files.createDirectories(tmp.resolve("meta"));
        Files.writeString(tmp.resolve("meta/GoalStack.json"),
                "{\"goalTree\":[{\"id\":\"goal-1\",\"status\":\"ACTIVE\"}]}");
        service.setMode(StartMode.NEW_PROJECT);

        StartModeService.Decision d = service.decide();

        assertThat(d.situation()).isEqualTo(StartModeService.Situation.LOOP_AUTHORED);
        assertThat(d.allowed()).isTrue();
    }

    @Test
    @DisplayName("an EMPTY goal stack is not evidence of authorship — the loop writes one on the way in")
    void anEmptyGoalStackDoesNotMakeATreeItsOwn() throws IOException {
        // The defect this closes, caught by running it: the goal planner writes an empty stack
        // while beans are still being created, before the start check runs. On its second boot a
        // foreign codebase therefore read as the loop's own work, NEW_PROJECT was allowed, and the
        // reset began. The tree survived only because the reset reads a seed file first and that
        // file happened to be missing.
        someoneElsesProject();
        withSeed();
        Files.createDirectories(tmp.resolve("meta"));
        Files.writeString(tmp.resolve("meta/GoalStack.json"),
                "{\"currentGoalId\":null,\"currentSubGoalId\":null,\"goalTree\":[]}");
        service.setMode(StartMode.NEW_PROJECT);

        StartModeService.Decision d = service.decide();

        assertThat(d.situation())
                .as("a stack with no goals in it is what this run's own startup left there")
                .isEqualTo(StartModeService.Situation.FOREIGN);
        assertThat(d.allowed()).isFalse();
    }

    @Test
    @DisplayName("per-cycle records ARE evidence of authorship, because only a run leaves them")
    void mutationsMakeATreeItsOwn() throws IOException {
        someoneElsesProject();
        withSeed();
        Files.createDirectories(tmp.resolve("mutations/cycle-1"));
        Files.writeString(tmp.resolve("mutations/cycle-1/meta.json"), "{}");
        service.setMode(StartMode.NEW_PROJECT);

        assertThat(service.decide().situation()).isEqualTo(StartModeService.Situation.LOOP_AUTHORED);
    }

    @Test
    @DisplayName("a new project with no specification is refused before the reset can start deleting")
    void aNewProjectNeedsSomethingToBuildFrom() {
        service.setMode(StartMode.NEW_PROJECT);

        StartModeService.Decision d = service.decide();

        assertThat(d.allowed())
                .as("the reset reads the seed before it deletes, so this used to die mid-reset")
                .isFalse();
        assertThat(d.explanation()).contains("bootstrap/seed.txt");
    }

    @Test
    @DisplayName("an empty tree with a specification starts fresh, the ordinary case, unchanged")
    void anEmptyTreeStartsFresh() throws IOException {
        withSeed();
        service.setMode(StartMode.NEW_PROJECT);

        StartModeService.Decision d = service.decide();

        assertThat(d.situation()).isEqualTo(StartModeService.Situation.EMPTY);
        assertThat(d.allowed()).isTrue();
    }

    @Test
    @DisplayName("continuing a lineage that is not there is refused rather than falling through to a reset")
    void continuingNothingIsRefused() {
        service.setMode(StartMode.CONTINUE_LINEAGE);

        StartModeService.Decision d = service.decide();

        assertThat(d.allowed())
                .as("falling through from a failed resume to a reset is how the destructive default hid")
                .isFalse();
    }

    @Test
    @DisplayName("ASK touches nothing and never becomes an action")
    void askIsARestingState() throws IOException {
        someoneElsesProject();
        service.setMode(StartMode.ASK);

        StartModeService.Decision d = service.decide();

        assertThat(d.allowed()).isFalse();
        assertThat(d.explanation()).contains("resting state");
    }

    @Test
    @DisplayName("a tree that cannot be read is treated as occupied, because the other reading deletes it")
    void anUnreadableTreeIsTreatedAsOccupied() throws IOException {
        StartModeService unreadable = new StartModeService(new FileService(
                tmp.resolve("does-not-exist").toString(), new ObjectMapper()));
        unreadable.setMode(StartMode.NEW_PROJECT);

        assertThat(unreadable.decide()).isNotNull();
    }

    @Test
    @DisplayName("the catalogue and the startup check judge the same tree the same way")
    void oneRuleNotTwo() throws IOException {
        // These were two rules for one question and they disagreed about the same directory within
        // minutes of both existing: the catalogue offered a mode the startup check then refused.
        someoneElsesProject();
        withSeed();

        assertThat(StartModeService.Situation.of(tmp))
                .isEqualTo(service.situation());

        Files.createDirectories(tmp.resolve("mutations/cycle-1"));
        Files.writeString(tmp.resolve("mutations/cycle-1/meta.json"), "{}");

        assertThat(StartModeService.Situation.of(tmp))
                .as("and they still agree once the tree changes under them")
                .isEqualTo(service.situation());
    }

    /** A specification, which NEW_PROJECT builds from and therefore requires. */
    private void withSeed() throws IOException {
        Files.createDirectories(tmp.resolve("bootstrap"));
        Files.writeString(tmp.resolve("bootstrap/seed.txt"), "Build a calculator.");
    }

    /** An output tree with sources and none of the loop's bookkeeping beside it. */
    private void someoneElsesProject() throws IOException {
        Files.createDirectories(tmp.resolve("output/src/main/java"));
        Files.writeString(tmp.resolve("output/pom.xml"), "<project/>");
        Files.writeString(tmp.resolve("output/src/main/java/Calculator.java"), "class Calculator {}");
    }
}
