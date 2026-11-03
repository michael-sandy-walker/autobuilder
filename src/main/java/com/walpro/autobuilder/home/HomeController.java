package com.walpro.autobuilder.home;

import com.walpro.autobuilder.core.init.TargetCatalog;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
@Profile("home")
@RestController
public class HomeController {

    /** A project name is a directory name: lower case, digits, dot, dash, underscore; no path. */
    private static final Pattern PROJECT_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private final ProjectLauncher launcher;
    private final HomeApplication.ProjectsDirectory projects;
    private final TargetCatalog catalog = new TargetCatalog();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public HomeController(ProjectLauncher launcher, HomeApplication.ProjectsDirectory projects) {
        this.launcher = launcher;
        this.projects = projects;
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

    /** The body of "Import one": a name for the project, the directory to copy in, and what may not be touched. */
    public record ImportRequest(String name, String source, String protectedPaths) {}

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
        if (request.source() == null || request.source().isBlank() || !Path.of(request.source().strip()).isAbsolute()) {
            return refuse(400, "the source must be the absolute path of a directory on this machine");
        }
        Path dir = projects.path().resolve(request.name());
        if (Files.exists(dir)) return refuse(409, "a project named " + request.name() + " already exists");
        Path source = Path.of(request.source().strip()).normalize();
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
        return relay(HttpRequest.newBuilder().GET(), "/autobuilder/cold-read");
    }

    /**
     * Gives the running adopted project its goal, through the run's own endpoint -- which writes it byte for byte
     * to {@code bootstrap/seed.txt} and refuses when the run already has one.
     */
    @PostMapping(value = "/home/run/goal", consumes = "text/plain", produces = "application/json")
    public ResponseEntity<String> goal(@RequestBody String goal) {
        return relay(HttpRequest.newBuilder().header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(goal, StandardCharsets.UTF_8)), "/autobuilder/goal");
    }

    /**
     * Sends a request to the run's own endpoint and returns its answer as it came. Relayed rather than handled
     * here, so what a goal is and when one is refused stay where the run keeps them.
     */
    private ResponseEntity<String> relay(HttpRequest.Builder request, String path) {
        Object port = launcher.describe().get("runPort");
        if (port == null) return ResponseEntity.status(409).body("{\"ok\":false,\"message\":\"no run is serving\"}");
        try {
            HttpResponse<String> res = http.send(request.uri(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofMinutes(2)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return ResponseEntity.status(res.statusCode()).body(res.body());
        } catch (IOException e) {
            return ResponseEntity.status(502).body("{\"ok\":false,\"message\":\"the run did not answer\"}");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(502).body("{\"ok\":false,\"message\":\"interrupted\"}");
        }
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

    /** Back to the home screen: closes a run that has ended (or clears a failed start). 409 while one runs. */
    @PostMapping("/home/return")
    public ResponseEntity<Map<String, Object>> returnHome() {
        try {
            launcher.returnHome();
        } catch (IllegalStateException e) {
            return refuse(409, e.getMessage());
        }
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
