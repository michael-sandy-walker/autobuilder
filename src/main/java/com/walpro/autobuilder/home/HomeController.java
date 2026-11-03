package com.walpro.autobuilder.home;

import com.walpro.autobuilder.core.init.TargetCatalog;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T4-ED: the home screen's endpoints -- what can be started, what is running, and "Start".
 *
 * <p>Every action the page takes is one of these, so a script can take it with curl.
 */
@Profile("home")
@RestController
@RequestMapping("/home")
public class HomeController {

    private final ProjectLauncher launcher;
    private final HomeApplication.ProjectsDirectory projects;
    private final TargetCatalog catalog = new TargetCatalog();

    public HomeController(ProjectLauncher launcher, HomeApplication.ProjectsDirectory projects) {
        this.launcher = launcher;
        this.projects = projects;
    }

    /** Whether a run is idle, starting, running or failed to start, and on what. */
    @GetMapping("/state")
    public Map<String, Object> state() {
        return launcher.describe();
    }

    /** The projects in the projects directory, with what each carries and the modes it can start in. */
    @GetMapping("/projects")
    public Map<String, Object> projects() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("directory", projects.path().toString());
        List<TargetCatalog.Target> found = catalog.targetsUnder(projects.path());
        body.put("projects", found);
        return body;
    }

    /** The body of "Start": a project NAME in the projects directory, and optionally a mode and profiles. */
    public record StartRequest(String project, String mode, String profiles) {}

    /** Starts a run on a project. Refused while another run is starting or running (one run at a time). */
    @PostMapping("/start")
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

    private static ResponseEntity<Map<String, Object>> refuse(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("ok", false, "message", message));
    }
}
