package com.walpro.autobuilder.home;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HomeControllerTest {

    @TempDir
    Path projects;

    private HomeController controller(ProjectLauncher launcher) {
        return new HomeController(launcher, new HomeApplication.ProjectsDirectory(projects));
    }

    private static ProjectLauncher idle() {
        return new ProjectLauncher(new String[0], (c, args) -> mock(ConfigurableApplicationContext.class));
    }

    @Test
    void aNewProjectIsItsSpecificationAndNothingElse() throws Exception {
        var res = controller(idle()).create(new HomeController.NewProject("my-service", "Build a thing."));

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat(Files.readString(projects.resolve("my-service/bootstrap/seed.txt"))).isEqualTo("Build a thing.\n");
        try (var all = Files.walk(projects.resolve("my-service"))) {
            assertThat(all.filter(Files::isRegularFile).toList()).hasSize(1);
        }
        assertThat(res.getBody().get("project")).asString().contains("NEW_PROJECT");
    }

    @Test
    void aNewProjectNeverOverwritesOne() throws Exception {
        Files.createDirectories(projects.resolve("taken/bootstrap"));
        Files.writeString(projects.resolve("taken/bootstrap/seed.txt"), "original\n");

        var res = controller(idle()).create(new HomeController.NewProject("taken", "replacement"));

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(Files.readString(projects.resolve("taken/bootstrap/seed.txt"))).isEqualTo("original\n");
    }

    @Test
    void aNewProjectNeedsADirectoryNameAndASpecification() {
        HomeController c = controller(idle());
        for (String bad : List.of("", "../escape", "a/b", "Upper", ".hidden", "x".repeat(65))) {
            assertThat(c.create(new HomeController.NewProject(bad, "spec")).getStatusCode().value())
                    .as(bad).isEqualTo(400);
        }
        assertThat(c.create(new HomeController.NewProject("ok-name", "  \n")).getStatusCode().value()).isEqualTo(400);
        assertThat(projects.resolve("ok-name")).doesNotExist();
    }

    @Test
    void startTakesANameInTheProjectsDirectoryOnly() throws Exception {
        Files.createDirectories(projects.resolve("here"));
        HomeController c = controller(idle());

        assertThat(c.start(new HomeController.StartRequest("../here", null, null)).getStatusCode().value()).isEqualTo(400);
        assertThat(c.start(new HomeController.StartRequest("absent", null, null)).getStatusCode().value()).isEqualTo(404);
        assertThat(c.start(new HomeController.StartRequest("here", "SIDEWAYS", null)).getStatusCode().value()).isEqualTo(400);
        assertThat(c.start(new HomeController.StartRequest("here", "CONTINUE_LINEAGE", "dev-auto")).getStatusCode().value())
                .isEqualTo(202);
    }

    @Test
    void aSecondStartIsAConflict() throws Exception {
        Files.createDirectories(projects.resolve("a"));
        Files.createDirectories(projects.resolve("b"));
        CountDownLatch release = new CountDownLatch(1);
        ProjectLauncher slow = new ProjectLauncher(new String[0], (c, args) -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return mock(ConfigurableApplicationContext.class);
        });
        HomeController c = controller(slow);

        assertThat(c.start(new HomeController.StartRequest("a", null, null)).getStatusCode().value()).isEqualTo(202);
        assertThat(c.start(new HomeController.StartRequest("b", null, null)).getStatusCode().value()).isEqualTo(409);
        release.countDown();
    }

    @Test
    void goingHomeIsRefusedWhileTheLoopRuns() throws Exception {
        Files.createDirectories(projects.resolve("a"));
        ProjectLauncher launcher = idle();
        HomeController c = controller(launcher);
        c.start(new HomeController.StartRequest("a", null, null));
        for (int i = 0; i < 500 && launcher.state() == ProjectLauncher.State.STARTING; i++) Thread.sleep(20);
        assertThat(launcher.state()).isEqualTo(ProjectLauncher.State.RUNNING);

        assertThat(c.returnHome().getStatusCode().value()).isEqualTo(409);
        launcher.haltProbe = ctx -> java.util.Optional.of("goal-fulfilled");
        assertThat(c.returnHome().getStatusCode().value()).isEqualTo(200);
        assertThat(launcher.state()).isEqualTo(ProjectLauncher.State.IDLE);
    }

    @Test
    void aProjectListsHowItsLastRunEnded() throws Exception {
        Files.createDirectories(projects.resolve("done/meta"));
        Files.createDirectories(projects.resolve("done/output"));
        Files.writeString(projects.resolve("done/output/A.java"), "class A {}");
        Files.writeString(projects.resolve("done/meta/run-end-report.json"),
                "{\"terminalReason\":\"goal-fulfilled\",\"writtenAt\":\"2026-11-03T00:22:20Z\"}");
        Files.createDirectories(projects.resolve("halted/meta"));
        Files.writeString(projects.resolve("halted/meta/run-end-report.json"),
                "{\"terminalReason\":\"root-convergence-stall\",\"writtenAt\":\"2026-11-02T10:00:00Z\"}");
        Files.createDirectories(projects.resolve("fresh/bootstrap"));

        var listed = new com.walpro.autobuilder.core.init.TargetCatalog().targetsUnder(projects);

        assertThat(listed).extracting(t -> t.name(), t -> t.lastEnd() == null ? null : t.lastEnd().fulfilled())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("done", true),
                        org.assertj.core.groups.Tuple.tuple("fresh", null),
                        org.assertj.core.groups.Tuple.tuple("halted", false));
        assertThat(listed.get(2).lastEnd().reason()).isEqualTo("root-convergence-stall");
    }
}
