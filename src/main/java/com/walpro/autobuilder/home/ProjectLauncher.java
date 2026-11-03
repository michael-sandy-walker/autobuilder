package com.walpro.autobuilder.home;

import com.walpro.autobuilder.AutobuilderApplication;
import com.walpro.autobuilder.core.init.StartMode;
import com.walpro.autobuilder.core.service.AutobuilderService;
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
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

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

    /**
     * Where a run stands, as the home screen shows it. ENDED: the loop has stopped (a terminal, or a person's
     * pause) and the run context stays up so its results can be read, until the operator returns home.
     */
    public enum State { IDLE, STARTING, RUNNING, ENDED, FAILED }

    /** System property {@code logback-spring.xml} and the startup log rotator write under. */
    static final String LOG_ROOT = "autobuilder.log.root";

    private final String[] startupArgs;
    private final BiFunction<ProjectChoice, String[], ConfigurableApplicationContext> runner;

    private State state = State.IDLE;
    private ProjectChoice current;
    private Instant since;
    private String failure;
    private ConfigurableApplicationContext runContext;
    private Runnable homeLogging = () -> {};
    private Instant stopRequested;
    /** Why the run's loop has stopped, or empty while it runs. Replaced in tests. */
    Function<ConfigurableApplicationContext, Optional<String>> haltProbe = ProjectLauncher::loopHalt;
    /** Whether the run is an adopted project waiting for its goal. Replaced in tests. */
    Predicate<ConfigurableApplicationContext> goalWaitProbe = ProjectLauncher::awaitingGoal;
    /** Whether the run's loop has not begun: held for a start mode, or adopted and waiting for a goal. Replaced in tests. */
    Predicate<ConfigurableApplicationContext> notBegunProbe = ProjectLauncher::notBegun;

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
            throw new IllegalStateException("a run is already " + state().name().toLowerCase(Locale.ROOT)
                    + " on " + current.project() + "; one run at a time"
                    + (state() == State.ENDED ? " -- return to the home screen first" : ""));
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

    /** What re-points logging to the home log once a run context has closed; set by the home context. */
    public void onReturnHome(Runnable homeLogging) {
        this.homeLogging = homeLogging;
    }

    /**
     * Back to the home screen: closes a run that has ENDED or whose loop has not begun (held, or adopted and
     * waiting for a goal), or clears a start that FAILED, so the next project can be started. Never automatic
     * (user, 2026-11-03): a finished run stays readable until someone chooses this. A run whose loop is going is
     * not closed from here.
     *
     * @throws IllegalStateException while a run is starting or its loop is running
     */
    public synchronized void returnHome() {
        State now = state();
        if (now == State.RUNNING && notBegunProbe.test(runContext)) now = State.ENDED;
        if (now == State.STARTING || now == State.RUNNING) {
            throw new IllegalStateException("the run on " + current.project() + " is still "
                    + now.name().toLowerCase(Locale.ROOT) + "; it can be left once it has ended");
        }
        if (now == State.ENDED) {
            log.info("T4-ED: closing the ended run on {} and returning to the home screen", current.project());
            runContext.close();
            homeLogging.run();
        }
        runContext = null;
        current = null;
        failure = null;
        since = null;
        stopRequested = null;
        state = State.IDLE;
    }

    /**
     * Records that the operator asked the running loop to stop. The stop itself is the run's own pause, which
     * waits for the current cycle; until the loop has halted, the home screen says it is stopping.
     *
     * @throws IllegalStateException unless a loop is running (a run that has not begun is left with returnHome)
     */
    public synchronized void stopRequested() {
        State now = state();
        if (now != State.RUNNING || notBegunProbe.test(runContext)) {
            throw new IllegalStateException("no loop is running" + (now == State.RUNNING
                    ? "; it has not begun -- leave it with Back to home" : ""));
        }
        if (stopRequested == null) stopRequested = Instant.now();
    }

    private static boolean notBegun(ConfigurableApplicationContext ctx) {
        if (!ctx.isActive()) return false;
        AutobuilderService service = ctx.getBeanProvider(AutobuilderService.class).getIfAvailable();
        return service != null && (service.held() || service.awaitingGoal());
    }

    private static boolean awaitingGoal(ConfigurableApplicationContext ctx) {
        if (!ctx.isActive()) return false;
        AutobuilderService service = ctx.getBeanProvider(AutobuilderService.class).getIfAvailable();
        return service != null && service.awaitingGoal();
    }

    private static Optional<String> loopHalt(ConfigurableApplicationContext ctx) {
        if (!ctx.isActive()) return Optional.empty();
        AutobuilderService service = ctx.getBeanProvider(AutobuilderService.class).getIfAvailable();
        return service == null ? Optional.empty() : service.haltReason();
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
        State now = state();
        body.put("state", now.name());
        if (now == State.ENDED) body.put("endReason", haltProbe.apply(runContext).orElse(""));
        if (now == State.RUNNING && goalWaitProbe.test(runContext)) body.put("waitingForGoal", true);
        if (now == State.RUNNING && notBegunProbe.test(runContext)) body.put("notBegun", true);
        if (now == State.RUNNING && stopRequested != null) body.put("stopping", stopRequested.toString());
        if (current != null) {
            body.put("project", current.project());
            body.put("projectExists", Files.isDirectory(Path.of(current.project())));
            if (current.mode() != null) body.put("mode", current.mode());
            if (current.profiles() != null) body.put("profiles", current.profiles());
        }
        if (since != null) body.put("since", since.toString());
        if (failure != null) body.put("failure", failure);
        if (runContext != null && state == State.RUNNING && runContext.isActive()) {
            String port = runContext.getEnvironment().getProperty("local.server.port");
            if (port != null) body.put("runPort", Integer.parseInt(port));
        }
        return body;
    }

    public synchronized State state() {
        if (state == State.RUNNING && runContext != null && haltProbe.apply(runContext).isPresent()) return State.ENDED;
        return state;
    }
}
