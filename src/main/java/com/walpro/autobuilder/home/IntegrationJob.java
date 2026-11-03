package com.walpro.autobuilder.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * T4-ED: projects brought into a project -- T4-EC's input operation, driven from the home screen.
 *
 * <p>Two shapes, one sequence. <b>Together</b>: a new, empty project receives two or more projects toward a goal it
 * is given first. <b>Extend</b>: an existing project receives one more. The steps are the ones
 * {@code scripts/bring_inputs.py} takes from the shell: unless the receiving project's loop is already going, it is
 * started held ({@code ASK}); the goal is set (Together only); each input is posted with its intent (one call each,
 * which can take minutes: the run compares what the input brings with what is there); conflicts no intent settles
 * wait for the operator; then a held run is released in {@code ADOPT_PROJECT}. A project whose loop is going takes
 * its inputs between cycles and needs no release. Every step is a call to the run's own endpoint, so nothing about
 * inputs is decided here.
 *
 * <p>A job, not page calls in sequence: the steps take longer than a page can be relied on to stay open.
 */
@Slf4j
class IntegrationJob {

    /** Where the job stands. RELEASED: the inputs are in and the loop works on them. */
    enum Phase { STARTING, SETTING_GOAL, BRINGING_IN, DECIDING, RELEASED, FAILED }

    /**
     * One input: a project in the projects directory, or a directory on this machine (exactly one of the two),
     * what to take from it, and whether its module is the one that runs.
     */
    record Input(String project, String path, String intent, boolean runs) {
        Input(String project, String intent, boolean runs) {
            this(project, null, intent, runs);
        }

        String label() {
            return project != null ? project : Path.of(path).getFileName().toString();
        }

        Path dir(Path projectsDir) {
            return (project != null ? projectsDir.resolve(project) : Path.of(path)).toAbsolutePath().normalize();
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration INPUT_TIMEOUT = Duration.ofMinutes(30);

    private final String name;
    private final Path projectDir;
    private final Path projectsDir;
    private final boolean create;
    private final String goal;
    private final String profiles;
    private final List<Input> inputs;
    private final ProjectLauncher launcher;
    private final RunClient run;

    private Phase phase = Phase.STARTING;
    private boolean startedHeld;
    private String failure;
    private final Instant began = Instant.now();
    private final Map<String, Map<String, Object>> landed = new LinkedHashMap<>();

    /**
     * @param create true for a new project (Together), which then needs {@code goal}; false to extend an existing
     *               one, whose goal stays as it is ({@code goal} null)
     */
    IntegrationJob(String name, Path projectsDir, boolean create, String goal, String profiles, List<Input> inputs,
                   ProjectLauncher launcher, RunClient run) {
        this.name = name;
        this.projectsDir = projectsDir;
        this.projectDir = projectsDir.resolve(name).toAbsolutePath().normalize();
        this.create = create;
        this.goal = goal;
        this.profiles = profiles;
        this.inputs = List.copyOf(inputs);
        this.launcher = launcher;
        this.run = run;
        for (Input i : inputs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("project", i.label());
            row.put("status", "waiting");
            landed.put(i.label(), row);
        }
    }

    /**
     * Checks a Together request before anything is created: a free name, a goal, two or more distinct inputs that
     * hold code, an intent for each, at most one that runs. Returns what is wrong, or null.
     */
    static String problem(Path projectsDir, String name, String goal, List<Input> inputs) {
        if (Files.exists(projectsDir.resolve(name))) return "a project named " + name + " already exists";
        if (goal == null || goal.isBlank()) return "the new project needs a goal";
        if (inputs == null || inputs.size() < 2) return "choose two or more projects to bring together";
        return inputProblem(projectsDir, projectsDir.resolve(name), inputs);
    }

    /** Checks an Extend request: an existing project, and one input that is not the project itself. */
    static String extendProblem(Path projectsDir, String name, Input input) {
        if (!Files.isDirectory(projectsDir.resolve(name))) return "no project named " + name;
        if (input == null) return "choose what to bring in";
        return inputProblem(projectsDir, projectsDir.resolve(name), List.of(input));
    }

    private static String inputProblem(Path projectsDir, Path receiving, List<Input> inputs) {
        if (inputs.stream().map(i -> i.dir(projectsDir)).distinct().count() < inputs.size()) return "a project is chosen twice";
        if (inputs.stream().filter(Input::runs).count() > 1) return "at most one project's module is the one that runs";
        for (Input i : inputs) {
            if ((i.project() == null) == (i.path() == null || i.path().isBlank())) {
                return "an input is a project here or a path, one of the two";
            }
            if (i.project() != null && (i.project().contains("/") || i.project().contains(".."))) {
                return "a project is a name in " + projectsDir;
            }
            if (i.path() != null && !Path.of(i.path()).isAbsolute()) return "a path is an absolute path on this machine";
            if (i.dir(projectsDir).equals(receiving.toAbsolutePath().normalize())) return "a project cannot be brought into itself";
            if (!holdsCode(i.dir(projectsDir))) return i.label() + " holds no code to bring in";
            if (i.intent() == null || i.intent().isBlank()) return "say what to take from " + i.label();
        }
        return null;
    }

    /** Code in its {@code output/} (a project here), or in the directory itself (a codebase elsewhere). */
    private static boolean holdsCode(Path dir) {
        Path code = Files.isDirectory(dir.resolve("output")) ? dir.resolve("output") : dir;
        if (!Files.isDirectory(code)) return false;
        try (Stream<Path> files = Files.walk(code)) {
            return files.anyMatch(Files::isRegularFile);
        } catch (IOException e) {
            return false;
        }
    }

    /** Runs the steps on a thread of their own; the page follows {@link #describe()}. */
    void begin() {
        Thread t = new Thread(this::steps, "integration-" + name);
        t.setDaemon(true);
        t.start();
    }

    private void steps() {
        try {
            if (create) Files.createDirectories(projectDir);
            boolean live = launcher.isOn(projectDir) && launcher.loopGoing();
            if (!live) {
                // Extending a project whose finished run is still open on the page: the operator asked for this.
                if (launcher.isOn(projectDir)) launcher.returnHome();
                launcher.start(new ProjectChoice(projectDir.toString(), "ASK", profiles)).join();
                synchronized (this) {
                    startedHeld = true;
                }
            }
            if (goal != null) {
                phase(Phase.SETTING_GOAL);
                expect(run.postText("/autobuilder/goal", goal), "the goal was refused");
            }
            phase(Phase.BRINGING_IN);
            for (Input i : inputs) bringIn(i);
            JsonNode open = MAPPER.readTree(expect(run.get("/autobuilder/inputs/conflicts"), "the conflicts could not be read"));
            if (open.isArray() && !open.isEmpty()) phase(Phase.DECIDING);
            else release();
        } catch (Exception e) {
            fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private void bringIn(Input i) throws IOException {
        Map<String, Object> row = landed.get(i.label());
        synchronized (this) {
            row.put("status", "bringing in");
        }
        Path dir = i.dir(projectsDir);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("label", i.label());
        body.put("runId", lineageOf(dir));
        body.put("dir", dir.toString());
        body.put("intent", i.intent());
        body.put("addedBy", "operator (home screen)");
        body.put("runs", i.runs());
        RunClient.Reply reply = run.postJson("/autobuilder/inputs", MAPPER.writeValueAsString(body), INPUT_TIMEOUT);
        if (!reply.ok()) {
            synchronized (this) {
                row.put("status", "refused");
                row.put("message", messageOf(reply.body()));
            }
            throw new IllegalStateException(i.label() + " was not brought in: " + messageOf(reply.body()));
        }
        JsonNode r = MAPPER.readTree(reply.body());
        synchronized (this) {
            row.put("status", "landed");
            row.put("placed", r.path("placed").size());
            row.put("corrections", r.path("corrections").size());
            List<String> conflicts = new ArrayList<>();
            r.path("conflicts").forEach(c -> conflicts.add(c.path("subject").asText()));
            row.put("conflicts", conflicts);
            row.put("unmet", r.path("unmet").size());
        }
        log.info("T4-ED: brought {} into {}: {} placed, {} corrections, {} conflicts", i.label(), name,
                r.path("placed").size(), r.path("corrections").size(), r.path("conflicts").size());
    }

    /** The input's lineage as its provenance, or "unrecorded" when it never ran here. */
    private static String lineageOf(Path dir) {
        try {
            return MAPPER.readTree(dir.resolve("meta/run-identity.json").toFile()).path("lineageId").asText("unrecorded");
        } catch (IOException e) {
            return "unrecorded";
        }
    }

    /**
     * Lets the loop work on what came in, once no conflict is open: a run this job started held is released in
     * ADOPT_PROJECT; a loop that was already going simply carries on. Called by the job when nothing waits for a
     * decision, and by the operator after deciding.
     *
     * @throws IllegalStateException while the job is not waiting for this, or a conflict is still open
     */
    synchronized void release() throws IOException {
        if (phase != Phase.BRINGING_IN && phase != Phase.DECIDING) {
            throw new IllegalStateException("nothing to release: the job is " + phase.name().toLowerCase());
        }
        JsonNode open = MAPPER.readTree(expect(run.get("/autobuilder/inputs/conflicts"), "the conflicts could not be read"));
        if (open.isArray() && !open.isEmpty()) {
            throw new IllegalStateException(open.size() + " conflict(s) still wait for a decision");
        }
        if (startedHeld) {
            expect(run.postJson("/autobuilder/start?mode=ADOPT_PROJECT", "", Duration.ofMinutes(2)), "the run was not released");
        }
        phase = Phase.RELEASED;
        log.info("T4-ED: {} -- the loop works on what came in", name);
    }

    private String expect(RunClient.Reply reply, String what) {
        if (!reply.ok()) throw new IllegalStateException(what + ": " + messageOf(reply.body()));
        return reply.body();
    }

    private static String messageOf(String body) {
        try {
            JsonNode n = MAPPER.readTree(body);
            for (String k : List.of("message", "error")) if (n.hasNonNull(k)) return n.get(k).asText();
        } catch (IOException ignored) {
            // not JSON: the body itself says it
        }
        return body;
    }

    private synchronized void phase(Phase p) {
        phase = p;
    }

    private synchronized void fail(String why) {
        phase = Phase.FAILED;
        failure = why;
        log.warn("T4-ED: bringing projects into {} failed: {}", name, why);
    }

    synchronized Phase phase() {
        return phase;
    }

    /** What the page shows: the receiving project, whether it is new, the phase, each input's outcome, why it failed. */
    synchronized Map<String, Object> describe() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("kind", create ? "together" : "extend");
        // Whether the release starts the loop: a project this job started held, not one whose loop was going.
        body.put("held", startedHeld);
        body.put("phase", phase.name());
        body.put("began", began.toString());
        List<Map<String, Object>> rows = new ArrayList<>();
        landed.values().forEach(r -> rows.add(new LinkedHashMap<>(r)));
        body.put("inputs", rows);
        if (failure != null) body.put("failure", failure);
        return body;
    }
}
