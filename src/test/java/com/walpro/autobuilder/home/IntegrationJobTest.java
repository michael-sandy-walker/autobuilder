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
                String label = json.replaceAll(".*\"label\":\"([^\"]+)\".*", "$1");
                return new Reply(200, "{\"inputId\":\"in-" + label + "\",\"placed\":[\"a\",\"b\"],\"corrections\":[],\"conflicts\":"
                        + (label.equals("two") ? "[{\"subject\":\"the title limit\",\"heldPath\":null,\"heldLine\":null},"
                                + "{\"subject\":\"src/A.java\",\"heldPath\":\"meta/held/A.java\",\"heldLine\":null}]" : "[]")
                        + ",\"unmet\":[\"x\"],\"criteria\":4,\"met\":3,\"unanswered\":0}");
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
        IntegrationJob job = new IntegrationJob("together", projects, true, "Combine them.", "dev-auto",
                List.of(new IntegrationJob.Input("one", "take one", true), new IntegrationJob.Input("three", "take three", false)),
                launcher, run);

        job.begin();
        await(job, IntegrationJob.Phase.DECIDING, IntegrationJob.Phase.FAILED);

        assertThat(job.phase()).as("landed, not kept: nothing starts by itself").isEqualTo(IntegrationJob.Phase.DECIDING);
        assertThat(projects.resolve("together")).isDirectory();
        assertThat(launcher.describe()).containsEntry("mode", "ASK");
        assertThat(run.calls).hasSize(3);
        assertThat(run.calls.get(0)).isEqualTo("POST /autobuilder/goal Combine them.");
        assertThat(run.calls.get(1)).startsWith("POST /autobuilder/inputs").contains("\"label\":\"one\"")
                .contains("\"intent\":\"take one\"").contains("\"runs\":true").contains("\"runId\":\"unrecorded\"")
                .contains("\"pending\":true");
        assertThat(run.calls.get(2)).contains("\"label\":\"three\"").contains("\"runs\":false");
        assertThat(job.describe().get("inputs")).asString().contains("landed").contains("newFiles=2").contains("unmet=1");

        job.release();

        assertThat(job.phase()).isEqualTo(IntegrationJob.Phase.RELEASED);
        assertThat(run.calls.subList(3, run.calls.size())).containsExactly("GET /autobuilder/inputs/conflicts",
                "POST /autobuilder/inputs/in-one/keep?by=operator%20(home%20screen)",
                "POST /autobuilder/inputs/in-three/keep?by=operator%20(home%20screen)",
                "POST /autobuilder/start?mode=ADOPT_PROJECT");
    }

    @Test
    void anOpenConflictWaitsForTheOperatorAndBlocksTheRelease() throws Exception {
        project("one");
        project("two");
        ProjectLauncher launcher = launcher();
        FakeRun run = new FakeRun(launcher);
        run.openConflicts.set("[{\"id\":\"c1\",\"subject\":\"the title limit\"}]");
        IntegrationJob job = new IntegrationJob("together", projects, true, "Combine them.", null,
                List.of(new IntegrationJob.Input("one", "x", false), new IntegrationJob.Input("two", "y", false)),
                launcher, run);

        job.begin();
        await(job, IntegrationJob.Phase.DECIDING, IntegrationJob.Phase.FAILED);

        assertThat(job.phase()).isEqualTo(IntegrationJob.Phase.DECIDING);
        assertThat(job.describe().get("inputs")).asString().contains("rulesThatDiffer=1").contains("filesBothHave=1")
                .contains("conflicts=2").contains("criteria=4").contains("met=3");
        assertThatThrownBy(job::release).isInstanceOf(IllegalStateException.class).hasMessageContaining("1 conflict");
        assertThat(run.calls).noneMatch(c -> c.contains("/start"));

        run.openConflicts.set("[]");
        job.release();

        assertThat(job.phase()).isEqualTo(IntegrationJob.Phase.RELEASED);
        assertThat(run.calls).last().isEqualTo("POST /autobuilder/start?mode=ADOPT_PROJECT");
    }

    @Test
    void cancellingTakesEverythingTheJobBroughtInBackOut_andStartsNothing() throws Exception {
        project("one");
        project("two");
        ProjectLauncher launcher = launcher();
        FakeRun run = new FakeRun(launcher);
        IntegrationJob job = new IntegrationJob("together", projects, true, "Combine them.", null,
                List.of(new IntegrationJob.Input("one", "x", false), new IntegrationJob.Input("two", "y", false)),
                launcher, run);
        job.begin();
        await(job, IntegrationJob.Phase.DECIDING, IntegrationJob.Phase.FAILED);

        job.cancel();

        assertThat(job.phase()).isEqualTo(IntegrationJob.Phase.CANCELLED);
        assertThat(job.busy()).isFalse();
        assertThat(run.calls).contains("POST /autobuilder/inputs/in-one/withdraw?by=operator%20(home%20screen)",
                "POST /autobuilder/inputs/in-two/withdraw?by=operator%20(home%20screen)");
        assertThat(run.calls).noneMatch(c -> c.contains("/start") || c.contains("/keep"));
        assertThatThrownBy(job::release).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void extendingAProjectAtRestStartsItHeldBringsTheInputInAndReleasesIt() throws Exception {
        project("base");
        project("lib");
        ProjectLauncher launcher = launcher();
        FakeRun run = new FakeRun(launcher);
        IntegrationJob job = new IntegrationJob("base", projects, false, null, "dev-auto",
                List.of(new IntegrationJob.Input("lib", "use lib", false)), launcher, run);

        job.begin();
        await(job, IntegrationJob.Phase.DECIDING, IntegrationJob.Phase.FAILED);
        job.release();

        assertThat(job.phase()).isEqualTo(IntegrationJob.Phase.RELEASED);
        assertThat(launcher.describe()).containsEntry("mode", "ASK");
        assertThat(run.calls).noneMatch(c -> c.contains("/autobuilder/goal"));
        assertThat(run.calls.get(0)).startsWith("POST /autobuilder/inputs").contains("\"label\":\"lib\"");
        assertThat(run.calls).last().isEqualTo("POST /autobuilder/start?mode=ADOPT_PROJECT");
        assertThat(job.describe()).containsEntry("kind", "extend");
    }

    @Test
    void extendingTheProjectWhoseLoopRunsBringsTheInputInWithoutStartingOrReleasing() throws Exception {
        project("base");
        Path lib = Files.createDirectories(projects.resolve("../lib-" + System.nanoTime()).normalize());
        Files.writeString(lib.resolve("L.java"), "class L {}");
        try {
            ProjectLauncher launcher = launcher();
            launcher.notBegunProbe = c -> false;
            launcher.start(new ProjectChoice(projects.resolve("base").toString(), "CONTINUE_LINEAGE", null))
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            FakeRun run = new FakeRun(launcher);
            IntegrationJob job = new IntegrationJob("base", projects, false, null, null,
                    List.of(new IntegrationJob.Input(null, lib.toString(), "use it", false)), launcher, run);

            job.begin();
            await(job, IntegrationJob.Phase.DECIDING, IntegrationJob.Phase.FAILED);
            job.release();

            assertThat(job.phase()).isEqualTo(IntegrationJob.Phase.RELEASED);
            assertThat(launcher.describe()).containsEntry("mode", "CONTINUE_LINEAGE");
            assertThat(run.calls).noneMatch(c -> c.contains("/start")).anyMatch(c -> c.contains("/keep"));
            assertThat(run.calls.get(0)).contains("\"label\":\"" + lib.getFileName() + "\"").contains("\"dir\":\"" + lib + "\"");
        } finally {
            try (var all = Files.walk(lib)) {
                all.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }
    }

    @Test
    void anExtendNeedsAnExistingProjectAndAnInputThatIsNotItself() throws Exception {
        project("base");
        project("lib");

        assertThat(IntegrationJob.extendProblem(projects, "absent", new IntegrationJob.Input("lib", "x", false))).contains("no project");
        assertThat(IntegrationJob.extendProblem(projects, "base", new IntegrationJob.Input("base", "x", false))).contains("into itself");
        assertThat(IntegrationJob.extendProblem(projects, "base", new IntegrationJob.Input(null, "relative/dir", "x", false))).contains("absolute");
        assertThat(IntegrationJob.extendProblem(projects, "base", new IntegrationJob.Input("lib", "lib", "x", false))).contains("one of the two");
        assertThat(IntegrationJob.extendProblem(projects, "base", new IntegrationJob.Input("lib", "  ", false))).contains("what to take");
        assertThat(IntegrationJob.extendProblem(projects, "base", new IntegrationJob.Input("lib", "use it", false))).isNull();
    }

    @Test
    void aPathInputIsNamedAfterItsProject_notItsOutputFolder() {
        assertThat(new IntegrationJob.Input(null, "/x/code/cashbook/output", "i", false).label()).isEqualTo("cashbook");
        assertThat(new IntegrationJob.Input(null, "/x/code/cashbook/output/", "i", false).label()).isEqualTo("cashbook");
        assertThat(new IntegrationJob.Input(null, "/x/code/their-lib", "i", false).label()).isEqualTo("their-lib");
        assertThat(new IntegrationJob.Input("lib", null, "i", false).label()).isEqualTo("lib");
    }
}
