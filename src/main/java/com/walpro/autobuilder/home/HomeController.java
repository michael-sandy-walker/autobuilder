package com.walpro.autobuilder.home;

import com.walpro.autobuilder.core.init.TargetCatalog;
import lombok.extern.slf4j.Slf4j;
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

    public HomeController(ProjectLauncher launcher, HomeApplication.ProjectsDirectory projects) {
        this.launcher = launcher;
        this.projects = projects;
        this.run = new RunClient(launcher);
    }

    /** The home screen is the root of the home context. */
    @GetMapping("/")
    public ResponseEntity<Void> root() {
        return ResponseEntity.status(302).location(URI.create("/home.html")).build();
    }

    /** Whether a run is idle, starting, running or failed to start, and on what. */
    @GetMapping("/home/state")
    public Map<String, Object> state() {
        return launcher.describe();
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

    /** The run's own cold read of an adopted project: what its code is, from its own evidence. */
    @GetMapping(value = "/home/run/cold-read", produces = "text/plain")
    public ResponseEntity<String> coldRead() {
        return relayed(run.get("/autobuilder/cold-read"));
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
        if (job != null && job.phase() != IntegrationJob.Phase.FAILED && job.phase() != IntegrationJob.Phase.RELEASED) {
            return refuse(409, "projects are already being brought into " + job.describe().get("name"));
        }
        if (launcher.state() != ProjectLauncher.State.IDLE) return refuse(409, "a run is up; one run at a time");
        String problem = IntegrationJob.problem(projects.path(), request.name(), request.goal(), request.inputs());
        if (problem != null) return refuse(400, problem);
        job = new IntegrationJob(request.name(), projects.path(), request.goal(), request.profiles(), request.inputs(),
                launcher, run);
        job.begin();
        return ResponseEntity.accepted().body(job.describe());
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

    /** One conflict decided: {@code input} or {@code receiving} wins. */
    public record Decision(String decision) {}

    @PostMapping(value = "/home/run/conflicts/{id}", produces = "application/json")
    public ResponseEntity<String> decide(@PathVariable String id, @RequestBody Decision d) {
        if (!id.matches("[A-Za-z0-9._-]+")) return ResponseEntity.badRequest().body("{\"ok\":false,\"message\":\"no such conflict\"}");
        String body;
        try {
            body = JSON.writeValueAsString(Map.of("decision", String.valueOf(d.decision()), "by", "operator (home screen)"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return ResponseEntity.badRequest().body("{\"ok\":false,\"message\":\"unreadable decision\"}");
        }
        return relayed(run.postJson("/autobuilder/inputs/conflicts/" + id, body, Duration.ofMinutes(5)));
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
        if (j != null && j.phase() != IntegrationJob.Phase.FAILED && j.phase() != IntegrationJob.Phase.RELEASED
                && j.phase() != IntegrationJob.Phase.DECIDING) {
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
