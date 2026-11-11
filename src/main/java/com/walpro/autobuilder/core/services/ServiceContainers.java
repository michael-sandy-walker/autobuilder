package com.walpro.autobuilder.core.services;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * T4-EN: the services a project needs, started as containers around ONE program start -- created fresh before it and
 * removed after it (decided: variants must not see each other's data; a claim must not hold on a gutted tree because an
 * earlier start left rows). Only what the operator said yes to ({@link ProjectServices}) is ever started.
 *
 * <p>Containers are named {@code autobuilder-<project>-<service>} and labelled with the project, so whatever is left
 * (a killed JVM) is removed by name before the next start and by label when this context closes.
 */
@Slf4j
@Component
public class ServiceContainers {

    static final String LABEL = "autobuilder.project";

    /** How a docker command ended: its exit status (null when it did not end in time or could not run) and output. */
    public record Ran(Integer exit, String output) {
        boolean ok() { return exit != null && exit == 0; }
    }

    /** Runs the docker CLI; replaced in tests. */
    public interface Docker {
        Ran run(List<String> args, long timeoutMs);
    }

    /** Whether a port answers as a service does; replaced in tests. */
    public interface Readiness {
        boolean ready(int port);
    }

    /** The services of one program start; closing removes them. {@code why} says why they could not be started. */
    public static final class Started implements AutoCloseable {
        static final Started NONE = new Started(null, List.of(), null);

        public static Started none() { return NONE; }
        private final ServiceContainers owner;
        private final List<String> names;
        private final String why;
        private final ServiceProposal failedOn;
        private final String said;

        Started(ServiceContainers owner, List<String> names, String why) {
            this(owner, names, why, null, null);
        }

        Started(ServiceContainers owner, List<String> names, String why, ServiceProposal failedOn, String said) {
            this.owner = owner;
            this.names = names;
            this.why = why;
            this.failedOn = failedOn;
            this.said = said;
        }

        public boolean failed() { return why != null; }
        public String why() { return why; }
        /** The service that could not be started, when one could not. */
        public ServiceProposal failedOn() { return failedOn; }
        /** What its container printed before it stopped or while it did not answer, in its own words; may be null. */
        public String said() { return said; }
        public List<String> names() { return names; }

        @Override
        public void close() {
            if (owner != null) owner.remove(names);
        }
    }

    private final ProjectServices services;
    private final long readyTimeoutMs;
    private Docker docker = ServiceContainers::cli;
    private Readiness readiness = ServiceContainers::answers;

    @Autowired
    public ServiceContainers(ProjectServices services,
                             // How long a started service has to answer on its port (the first start also pulls the image).
                             @Value("${autobuilder.services.ready-timeout-ms:300000}") long readyTimeoutMs) {
        this.services = services;
        this.readyTimeoutMs = readyTimeoutMs;
    }

    ServiceContainers(ProjectServices services, long readyTimeoutMs, Docker docker, Readiness readiness) {
        this(services, readyTimeoutMs);
        this.docker = docker;
        this.readiness = readiness;
    }

    public String containerName(ServiceProposal s) {
        return "autobuilder-" + services.project() + "-" + s.name().replaceAll("[^A-Za-z0-9_.-]", "-");
    }

    /**
     * Before one program start of {@code tree}: every service the operator said yes to, created fresh and answering on
     * its port. Nothing recorded, nothing started ({@link Started#NONE}). On a failure, what was started is removed
     * and {@code why} says what failed.
     */
    public Started startFresh(Path tree) {
        List<ServiceProposal> wanted = services.toStart();
        if (wanted.isEmpty()) return Started.NONE;
        List<String> names = new ArrayList<>();
        for (ServiceProposal s : wanted) {
            String name = containerName(s);
            docker.run(List.of("rm", "-f", name), 30_000);   // a leftover of this project's own, never anything else
            if (!portFree(s.hostPort())) {
                remove(names);
                return new Started(null, List.of(), "port " + s.hostPort() + ", where the project reaches " + s.name()
                        + ", is already in use by something else");
            }
            // Created, the setup script copied in, then started -- never mounted: a mount needs the script's directory
            // shared with the Docker host, and where Docker runs in its own machine a working copy under the temporary
            // directory is not (T4-EO, field: "mounts denied ... is not shared from the host").
            List<String> args = new ArrayList<>(List.of("create", "--name", name, "--label", LABEL + "=" + services.project(),
                    "-p", s.hostPort() + ":" + s.containerPort()));
            s.env().forEach((k, v) -> args.addAll(List.of("-e", k + "=" + v)));
            Path script = null;
            if (s.initScript() != null && s.initDir() != null) {
                script = tree.resolve(s.initScript()).toAbsolutePath().normalize();
                if (!script.startsWith(tree.toAbsolutePath().normalize()) || !Files.isRegularFile(script)) {
                    remove(names);
                    return new Started(null, List.of(), "the setup script " + s.initScript() + " is not in the project");
                }
            }
            args.add(s.image());
            Ran ran = docker.run(args, readyTimeoutMs);
            if (ran.ok() && script != null) {
                ran = docker.run(List.of("cp", script.toString(), name + ":" + s.initDir().replaceAll("/+$", "") + "/"
                        + script.getFileName()), 60_000);
            }
            if (ran.ok()) ran = docker.run(List.of("start", name), 60_000);
            if (!ran.ok()) {
                names.add(name);   // created or not, nothing of it is left
                remove(names);
                String said = ran.output() == null ? "docker did not answer" : lastLines(ran.output());
                return new Started(null, List.of(), s.name() + " (" + s.image() + ") could not be started: " + said, s, said);
            }
            names.add(name);
            Boolean ready = awaitReady(name, s.hostPort());
            if (!Boolean.TRUE.equals(ready)) {
                Ran logs = docker.run(List.of("logs", "--tail", "12", name), 30_000);
                String said = logs.output() == null ? null : lastLines(logs.output());
                remove(names);
                String what = ready == null ? "stopped while starting" : "did not answer on port " + s.hostPort() + " within "
                        + readyTimeoutMs / 1000 + " s";
                return new Started(null, List.of(), s.name() + " (" + s.image() + ") " + what
                        + (said == null ? "" : "; it said: " + said), s, said);
            }
            log.info("T4-EN: {} started ({} at port {})", name, s.image(), s.hostPort());
        }
        return new Started(this, List.copyOf(names), null);
    }

    void remove(List<String> names) {
        for (String n : names) docker.run(List.of("rm", "-f", "-v", n), 60_000);
    }

    /** When this context closes: nothing of this project's is left running. */
    @PreDestroy
    public void removeAll() {
        if (services.toStart().isEmpty()) return;
        Ran ids = docker.run(List.of("ps", "-aq", "--filter", "label=" + LABEL + "=" + services.project()), 30_000);
        if (!ids.ok() || ids.output() == null) return;
        List<String> left = ids.output().lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        remove(left);
    }

    /** True when it answers, false when the time ran out, null when its container stopped first. */
    private Boolean awaitReady(String name, int port) {
        long deadline = System.currentTimeMillis() + readyTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (readiness.ready(port)) return true;
            Ran running = docker.run(List.of("inspect", "-f", "{{.State.Running}}", name), 30_000);
            if (running.ok() && running.output() != null && running.output().strip().equals("false")) return null;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return readiness.ready(port);
    }

    /**
     * A port answers as a service when a connection stays open: Docker's port forwarder accepts a connection even while
     * nothing listens inside the container, and then closes it at once -- that end of stream is "not yet". A service
     * that greets (sends first) or waits for its client both count.
     */
    static boolean answers(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            s.setSoTimeout(1500);
            return s.getInputStream().read() != -1;
        } catch (SocketTimeoutException e) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static boolean portFree(int port) {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Its last lines, joined -- a message that names what is missing is often spread over several. */
    private static String lastLines(String output) {
        List<String> lines = output.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        if (lines.isEmpty()) return "it printed nothing";
        String l = String.join(" | ", lines.subList(Math.max(0, lines.size() - 8), lines.size()));
        return l.length() <= 700 ? l : "..." + l.substring(l.length() - 700);
    }

    static Ran cli(List<String> args, long timeoutMs) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(args);
        Process p = null;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.getOutputStream().close();
            InputStream in = p.getInputStream();
            StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                try {
                    out.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // destroyed
                }
            });
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return new Ran(null, "docker " + args.get(0) + " did not end within " + timeoutMs / 1000 + " s");
            }
            reader.join(2000);
            return new Ran(p.exitValue(), out.toString());
        } catch (IOException e) {
            return new Ran(null, "docker could not be run: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (p != null) p.destroyForcibly();
            return new Ran(null, "interrupted");
        }
    }
}
