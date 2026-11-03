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
 * T4-ED slice 4: "Import several" -- two or more projects brought into a new one, toward a goal.
 *
 * <p>The steps are T4-EC's, the ones {@code scripts/bring_inputs.py} takes from the shell: the new, empty project
 * is started held ({@code ASK}); it is given its goal; each project is brought in as an input with its intent (one
 * call each, which can take minutes: the run compares what the input brings with what is there); conflicts no intent
 * settles are left for the operator to decide on the page; then the run is released in {@code ADOPT_PROJECT}. Every
 * step is a call to the run's own endpoint, so nothing about inputs is decided here.
 *
 * <p>A job, not page calls in sequence: the steps take longer than a page can be relied on to stay open.
 */
@Slf4j
class IntegrationJob {

    /** Where the job stands. */
    enum Phase { STARTING, SETTING_GOAL, BRINGING_IN, DECIDING, RELEASED, FAILED }

    /** One project to bring in: its name in the projects directory, what to take from it, and whether its module runs. */
    record Input(String project, String intent, boolean runs) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration INPUT_TIMEOUT = Duration.ofMinutes(30);

    private final String name;
    private final Path projectDir;
    private final Path projectsDir;
    private final String goal;
    private final String profiles;
    private final List<Input> inputs;
    private final ProjectLauncher launcher;
    private final RunClient run;

    private Phase phase = Phase.STARTING;
    private String failure;
    private final Instant began = Instant.now();
    private final Map<String, Map<String, Object>> landed = new LinkedHashMap<>();

    IntegrationJob(String name, Path projectsDir, String goal, String profiles, List<Input> inputs,
                   ProjectLauncher launcher, RunClient run) {
        this.name = name;
        this.projectsDir = projectsDir;
        this.projectDir = projectsDir.resolve(name).toAbsolutePath().normalize();
        this.goal = goal;
        this.profiles = profiles;
        this.inputs = List.copyOf(inputs);
        this.launcher = launcher;
        this.run = run;
        for (Input i : inputs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("project", i.project());
            row.put("status", "waiting");
            landed.put(i.project(), row);
        }
    }

    /**
     * Checks the request before anything is created: a free name, a goal, two or more distinct projects that hold
     * code, an intent for each, at most one that runs. Returns what is wrong, or null.
     */
    static String problem(Path projectsDir, String name, String goal, List<Input> inputs) {
        if (Files.exists(projectsDir.resolve(name))) return "a project named " + name + " already exists";
        if (goal == null || goal.isBlank()) return "the new project needs a goal";
        if (inputs == null || inputs.size() < 2) return "choose two or more projects to bring together";
        if (inputs.stream().map(Input::project).distinct().count() < inputs.size()) return "a project is chosen twice";
        if (inputs.stream().filter(Input::runs).count() > 1) return "at most one project's module is the one that runs";
        for (Input i : inputs) {
            if (i.project() == null || i.project().contains("/") || i.project().contains("..")) return "a project is a name in " + projectsDir;
            if (!holdsCode(projectsDir.resolve(i.project()))) return i.project() + " holds no code to bring in";
            if (i.intent() == null || i.intent().isBlank()) return "say what to take from " + i.project();
        }
        return null;
    }

    private static boolean holdsCode(Path project) {
        Path output = project.resolve("output");
        if (!Files.isDirectory(output)) return false;
        try (Stream<Path> files = Files.walk(output)) {
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
            Files.createDirectories(projectDir);
            launcher.start(new ProjectChoice(projectDir.toString(), "ASK", profiles)).join();
            phase(Phase.SETTING_GOAL);
            expect(run.postText("/autobuilder/goal", goal), "the goal was refused");
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
        Map<String, Object> row = landed.get(i.project());
        synchronized (this) {
            row.put("status", "bringing in");
        }
        Path dir = projectsDir.resolve(i.project()).toAbsolutePath().normalize();
        ObjectNode body = MAPPER.createObjectNode();
        body.put("label", i.project());
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
            throw new IllegalStateException(i.project() + " was not brought in: " + messageOf(reply.body()));
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
        log.info("T4-ED: brought {} into {}: {} placed, {} corrections, {} conflicts", i.project(), name,
                r.path("placed").size(), r.path("corrections").size(), r.path("conflicts").size());
    }

    /** The project's lineage as its provenance on the input, or "unrecorded" when it never ran here. */
    private static String lineageOf(Path dir) {
        try {
            return MAPPER.readTree(dir.resolve("meta/run-identity.json").toFile()).path("lineageId").asText("unrecorded");
        } catch (IOException e) {
            return "unrecorded";
        }
    }

    /**
     * Starts the loop on the project the inputs made, once no conflict is open. Called by the job when nothing
     * waits for a decision, and by the operator after deciding.
     *
     * @throws IllegalStateException while the job is not waiting to be released, or a conflict is still open
     */
    synchronized void release() throws IOException {
        if (phase != Phase.BRINGING_IN && phase != Phase.DECIDING) {
            throw new IllegalStateException("nothing to release: the job is " + phase.name().toLowerCase());
        }
        JsonNode open = MAPPER.readTree(expect(run.get("/autobuilder/inputs/conflicts"), "the conflicts could not be read"));
        if (open.isArray() && !open.isEmpty()) {
            throw new IllegalStateException(open.size() + " conflict(s) still wait for a decision");
        }
        expect(run.postJson("/autobuilder/start?mode=ADOPT_PROJECT", "", Duration.ofMinutes(2)), "the run was not released");
        phase = Phase.RELEASED;
        log.info("T4-ED: {} released -- the loop works on what the inputs brought", name);
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

    /** What the page shows: the new project, the phase, each input's outcome, and why it failed. */
    synchronized Map<String, Object> describe() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("phase", phase.name());
        body.put("began", began.toString());
        List<Map<String, Object>> rows = new ArrayList<>();
        landed.values().forEach(r -> rows.add(new LinkedHashMap<>(r)));
        body.put("inputs", rows);
        if (failure != null) body.put("failure", failure);
        return body;
    }
}
