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

    @TempDir
    Path elsewhere;

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

    @Test
    void aDuplicateIsACopyOfTheBootstrapAndNothingElse() throws Exception {
        Path src = projects.resolve("original");
        Files.createDirectories(src.resolve("bootstrap/spec"));
        Files.createDirectories(src.resolve("output"));
        Files.createDirectories(src.resolve("meta"));
        Files.writeString(src.resolve("bootstrap/seed.txt"), "Build a thing.\n");
        Files.writeString(src.resolve("bootstrap/seed.obligations.txt"), "obligations keyed to the seed\n");
        Files.writeString(src.resolve("bootstrap/spec/part.md"), "part\n");
        Files.writeString(src.resolve("output/A.java"), "class A {}");
        Files.writeString(src.resolve("meta/run-end-report.json"), "{}");

        var res = controller(idle()).create(new HomeController.NewProject("copy", null, "original"));

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        try (var all = Files.walk(projects.resolve("copy"))) {
            assertThat(all.filter(Files::isRegularFile).map(p -> projects.resolve("copy").relativize(p).toString()).sorted())
                    .containsExactly("bootstrap/seed.obligations.txt", "bootstrap/seed.txt", "bootstrap/spec/part.md");
        }
        assertThat(Files.mismatch(src.resolve("bootstrap/seed.txt"), projects.resolve("copy/bootstrap/seed.txt"))).isEqualTo(-1);
        assertThat(res.getBody().get("project")).asString().contains("NEW_PROJECT");
    }

    @Test
    void aDuplicateNeedsAProjectWithASpecification() throws Exception {
        Files.createDirectories(projects.resolve("bare/output"));
        HomeController c = controller(idle());

        assertThat(c.create(new HomeController.NewProject("copy", null, "bare")).getStatusCode().value()).isEqualTo(404);
        assertThat(c.create(new HomeController.NewProject("copy", null, "../bare")).getStatusCode().value()).isEqualTo(404);
        assertThat(projects.resolve("copy")).doesNotExist();
    }

    @Test
    void anImportTakesAnAbsoluteDirectoryAndAFreeName() throws Exception {
        Path code = Files.createDirectories(elsewhere.resolve("code"));
        Files.writeString(code.resolve("A.java"), "class A {}");
        Files.createDirectories(projects.resolve("taken"));
        HomeController c = controller(idle());

        assertThat(c.importProject(new HomeController.ImportRequest("x", "relative/path", null)).getStatusCode().value()).isEqualTo(400);
        assertThat(c.importProject(new HomeController.ImportRequest("taken", code.toString(), null)).getStatusCode().value()).isEqualTo(409);
        assertThat(c.importProject(new HomeController.ImportRequest("x", projects.getParent().toString(), null)).getStatusCode().value())
                .as("a source holding the projects directory").isEqualTo(400);
        var ok = c.importProject(new HomeController.ImportRequest("imported", code.toString(), null));

        assertThat(ok.getStatusCode().value()).isEqualTo(201);
        assertThat(ok.getBody()).containsEntry("files", 1);
        assertThat(ok.getBody().get("project")).asString().contains("FOREIGN").contains("ADOPT_PROJECT");
        assertThat(projects.resolve("imported/output/A.java")).hasContent("class A {}");
    }

    @Test
    void theRunsEndpointsAreRelayedOnlyWhileARunServes() {
        HomeController c = controller(idle());

        assertThat(c.coldRead().getStatusCode().value()).isEqualTo(409);
        assertThat(c.stop().getStatusCode().value()).as("nothing to stop").isEqualTo(409);
        assertThat(c.goal("a goal").getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void aProjectHereCanBeImportedAsACopyOfItsCode() throws Exception {
        Files.createDirectories(projects.resolve("here/output/src"));
        Files.createDirectories(projects.resolve("here/bootstrap"));
        Files.createDirectories(projects.resolve("here/meta"));
        Files.writeString(projects.resolve("here/output/src/A.java"), "class A {}");
        Files.writeString(projects.resolve("here/bootstrap/seed.txt"), "its own goal\n");
        Files.writeString(projects.resolve("here/meta/run-end-report.json"), "{}");
        HomeController c = controller(idle());

        var res = c.importProject(new HomeController.ImportRequest("here-adopted", null, null, "here"));

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        try (var all = Files.walk(projects.resolve("here-adopted"))) {
            assertThat(all.filter(Files::isRegularFile).map(p -> projects.resolve("here-adopted").relativize(p).toString()))
                    .as("the code only: no goal, no history").containsExactly("output/src/A.java");
        }
        assertThat(projects.resolve("here/output/src/A.java")).hasContent("class A {}");
        assertThat(c.importProject(new HomeController.ImportRequest("x", null, null, "absent")).getStatusCode().value()).isEqualTo(404);
        assertThat(c.importProject(new HomeController.ImportRequest("x", null, null, "../here")).getStatusCode().value()).isEqualTo(404);
    }
}
