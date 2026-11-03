package com.walpro.autobuilder.home;

import com.walpro.autobuilder.AutobuilderApplication;
import com.walpro.autobuilder.core.init.StartMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * T4-ED: starts the run context on a chosen project -- the one path from "no project" to a running loop.
 *
 * <p><b>Why a second context and not beans that attach late.</b> Fourteen beans take the project path at
 * construction, five startup hooks read the project, the log location is fixed when logging starts, and
 * about half the components keep state in fields. A run context booted with the project bound is exactly
 * how a JVM has always been booted on a target, so nothing on the run side changes.
 *
 * <p>One run at a time: graph reads are not yet scoped to a run, so two live runs would read each other's
 * nodes. A second start while one is starting or running is refused, not queued.
 */
@Slf4j
public class ProjectLauncher {

    /** Where a run stands, as the home screen shows it. */
    public enum State { IDLE, STARTING, RUNNING, FAILED }

    /** System property {@code logback-spring.xml} and the startup log rotator write under. */
    static final String LOG_ROOT = "autobuilder.log.root";

    private final String[] startupArgs;
    private final BiFunction<ProjectChoice, String[], ConfigurableApplicationContext> runner;

    private State state = State.IDLE;
    private ProjectChoice current;
    private Instant since;
    private String failure;
    private ConfigurableApplicationContext runContext;

    public ProjectLauncher(String[] startupArgs) {
        this(startupArgs, ProjectLauncher::bootRunContext);
    }

    /** Visible for testing: {@code runner} boots the run context on a choice from its arguments. */
    ProjectLauncher(String[] startupArgs, BiFunction<ProjectChoice, String[], ConfigurableApplicationContext> runner) {
        this.startupArgs = startupArgs.clone();
        this.runner = runner;
    }

    /**
     * Starts the run context on {@code choice} in the background. The future completes when the context is
     * up, or exceptionally when it failed to start; the state says the same to the page.
     *
     * @throws IllegalStateException when a run is already starting or running
     * @throws IllegalArgumentException when the choice names an unknown mode
     */
    public synchronized CompletableFuture<ConfigurableApplicationContext> start(ProjectChoice choice) {
        if (state == State.STARTING || state == State.RUNNING) {
            throw new IllegalStateException("a run is already " + state.name().toLowerCase(Locale.ROOT)
                    + " on " + current.project() + "; one run at a time");
        }
        if (choice.mode() != null) StartMode.valueOf(choice.mode().trim().toUpperCase(Locale.ROOT));
        String[] args = runArgs(choice);
        current = choice;
        since = Instant.now();
        failure = null;
        state = State.STARTING;
        log.info("T4-ED: starting a run on {} (mode {}, profiles {})", choice.project(),
                choice.mode() == null ? "from the startup arguments" : choice.mode(),
                choice.profiles() == null ? "from the startup arguments" : choice.profiles());

        CompletableFuture<ConfigurableApplicationContext> started = new CompletableFuture<>();
        Thread t = new Thread(() -> boot(args, started), "run-context");
        t.setContextClassLoader(ProjectLauncher.class.getClassLoader());
        t.start();
        return started;
    }

    private void boot(String[] args, CompletableFuture<ConfigurableApplicationContext> started) {
        try {
            ConfigurableApplicationContext ctx = runner.apply(current, args);
            synchronized (this) {
                runContext = ctx;
                state = State.RUNNING;
                since = Instant.now();
            }
            started.complete(ctx);
        } catch (Throwable e) {
            synchronized (this) {
                state = State.FAILED;
                failure = e.getClass().getSimpleName() + ": " + e.getMessage();
                since = Instant.now();
            }
            log.error("T4-ED: the run on {} failed to start: {}", current.project(), e.toString());
            started.completeExceptionally(e);
        }
    }

    private static ConfigurableApplicationContext bootRunContext(ProjectChoice choice, String[] args) {
        // The log root moves to the project before the context starts, and logging is released so the
        // run context configures it again: one logging system per JVM, initialised once otherwise.
        System.setProperty(LOG_ROOT, choice.project());
        LoggingSystem.get(ProjectLauncher.class.getClassLoader()).cleanUp();
        // main() named, since it cannot be found from a worker thread's stack: without it the "Started
        // AutobuilderApplication" line is logged under a framework logger the log level filters out, and
        // scripts waiting for the run to serve (post_adopted_goal.sh) wait for it in the project's log.
        return new SpringApplicationBuilder(AutobuilderApplication.class).main(AutobuilderApplication.class).run(args);
    }

    /**
     * The run context's arguments: the startup arguments, with the project, mode and profiles the choice sets
     * replacing any the startup arguments carried (a repeated option would reach Spring as a list).
     */
    String[] runArgs(ProjectChoice choice) {
        Map<String, String> set = new LinkedHashMap<>();
        set.put(ProjectChoice.BASE_PATH, choice.project());
        if (choice.mode() != null) set.put("autobuilder.start.mode", choice.mode().trim().toUpperCase(Locale.ROOT));
        if (choice.profiles() != null) set.put("spring.profiles.active", choice.profiles());
        List<String> out = new ArrayList<>();
        for (String a : startupArgs) {
            if (set.keySet().stream().noneMatch(k -> a.startsWith("--" + k + "="))) out.add(a);
        }
        set.forEach((k, v) -> out.add("--" + k + "=" + v));
        return out.toArray(String[]::new);
    }

    /** What the home screen shows: the state, the project, since when, and why a start failed. */
    public synchronized Map<String, Object> describe() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", state.name());
        if (current != null) {
            body.put("project", current.project());
            body.put("projectExists", Files.isDirectory(Path.of(current.project())));
            if (current.mode() != null) body.put("mode", current.mode());
            if (current.profiles() != null) body.put("profiles", current.profiles());
        }
        if (since != null) body.put("since", since.toString());
        if (failure != null) body.put("failure", failure);
        if (runContext != null && state == State.RUNNING) {
            String port = runContext.getEnvironment().getProperty("local.server.port");
            if (port != null) body.put("runPort", Integer.parseInt(port));
        }
        return body;
    }

    public synchronized State state() {
        return state;
    }
}
