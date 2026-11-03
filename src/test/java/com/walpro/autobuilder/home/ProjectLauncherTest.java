package com.walpro.autobuilder.home;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ProjectLauncherTest {

    @Test
    void noProjectAtStartupMeansTheHomeScreen() {
        assertThat(ProjectChoice.fromStartup(new String[]{"--spring.profiles.active=dev-auto"},
                new Properties(), Map.of())).isNull();
    }

    @Test
    void projectAtStartupIsReadInSpringsOrder() {
        Properties sys = new Properties();
        sys.setProperty("FILE_BASE_PATH", "data/from-sys");
        Map<String, String> env = Map.of("FILE_BASE_PATH", "data/from-env");

        assertThat(ProjectChoice.fromStartup(new String[0], new Properties(), env).project()).isEqualTo("data/from-env");
        assertThat(ProjectChoice.fromStartup(new String[0], sys, env).project()).isEqualTo("data/from-sys");
        assertThat(ProjectChoice.fromStartup(new String[]{"--file.base-path=data/from-arg"}, sys, env).project())
                .isEqualTo("data/from-arg");
    }

    @Test
    void theChoiceReplacesWhatTheStartupArgumentsCarriedAndKeepsTheRest() {
        ProjectLauncher launcher = new ProjectLauncher(new String[]{
                "--spring.profiles.active=dev-auto,fast", "--file.base-path=data/old",
                "--autobuilder.run.budget-minutes=60", "--autobuilder.start.mode=ADOPT_PROJECT"}, (c, args) -> null);

        assertThat(launcher.runArgs(new ProjectChoice("data/new", "new_project", null))).containsExactly(
                "--spring.profiles.active=dev-auto,fast", "--autobuilder.run.budget-minutes=60",
                "--file.base-path=data/new", "--autobuilder.start.mode=NEW_PROJECT");
    }

    @Test
    void theFastForwardPassesTheStartupArgumentsThroughUnchanged() {
        String[] startup = {"--spring.profiles.active=dev-auto,fast", "--autobuilder.run.budget-minutes=60"};
        ProjectLauncher launcher = new ProjectLauncher(startup, (c, args) -> null);

        assertThat(launcher.runArgs(new ProjectChoice("data/x", null, null)))
                .containsExactly("--spring.profiles.active=dev-auto,fast", "--autobuilder.run.budget-minutes=60",
                        "--file.base-path=data/x");
    }

    @Test
    void aSecondStartIsRefusedWhileOneIsStarting() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ProjectLauncher launcher = new ProjectLauncher(new String[0], (c, args) -> {
            awaitQuietly(release);
            return mock(ConfigurableApplicationContext.class);
        });
        CompletableFuture<ConfigurableApplicationContext> first = launcher.start(new ProjectChoice("data/a", null, null));

        assertThatThrownBy(() -> launcher.start(new ProjectChoice("data/b", null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("data/a");
        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        assertThat(launcher.state()).isEqualTo(ProjectLauncher.State.RUNNING);
        assertThatThrownBy(() -> launcher.start(new ProjectChoice("data/b", null, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFailedStartIsReportedAndAllowsAnotherStart() {
        ProjectLauncher launcher = new ProjectLauncher(new String[0], (c, args) -> {
            throw new IllegalStateException("port in use");
        });
        CompletableFuture<ConfigurableApplicationContext> started = launcher.start(new ProjectChoice("data/a", null, null));

        assertThatThrownBy(started::join).isInstanceOf(CompletionException.class);
        assertThat(launcher.state()).isEqualTo(ProjectLauncher.State.FAILED);
        assertThat(launcher.describe().get("failure")).asString().contains("port in use");
        launcher.start(new ProjectChoice("data/b", null, null)).exceptionally(e -> null).join();
        assertThat(launcher.describe().get("project")).isEqualTo("data/b");
    }

    @Test
    void anUnknownModeIsRefusedBeforeAnythingStarts() {
        ProjectLauncher launcher = new ProjectLauncher(new String[0], (c, args) -> null);

        assertThatThrownBy(() -> launcher.start(new ProjectChoice("data/a", "SIDEWAYS", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(launcher.state()).isEqualTo(ProjectLauncher.State.IDLE);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
