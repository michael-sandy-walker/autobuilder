package com.walpro.autobuilder.home;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class IntegrationJobTest {

    @TempDir
    Path projects;

    /** The run's endpoints as a script: what was called, and what each answers. */
    static class FakeRun extends RunClient {
        final List<String> calls = new ArrayList<>();
        final AtomicReference<String> openConflicts = new AtomicReference<>("[]");

        FakeRun(ProjectLauncher launcher) {
            super(launcher);
        }

        @Override
        Reply get(String path) {
            calls.add("GET " + path);
            return new Reply(200, openConflicts.get());
        }

        @Override
        Reply postText(String path, String text) {
            calls.add("POST " + path + " " + text);
            return new Reply(200, "{\"ok\":true}");
        }

        @Override
        Reply postJson(String path, String json, Duration timeout) {
            calls.add("POST " + path + (json.isEmpty() ? "" : " " + json));
            if (path.equals("/autobuilder/inputs")) {
                return new Reply(200, "{\"placed\":[\"a\",\"b\"],\"corrections\":[],\"conflicts\":"
                        + (json.contains("\"label\":\"two\"") ? "[{\"subject\":\"the title limit\"}]" : "[]") + ",\"unmet\":[\"x\"]}");
            }
            return new Reply(200, "{\"ok\":true}");
        }
    }

    private void project(String name) throws Exception {
        Files.createDirectories(projects.resolve(name + "/output"));
        Files.writeString(projects.resolve(name + "/output/A.java"), "class A {}");
    }

    private static ProjectLauncher launcher() {
        return new ProjectLauncher(new String[0], (c, args) -> mock(ConfigurableApplicationContext.class));
    }

    private static void await(IntegrationJob job, IntegrationJob.Phase... phases) throws InterruptedException {
        for (int i = 0; i < 250 && !List.of(phases).contains(job.phase()); i++) Thread.sleep(20);
        assertThat(job.phase()).isIn((Object[]) phases);
    }

    @Test
    void theRequestIsCheckedBeforeAnythingIsCreated() throws Exception {
        project("one");
        project("two");
        Files.createDirectories(projects.resolve("empty/output"));
        var one = new IntegrationJob.Input("one", "take it", false);
        var two = new IntegrationJob.Input("two", "take it", false);

        assertThat(IntegrationJob.problem(projects, "new", "goal", List.of(one))).contains("two or more");
        assertThat(IntegrationJob.problem(projects, "new", " ", List.of(one, two))).contains("goal");
        assertThat(IntegrationJob.problem(projects, "one", "goal", List.of(one, two))).contains("already exists");
        assertThat(IntegrationJob.problem(projects, "new", "goal", List.of(one, one))).contains("twice");
        assertThat(IntegrationJob.problem(projects, "new", "goal",
                List.of(new IntegrationJob.Input("one", "x", true), new IntegrationJob.Input("two", "x", true)))).contains("at most one");
        assertThat(IntegrationJob.problem(projects, "new", "goal",
                List.of(one, new IntegrationJob.Input("empty", "x", false)))).contains("no code");
        assertThat(IntegrationJob.problem(projects, "new", "goal",
                List.of(one, new IntegrationJob.Input("two", "", false)))).contains("what to take");
        assertThat(IntegrationJob.problem(projects, "new", "goal", List.of(one, two))).isNull();
        assertThat(projects.resolve("new")).doesNotExist();
    }

    @Test
    void withoutConflictsTheGoalIsSetEachProjectIsBroughtInAndTheRunIsReleased() throws Exception {
        project("one");
        project("three");
        ProjectLauncher launcher = launcher();
        FakeRun run = new FakeRun(launcher);
        IntegrationJob job = new IntegrationJob("together", projects, "Combine them.", "dev-auto",
                List.of(new IntegrationJob.Input("one", "take one", true), new IntegrationJob.Input("three", "take three", false)),
                launcher, run);

        job.begin();
        await(job, IntegrationJob.Phase.RELEASED, IntegrationJob.Phase.FAILED);

        assertThat(job.phase()).isEqualTo(IntegrationJob.Phase.RELEASED);
        assertThat(projects.resolve("together")).isDirectory();
        assertThat(launcher.describe()).containsEntry("mode", "ASK");
        assertThat(run.calls).hasSize(6);
        assertThat(run.calls.get(0)).isEqualTo("POST /autobuilder/goal Combine them.");
        assertThat(run.calls.get(1)).startsWith("POST /autobuilder/inputs").contains("\"label\":\"one\"")
                .contains("\"intent\":\"take one\"").contains("\"runs\":true").contains("\"runId\":\"unrecorded\"");
        assertThat(run.calls.get(2)).contains("\"label\":\"three\"").contains("\"runs\":false");
        assertThat(run.calls.get(3)).isEqualTo("GET /autobuilder/inputs/conflicts");
        assertThat(run.calls.get(5)).as("the release re-reads the conflicts first").isEqualTo("POST /autobuilder/start?mode=ADOPT_PROJECT");
        assertThat(job.describe().get("inputs")).asString().contains("landed").contains("placed=2").contains("unmet=1");
    }

    @Test
    void anOpenConflictWaitsForTheOperatorAndBlocksTheRelease() throws Exception {
        project("one");
        project("two");
        ProjectLauncher launcher = launcher();
        FakeRun run = new FakeRun(launcher);
        run.openConflicts.set("[{\"id\":\"c1\",\"subject\":\"the title limit\"}]");
        IntegrationJob job = new IntegrationJob("together", projects, "Combine them.", null,
                List.of(new IntegrationJob.Input("one", "x", false), new IntegrationJob.Input("two", "y", false)),
                launcher, run);

        job.begin();
        await(job, IntegrationJob.Phase.DECIDING, IntegrationJob.Phase.FAILED);

        assertThat(job.phase()).isEqualTo(IntegrationJob.Phase.DECIDING);
        assertThat(job.describe().get("inputs")).asString().contains("the title limit");
        assertThatThrownBy(job::release).isInstanceOf(IllegalStateException.class).hasMessageContaining("1 conflict");
        assertThat(run.calls).noneMatch(c -> c.contains("/start"));

        run.openConflicts.set("[]");
        job.release();

        assertThat(job.phase()).isEqualTo(IntegrationJob.Phase.RELEASED);
        assertThat(run.calls).last().isEqualTo("POST /autobuilder/start?mode=ADOPT_PROJECT");
    }
}
