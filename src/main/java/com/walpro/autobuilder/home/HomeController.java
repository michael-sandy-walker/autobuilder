package com.walpro.autobuilder.home;

import com.walpro.autobuilder.core.init.TargetCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * T4-ED: the home screen's endpoints -- what can be started, what is running, a new project, and "Start".
 *
 * <p>Every action the page takes is one of these, so a script can take it with curl.
 */
@Slf4j
@Profile("home")
@RestController
public class HomeController {

    /** A project name is a directory name: lower case, digits, dot, dash, underscore; no path. */
    private static final Pattern PROJECT_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private final ProjectLauncher launcher;
    private final HomeApplication.ProjectsDirectory projects;
    private final TargetCatalog catalog = new TargetCatalog();
    private final RunClient run;
    private volatile IntegrationJob job;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    @Autowired // two constructors: without this Spring has no way to choose and the home context does not start
    public HomeController(ProjectLauncher launcher, HomeApplication.ProjectsDirectory projects) {
        this(launcher, projects, new RunClient(launcher));
    }

    /** Visible for testing: {@code run} stands in for the run's endpoints. */
    HomeController(ProjectLauncher launcher, HomeApplication.ProjectsDirectory projects, RunClient run) {
        this.launcher = launcher;
        this.projects = projects;
        this.run = run;
    }

    /** The home screen is the root of the home context. */
    @GetMapping("/")
    public ResponseEntity<Void> root() {
        return ResponseEntity.status(302).location(URI.create("/home.html")).build();
    }

    /** When this home context started: a page loaded from an earlier one reloads itself, or it runs old code. */
    private final String started = java.time.Instant.now().toString();

    /** Whether a run is idle, starting, running or failed to start, and on what; and when this home context started. */
    @GetMapping("/home/state")
    public Map<String, Object> state() {
        Map<String, Object> body = new LinkedHashMap<>(launcher.describe());
        body.put("homeSince", started);
        return body;
    }

    /** The projects in the projects directory, with what each carries and the modes it can start in. */
    @GetMapping("/home/projects")
    public Map<String, Object> projects() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("directory", projects.path().toString());
        List<TargetCatalog.Target> found = catalog.targetsUnder(projects.path());
        body.put("projects", found);
        return body;
    }

    /**
     * The body of "New project": a name for its directory, and either the specification it is built from or
     * {@code from}, an existing project whose {@code bootstrap/} it duplicates.
     */
    public record NewProject(String name, String seed, String from) {
        public NewProject(String name, String seed) {
            this(name, seed, null);
        }
    }

    /**
     * Creates a project: {@code <projects>/<name>/bootstrap/seed.txt} from a specification, or -- with {@code from}
     * -- a copy of that project's whole {@code bootstrap/} (specification, obligations, protected paths, yardstick),
     * byte for byte, so obligations keyed to the specification's text stay valid. Nothing else: no code, no run
     * history. It is then started like any other project, in NEW_PROJECT mode. Refused when the name is taken: an
     * existing project is continued or restarted from its own specification, never overwritten from here.
     */
    @PostMapping("/home/projects")
    public ResponseEntity<Map<String, Object>> create(@RequestBody NewProject request) {
        if (request.name() == null || !PROJECT_NAME.matcher(request.name()).matches()) {
            return refuse(400, "a project name is lower case letters, digits, '.', '-' or '_', at most 64, "
                    + "starting with a letter or digit");
        }
        Path source = null;
        if (request.from() != null) {
            if (!PROJECT_NAME.matcher(request.from()).matches()
                    || !Files.isRegularFile(projects.path().resolve(request.from()).resolve("bootstrap/seed.txt"))) {
                return refuse(404, "no project named " + request.from() + " with a specification to duplicate");
            }
            source = projects.path().resolve(request.from()).resolve("bootstrap");
        } else if (request.seed() == null || request.seed().isBlank()) {
            return refuse(400, "a new project needs a specification to be built from");
        }
        Path dir = projects.path().resolve(request.name());
        if (Files.exists(dir)) return refuse(409, "a project named " + request.name() + " already exists");
        try {
            if (source != null) {
                copyTree(source, dir.resolve("bootstrap"));
            } else {
                Path seed = dir.resolve("bootstrap/seed.txt");
                Files.createDirectories(seed.getParent());
                Files.writeString(seed, request.seed().endsWith("\n") ? request.seed() : request.seed() + "\n",
                        StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return refuse(500, "the project could not be written: " + e.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("project", catalog.targetsUnder(projects.path()).stream()
                .filter(t -> t.name().equals(request.name())).findFirst().orElse(null));
        return ResponseEntity.status(201).body(body);
    }

    /**
     * The body of "Import one": a name for the project, the directory to copy in -- or {@code from}, a project here
     * whose code is copied -- and what may not be touched.
     */
    public record ImportRequest(String name, String source, String protectedPaths, String from) {
        public ImportRequest(String name, String source, String protectedPaths) {
            this(name, source, protectedPaths, null);
        }
    }

    /**
     * Imports a codebase on this machine as a new project: its tree copied into {@code output/} (build output,
     * version control and IDE state left out), {@code protectedPaths} into {@code bootstrap/protected-paths.txt}.
     * The page then starts it in ADOPT_PROJECT. The original directory is only read.
     */
    @PostMapping("/home/import")
    public ResponseEntity<Map<String, Object>> importProject(@RequestBody ImportRequest request) {
        if (request.name() == null || !PROJECT_NAME.matcher(request.name()).matches()) {
            return refuse(400, "a project name is lower case letters, digits, '.', '-' or '_', at most 64, "
                    + "starting with a letter or digit");
        }
        Path source;
        if (request.from() != null) {
            if (!PROJECT_NAME.matcher(request.from()).matches()
                    || !Files.isDirectory(projects.path().resolve(request.from()).resolve("output"))) {
                return refuse(404, "no project named " + request.from() + " with code to import");
            }
            source = projects.path().resolve(request.from()).resolve("output").toAbsolutePath().normalize();
        } else if (request.source() == null || request.source().isBlank()
                || !Path.of(request.source().strip()).isAbsolute()) {
            return refuse(400, "the source must be the absolute path of a directory on this machine");
        } else {
            source = Path.of(request.source().strip()).normalize();
        }
        Path dir = projects.path().resolve(request.name());
        if (Files.exists(dir)) return refuse(409, "a project named " + request.name() + " already exists");
        if (dir.toAbsolutePath().normalize().startsWith(source)) {
            return refuse(400, "the source contains the projects directory; choose the codebase itself");
        }
        ProjectImport.Copied copied;
        try {
            copied = ProjectImport.into(source, dir, request.protectedPaths());
        } catch (IllegalArgumentException e) {
            return refuse(400, e.getMessage());
        } catch (IOException e) {
            return refuse(500, "the import failed part-way (" + e.getMessage() + "); " + dir + " may hold a partial copy");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("files", copied.files());
        body.put("bytes", copied.bytes());
        body.put("leftOut", copied.leftOut());
        body.put("project", catalog.targetsUnder(projects.path()).stream()
                .filter(t -> t.name().equals(request.name())).findFirst().orElse(null));
        return ResponseEntity.status(201).body(body);
    }

    /** At most this many sub-directories are listed for one directory; the rest are counted. */
    static final int MAX_LISTED_DIRECTORIES = 500;

    /**
     * The directories inside {@code path} (the user's home when absent), for the page's folder picker -- a browser
     * cannot hand a page the absolute path of a directory, so the page asks this machine instead (the user,
     * 2026-11-06: "the path of the project should be selectable by an element"). Hidden directories come after
     * the others; only directories are listed, never file contents.
     */
    @GetMapping("/home/directories")
    public ResponseEntity<Map<String, Object>> directories(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String path) {
        Path dir = path == null || path.isBlank()
                ? Path.of(System.getProperty("user.home")) : Path.of(path.strip());
        if (!dir.isAbsolute()) return refuse(400, "the path must be absolute");
        dir = dir.normalize();
        if (!Files.isDirectory(dir)) return refuse(404, "no directory " + dir);
        List<String> names;
        try (var entries = Files.list(dir)) {
            names = entries.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    // Hidden directories are listed too, after the others: code worth importing lives in them
                    // (the user, 2026-11-06: "the selector doesn't show the dotted directories").
                    .sorted(java.util.Comparator.comparing((String n) -> n.startsWith("."))
                            .thenComparing(String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (IOException | SecurityException e) {
            return refuse(403, "the directory cannot be read: " + e.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", dir.toString());
        body.put("parent", dir.getParent() == null ? null : dir.getParent().toString());
        body.put("directories", names.size() > MAX_LISTED_DIRECTORIES ? names.subList(0, MAX_LISTED_DIRECTORIES) : names);
        body.put("notListed", Math.max(0, names.size() - MAX_LISTED_DIRECTORIES));
        return ResponseEntity.ok(body);
    }

    /** The run's own cold read of an adopted project: what its code is, from its own evidence. */
    @GetMapping(value = "/home/run/cold-read", produces = "text/plain")
    public ResponseEntity<String> coldRead() {
        return relayed(run.get("/autobuilder/cold-read"));
    }

    /** T4-EK: reverse-engineers the open project's code in its run (a job, minutes); read-only, the project is not touched. */
    @PostMapping(value = "/home/run/reverse-engineering", produces = "application/json")
    public ResponseEntity<String> reverseEngineer() {
        return relayed(run.postJson("/autobuilder/reverse-engineering/job", "", Duration.ofMinutes(2)));
    }

    /** T4-EK slice (b): the seed the operator confirmed, sent to the run that found it. */
    @PostMapping(value = "/home/run/reverse-engineering/confirm", consumes = "text/plain", produces = "application/json")
    public ResponseEntity<String> confirmReverseEngineering(@RequestBody String seed) {
        // Completing each earned goal exercises its criteria (package, start, run): minutes, not seconds.
        return relayed(run.postText("/autobuilder/reverse-engineering/confirm", seed, Duration.ofMinutes(15)));
    }

    /** T4-EK: where the reverse engineering is, and its account once done. */
    @GetMapping(value = "/home/run/reverse-engineering", produces = "application/json")
    public ResponseEntity<String> reverseEngineering() {
        return relayed(run.get("/autobuilder/reverse-engineering/job"));
    }

    /**
     * Gives the running adopted project its goal, through the run's own endpoint -- which writes it byte for byte
     * to {@code bootstrap/seed.txt} and refuses when the run already has one.
     */
    @PostMapping(value = "/home/run/goal", consumes = "text/plain", produces = "application/json")
    public ResponseEntity<String> goal(@RequestBody String goal) {
        return relayed(run.postText("/autobuilder/goal", goal));
    }

    /** The body of "Import several": the new project's name and goal, how it runs, and what each project brings. */
    public record IntegrateRequest(String name, String goal, String profiles, List<IntegrationJob.Input> inputs) {}

    /**
     * Brings two or more projects into a new one toward a goal (T4-EC's inputs), as a job the page follows on
     * {@code GET /home/integrate}. Refused while a run is up or another job is under way.
     */
    @PostMapping("/home/integrate")
    public synchronized ResponseEntity<Map<String, Object>> integrate(@RequestBody IntegrateRequest request) {
        if (request.name() == null || !PROJECT_NAME.matcher(request.name()).matches()) {
            return refuse(400, "a project name is lower case letters, digits, '.', '-' or '_', at most 64, "
                    + "starting with a letter or digit");
        }
        if (job != null && job.busy()) {
            return refuse(409, "projects are already being brought into " + job.describe().get("name"));
        }
        if (launcher.state() != ProjectLauncher.State.IDLE) return refuse(409, "a run is up; one run at a time");
        String problem = IntegrationJob.problem(projects.path(), request.name(), request.goal(), request.inputs());
        if (problem != null) return refuse(400, problem);
        job = new IntegrationJob(request.name(), projects.path(), true, request.goal(), request.profiles(), request.inputs(),
                launcher, run);
        job.begin();
        return ResponseEntity.accepted().body(job.describe());
    }

    /** The body of "Extend": the project that receives, how it runs, and the one input -- a project here or a path. */
    public record ExtendRequest(String project, String profiles, IntegrationJob.Input input) {}

    /**
     * Brings one project into an existing one (T4-EC's input), as a job the page follows on {@code GET /home/integrate}.
     * The receiving project may be the one whose loop is running (it takes the input between cycles), one whose
     * finished run is still open (closed first: the operator asked for this), or one at rest (started held,
     * released after). Refused while another project's run is up, or a run is starting.
     */
    @PostMapping("/home/extend")
    public synchronized ResponseEntity<Map<String, Object>> extend(@RequestBody ExtendRequest request) {
        if (request.project() == null || !PROJECT_NAME.matcher(request.project()).matches()) {
            return refuse(400, "the project to extend is a name in " + projects.path());
        }
        if (job != null && job.busy()) {
            return refuse(409, "projects are already being brought into " + job.describe().get("name"));
        }
        Path receiving = projects.path().resolve(request.project());
        ProjectLauncher.State now = launcher.state();
        if (now == ProjectLauncher.State.STARTING) return refuse(409, "a run is starting; wait for it");
        if (now != ProjectLauncher.State.IDLE && !launcher.isOn(receiving)) {
            return refuse(409, "another project's run is up; close it first (one run at a time)");
        }
        String problem = IntegrationJob.extendProblem(projects.path(), request.project(), request.input());
        if (problem != null) return refuse(400, problem);
        job = new IntegrationJob(request.project(), projects.path(), false, null, request.profiles(),
                List.of(request.input()), launcher, run);
        job.begin();
        return ResponseEntity.accepted().body(job.describe());
    }

    /**
     * T4-EI: the operator's choices for a capability import -- a list of {@code {capability, mode, description,
     * reference}} -- relayed to the run, which answers with what they would bring in.
     */
    @PostMapping(value = "/home/integrate/capabilities", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> chooseCapabilities(@RequestBody String choices) {
        IntegrationJob j = job;
        if (j == null) return refuse(409, "nothing is being brought in");
        try {
            j.choose(choices);
        } catch (IllegalStateException e) {
            return refuse(400, e.getMessage());
        }
        return ResponseEntity.ok(j.describe());
    }

    /** T4-EI: the chosen capabilities brought in; the import then waits for its conflicts and keep, as any other. */
    @PostMapping("/home/integrate/capabilities/import")
    public ResponseEntity<Map<String, Object>> importCapabilities() {
        IntegrationJob j = job;
        if (j == null) return refuse(409, "nothing is being brought in");
        try {
            j.importChosen();
        } catch (IllegalStateException e) {
            return refuse(409, e.getMessage());
        }
        return ResponseEntity.accepted().body(j.describe());
    }

    /** The integration job, if there is one: its phase and each input's outcome. */
    @GetMapping("/home/integrate")
    public ResponseEntity<Map<String, Object>> integration() {
        IntegrationJob j = job;
        return j == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(j.describe());
    }

    /** The conflicts no intent settled, as the run holds them. */
    @GetMapping(value = "/home/run/conflicts", produces = "application/json")
    public ResponseEntity<String> conflicts() {
        return relayed(run.get("/autobuilder/inputs/conflicts"));
    }

    /** One conflict decided: {@code input} or {@code receiving} wins, or {@code own} with the operator's resolution. */
    public record Decision(String decision, String resolution) {}

    /** Where the input under way stands: its step out of all, and how far through the slow ones. 204 when none is. */
    @GetMapping(value = "/home/run/input-progress", produces = "application/json")
    public ResponseEntity<String> inputProgress() {
        return relayed(run.get("/autobuilder/inputs/progress"));
    }

    /** The ways a conflict can be resolved, each with what it changes: the model's advice, nothing decided. */
    @GetMapping(value = "/home/run/conflicts/{id}/options", produces = "application/json")
    public ResponseEntity<String> conflictOptions(@PathVariable String id) {
        if (!id.matches("[A-Za-z0-9._-]+")) return ResponseEntity.badRequest().body("{\"ok\":false,\"message\":\"no such conflict\"}");
        return relayed(run.get("/autobuilder/inputs/conflicts/" + id + "/options"));
    }

    @PostMapping(value = "/home/run/conflicts/{id}", produces = "application/json")
    public ResponseEntity<String> decide(@PathVariable String id, @RequestBody Decision d) {
        if (!id.matches("[A-Za-z0-9._-]+")) return ResponseEntity.badRequest().body("{\"ok\":false,\"message\":\"no such conflict\"}");
        String body;
        try {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("decision", String.valueOf(d.decision()));
            fields.put("by", "operator (home screen)");
            if (d.resolution() != null) fields.put("resolution", d.resolution());
            body = JSON.writeValueAsString(fields);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return ResponseEntity.badRequest().body("{\"ok\":false,\"message\":\"unreadable decision\"}");
        }
        return relayed(run.postJson("/autobuilder/inputs/conflicts/" + id, body, Duration.ofMinutes(5)));
    }

    /** Takes what the job brought in back out; the project, if it was started held for this, stays held. */
    @PostMapping("/home/integrate/cancel")
    public ResponseEntity<Map<String, Object>> cancelIntegration() {
        IntegrationJob j = job;
        if (j == null) return refuse(409, "nothing is being brought in");
        try {
            j.cancel();
        } catch (IllegalStateException e) {
            return refuse(409, e.getMessage());
        }
        return ResponseEntity.ok(j.describe());
    }

    /** The imports of the running project that wait for confirmation -- from this session or an earlier one. */
    @GetMapping(value = "/home/run/pending", produces = "application/json")
    public ResponseEntity<String> pendingImports() {
        return relayed(run.get("/autobuilder/inputs/pending"));
    }

    /** The running loop at a glance, as the run reports it. */
    @GetMapping(value = "/home/run/live", produces = "application/json")
    public ResponseEntity<String> live() {
        return relayed(run.get("/autobuilder/live"));
    }

    /** Older notable events of the running loop, before an instant, newest first. */
    @GetMapping(value = "/home/run/live/events", produces = "application/json")
    public ResponseEntity<String> liveEvents(@org.springframework.web.bind.annotation.RequestParam String before) {
        if (!before.matches("[0-9T:.Z+-]+")) return ResponseEntity.badRequest().body("{\"ok\":false,\"message\":\"before is an instant\"}");
        return relayed(run.get("/autobuilder/live/events?before=" + before + "&limit=50"));
    }

    /** Every import of the running project not cancelled, kept or pending. */
    @GetMapping(value = "/home/run/imports", produces = "application/json")
    public ResponseEntity<String> imports() {
        return relayed(run.get("/autobuilder/inputs/imports"));
    }

    /** One pending import kept. */
    @PostMapping(value = "/home/run/imports/{id}/keep", produces = "application/json")
    public ResponseEntity<String> keepImport(@PathVariable String id) {
        if (!id.matches("[A-Za-z0-9._-]+")) return ResponseEntity.badRequest().body("{\"ok\":false,\"message\":\"no such import\"}");
        return relayed(run.postJson("/autobuilder/inputs/" + id + "/keep?by=operator%20(home%20screen)", "", Duration.ofMinutes(2)));
    }

    /** One pending import cancelled; it leaves nothing. A kept import is part of the project (T4-EG). */
    @PostMapping(value = "/home/run/imports/{id}/cancel", produces = "application/json")
    public ResponseEntity<String> cancelImport(@PathVariable String id) {
        if (!id.matches("[A-Za-z0-9._-]+")) return ResponseEntity.badRequest().body("{\"ok\":false,\"message\":\"no such import\"}");
        return relayed(run.postJson("/autobuilder/inputs/" + id + "/cancel?by=operator%20(home%20screen)", "", Duration.ofMinutes(5)));
    }

    /**
     * Starts the loop on a held project whose imports have all been kept or cancelled and whose conflicts are decided --
     * the way on after reviewing an import from an earlier session (within a session, the job's release does this).
     */
    @PostMapping("/home/run/continue")
    public ResponseEntity<Map<String, Object>> continueHeld() {
        if (job != null && job.busy()) return refuse(409, "finish the import under way first");
        if (!launcher.loopGoing() && launcher.state() == ProjectLauncher.State.RUNNING) {
            RunClient.Reply pending = run.get("/autobuilder/inputs/pending");
            RunClient.Reply open = run.get("/autobuilder/inputs/conflicts");
            if (!pending.ok() || !open.ok()) return refuse(502, "the run's imports could not be read");
            // Read as lists: the run writes an empty one as "[ ]", so comparing text refused with nothing waiting.
            if (!emptyList(pending.body())) return refuse(409, "an import still waits to be kept or cancelled");
            if (!emptyList(open.body())) return refuse(409, "a conflict still waits for a decision");
            RunClient.Reply started = run.postJson("/autobuilder/start?mode=ADOPT_PROJECT", "", Duration.ofMinutes(2));
            if (!started.ok()) return refuse(started.status(), "the run was not started: " + started.body());
            return ResponseEntity.ok(launcher.describe());
        }
        return refuse(409, "no held project to continue");
    }

    /** Starts the loop on the brought-together project once no conflict is open. */
    @PostMapping("/home/integrate/release")
    public ResponseEntity<Map<String, Object>> release() {
        IntegrationJob j = job;
        if (j == null) return refuse(409, "no projects are being brought together");
        try {
            j.release();
        } catch (IllegalStateException e) {
            return refuse(409, e.getMessage());
        } catch (IOException e) {
            return refuse(502, "the run's conflicts could not be read: " + e.getMessage());
        }
        return ResponseEntity.ok(j.describe());
    }

    private static boolean emptyList(String json) {
        try {
            com.fasterxml.jackson.databind.JsonNode n = JSON.readTree(json);
            return n.isArray() && n.isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    private static ResponseEntity<String> relayed(RunClient.Reply reply) {
        return ResponseEntity.status(reply.status()).body(reply.body());
    }

    /** The body of "Start": a project NAME in the projects directory, and optionally a mode and profiles. */
    public record StartRequest(String project, String mode, String profiles) {}

    /** Starts a run on a project. Refused while another run is starting or running (one run at a time). */
    @PostMapping("/home/start")
    public ResponseEntity<Map<String, Object>> start(@RequestBody StartRequest request) {
        if (request.project() == null || request.project().isBlank()
                || request.project().contains("/") || request.project().contains("..")) {
            return refuse(400, "project must be the name of a directory in " + projects.path());
        }
        Path dir = projects.path().resolve(request.project());
        if (!Files.isDirectory(dir)) return refuse(404, "no project named " + request.project() + " in " + projects.path());
        try {
            launcher.start(new ProjectChoice(dir.toString(), request.mode(), request.profiles()));
        } catch (IllegalStateException e) {
            return refuse(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            return refuse(400, "unknown mode " + request.mode());
        }
        return ResponseEntity.accepted().body(launcher.describe());
    }

    /**
     * Stops the running loop: the run's own pause ({@code user-pause}), which waits for the current cycle to
     * finish. Sent in the background, since that can take minutes; the run then shows as ENDED, its results
     * readable until the operator goes back home.
     */
    @PostMapping("/home/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        try {
            launcher.stopRequested();
        } catch (IllegalStateException e) {
            return refuse(409, e.getMessage());
        }
        Thread t = new Thread(() -> {
            RunClient.Reply reply = run.postJson("/autobuilder/pause", "", Duration.ofHours(2));
            if (!reply.ok()) log.warn("T4-ED: the run did not take the stop: {} {}", reply.status(), reply.body());
        }, "home-stop");
        t.setDaemon(true);
        t.start();
        return ResponseEntity.accepted().body(launcher.describe());
    }

    /** Back to the home screen: closes a run that has ended (or clears a failed start). 409 while one runs. */
    @PostMapping("/home/return")
    public ResponseEntity<Map<String, Object>> returnHome() {
        IntegrationJob j = job;
        if (j != null && j.busy() && j.phase() != IntegrationJob.Phase.DECIDING) {
            return refuse(409, "projects are being brought into " + j.describe().get("name") + "; wait for it");
        }
        try {
            launcher.returnHome();
        } catch (IllegalStateException e) {
            return refuse(409, e.getMessage());
        }
        job = null;
        return ResponseEntity.ok(launcher.describe());
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target);
            }
        }
    }

    private static ResponseEntity<Map<String, Object>> refuse(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("ok", false, "message", message));
    }
}
