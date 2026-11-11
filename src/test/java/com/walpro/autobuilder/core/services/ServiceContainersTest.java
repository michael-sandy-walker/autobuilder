package com.walpro.autobuilder.core.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceContainersTest {

    @TempDir
    Path project;
    @TempDir
    Path tree;

    private final List<List<String>> calls = new ArrayList<>();

    private ServiceContainers containers(ProjectServices services, boolean ready) {
        return new ServiceContainers(services, 1500, (args, t) -> {
            calls.add(args);
            return new ServiceContainers.Ran(0, args.get(0).equals("create") ? "abc123\n" : "");
        }, port -> ready);
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private ServiceProposal mysql(int port, String initScript) {
        return new ServiceProposal("mysql", "mysql:8.0", port, 3306, Map.of("MYSQL_ALLOW_EMPTY_PASSWORD", "yes"),
                "/docker-entrypoint-initdb.d", List.of("basedata.sql"), initScript, "model", List.of("a:1: x"), "link failure");
    }

    @Test
    void nothingSaidNothingStarted() {
        ProjectServices services = new ProjectServices(project.toString());
        ServiceContainers.Started s = containers(services, true).startFresh(tree);
        assertThat(s.failed()).isFalse();
        assertThat(s.names()).isEmpty();
        assertThat(calls).isEmpty();
    }

    @Test
    void aNoStartsNothing() throws Exception {
        ProjectServices services = new ProjectServices(project.toString());
        services.record(new ProjectServices.Decision(false, List.of(mysql(freePort(), null)), Instant.now()));
        containers(services, true).startFresh(tree).close();
        assertThat(calls).isEmpty();
    }

    @Test
    void aYesIsCreatedFreshAroundOneStartAndRemovedAfterIt() throws Exception {
        Files.writeString(tree.resolve("basedata.sql"), "select 1;");
        int port = freePort();
        ProjectServices services = new ProjectServices(project.resolve("my shop").toString());
        services.record(new ProjectServices.Decision(true, List.of(mysql(port, "basedata.sql")), Instant.now()));
        ServiceContainers c = containers(services, true);
        try (ServiceContainers.Started s = c.startFresh(tree)) {
            assertThat(s.failed()).isFalse();
            assertThat(s.names()).containsExactly("autobuilder-my-shop-mysql");
            assertThat(calls.get(0)).containsExactly("rm", "-f", "autobuilder-my-shop-mysql");
            assertThat(calls.get(1)).containsSequence("create", "--name", "autobuilder-my-shop-mysql")
                    .containsSequence("--label", "autobuilder.project=my-shop")
                    .containsSequence("-p", port + ":3306")
                    .containsSequence("-e", "MYSQL_ALLOW_EMPTY_PASSWORD=yes")
                    .doesNotContain("-v")
                    .endsWith("mysql:8.0");
            // T4-EO: the script is copied in before the start, never mounted (a mount needs the host to share its directory)
            assertThat(calls.get(2)).containsExactly("cp", tree.resolve("basedata.sql").toAbsolutePath().toString(),
                    "autobuilder-my-shop-mysql:/docker-entrypoint-initdb.d/basedata.sql");
            assertThat(calls.get(3)).containsExactly("start", "autobuilder-my-shop-mysql");
        }
        assertThat(calls.get(calls.size() - 1)).containsExactly("rm", "-f", "-v", "autobuilder-my-shop-mysql");
    }

    @Test
    void aContainerThatCouldNotBeStartedIsNotLeftBehind() throws Exception {
        ProjectServices services = new ProjectServices(project.toString());
        services.record(new ProjectServices.Decision(true, List.of(mysql(freePort(), null)), Instant.now()));
        ServiceContainers c = new ServiceContainers(services, 1500, (args, t) -> {
            calls.add(args);
            return args.get(0).equals("start") ? new ServiceContainers.Ran(125, "Error response from daemon: mounts denied\n")
                    : new ServiceContainers.Ran(0, "");
        }, port -> true);
        ServiceContainers.Started s = c.startFresh(tree);
        assertThat(s.failed()).isTrue();
        assertThat(s.why()).contains("could not be started").contains("mounts denied");
        assertThat(calls.get(calls.size() - 1)).as("the created container is removed").containsSequence("rm", "-f", "-v")
                .anyMatch(a -> a.startsWith("autobuilder-"));
    }

    @Test
    void aServiceThatNeverAnswersIsRemovedAndSaysWhy() throws Exception {
        ProjectServices services = new ProjectServices(project.toString());
        services.record(new ProjectServices.Decision(true, List.of(mysql(freePort(), null)), Instant.now()));
        ServiceContainers.Started s = containers(services, false).startFresh(tree);
        assertThat(s.failed()).isTrue();
        assertThat(s.why()).contains("did not answer on port");
        assertThat(calls.get(calls.size() - 1)).containsSequence("rm", "-f", "-v");
    }

    @Test
    void aContainerThatStopsIsGivenUpAtOnceWithItsOwnWords() throws Exception {
        ProjectServices services = new ProjectServices(project.toString());
        ServiceProposal p = mysql(freePort(), null);
        services.record(new ProjectServices.Decision(true, List.of(p), Instant.now()));
        ServiceContainers c = new ServiceContainers(services, 600_000, (args, t) -> {
            calls.add(args);
            return switch (args.get(0)) {
                case "inspect" -> new ServiceContainers.Ran(0, "false\n");
                case "logs" -> new ServiceContainers.Ran(0, "error: database is uninitialized\n"
                        + "You need to specify one of the following as an environment variable:\n- MYSQL_ROOT_PASSWORD\n- MYSQL_ALLOW_EMPTY_PASSWORD\n");
                default -> new ServiceContainers.Ran(0, "");
            };
        }, port -> false);
        long t0 = System.currentTimeMillis();
        ServiceContainers.Started s = c.startFresh(tree);
        assertThat(System.currentTimeMillis() - t0).as("not the ten-minute timeout").isLessThan(10_000);
        assertThat(s.failed()).isTrue();
        assertThat(s.why()).contains("stopped while starting").contains("MYSQL_ALLOW_EMPTY_PASSWORD");
        assertThat(s.failedOn()).isEqualTo(p);
        assertThat(s.said()).contains("You need to specify one of the following");
        assertThat(calls.get(calls.size() - 1)).containsSequence("rm", "-f", "-v");
    }

    @Test
    void aTakenPortIsSaidAndNothingIsStarted() throws Exception {
        try (ServerSocket taken = new ServerSocket(0)) {
            ProjectServices services = new ProjectServices(project.toString());
            services.record(new ProjectServices.Decision(true, List.of(mysql(taken.getLocalPort(), null)), Instant.now()));
            ServiceContainers.Started s = containers(services, true).startFresh(tree);
            assertThat(s.failed()).isTrue();
            assertThat(s.why()).contains("already in use by something else");
            assertThat(calls).noneMatch(a -> a.get(0).equals("create") || a.get(0).equals("start"));
        }
    }

    @Test
    void aConnectionClosedAtOnceIsNotAnAnswer() throws Exception {
        // Docker's port forwarder accepts while nothing listens inside, then closes: that is "not yet".
        try (ServerSocket closing = new ServerSocket(0)) {
            Thread t = new Thread(() -> {
                try (Socket s = closing.accept()) { /* closed at once */ } catch (Exception ignored) { }
            });
            t.start();
            assertThat(ServiceContainers.answers(closing.getLocalPort())).isFalse();
            t.join(2000);
        }
        // A service that waits for its client to speak first keeps the connection open: an answer.
        try (ServerSocket waiting = new ServerSocket(0)) {
            List<Socket> held = new ArrayList<>();
            Thread t = new Thread(() -> {
                try { held.add(waiting.accept()); } catch (Exception ignored) { }
            });
            t.start();
            assertThat(ServiceContainers.answers(waiting.getLocalPort())).isTrue();
            t.join(2000);
            for (Socket s : held) s.close();
        }
        assertThat(ServiceContainers.answers(freePort())).isFalse();
    }

    @Test
    void theAnswerIsKeptInTheProjectAndCanBeWithdrawn() throws Exception {
        ProjectServices services = new ProjectServices(project.toString());
        assertThat(services.recorded()).isEmpty();
        services.record(new ProjectServices.Decision(true, List.of(mysql(3306, null)), Instant.now()));
        assertThat(Files.exists(project.resolve("meta/services.json"))).isTrue();
        assertThat(new ProjectServices(project.toString()).toStart()).singleElement()
                .satisfies(s -> assertThat(s.image()).isEqualTo("mysql:8.0"));
        services.withdraw();
        assertThat(services.recorded()).isEmpty();
    }

    @Test
    void anEditThatCannotBeStartedIsRefused() {
        assertThat(mysql(3306, null).invalid()).isNull();
        assertThat(new ServiceProposal("m", "mysql 8", 3306, 3306, Map.of(), null, List.of(), null, "model", List.of(), null).invalid())
                .contains("image");
        assertThat(new ServiceProposal("m", "mysql:8", 3306, 3306, Map.of("BAD-NAME", "x"), null, List.of(), null, "model", List.of(), null).invalid())
                .contains("environment");
        assertThat(mysql(3306, "../etc/passwd").invalid()).contains("not one the project ships");
    }
}
