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

    /** The body of "New project": a name for its directory and the specification it is built from. */
    public record NewProject(String name, String seed) {}

    /**
     * Creates a project from a specification: {@code <projects>/<name>/bootstrap/seed.txt}, nothing else. It
     * is then started like any other project, in NEW_PROJECT mode. Refused when the name is taken: an existing
     * project is continued or begun again from its own specification, never overwritten from here.
     */
    @PostMapping("/home/projects")
    public ResponseEntity<Map<String, Object>> create(@RequestBody NewProject request) {
        if (request.name() == null || !PROJECT_NAME.matcher(request.name()).matches()) {
            return refuse(400, "a project name is lower case letters, digits, '.', '-' or '_', at most 64, "
                    + "starting with a letter or digit");
        }
        if (request.seed() == null || request.seed().isBlank()) {
            return refuse(400, "a new project needs a specification to be built from");
        }
        Path dir = projects.path().resolve(request.name());
        if (Files.exists(dir)) return refuse(409, "a project named " + request.name() + " already exists");
        try {
            Path seed = dir.resolve("bootstrap/seed.txt");
            Files.createDirectories(seed.getParent());
            Files.writeString(seed, request.seed().endsWith("\n") ? request.seed() : request.seed() + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            return refuse(500, "the project could not be written: " + e.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("project", catalog.targetsUnder(projects.path()).stream()
                .filter(t -> t.name().equals(request.name())).findFirst().orElse(null));
        return ResponseEntity.status(201).body(body);
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

    private static ResponseEntity<Map<String, Object>> refuse(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("ok", false, "message", message));
    }
}
