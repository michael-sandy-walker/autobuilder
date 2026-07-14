package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * T4-BV: execution-observation gate at the completion edge. The package gate proves the artifact
 * assembles; the test gate proves its own tests pass (and abstains when there are none — the
 * common case); NOTHING ever runs the artifact. Ground truth from the preserved corpus: false
 * completion #2 (2026-07-02) shipped a jar that died at launch, and the two fulfilled runs of
 * 2026-07-02/03 shipped jars whose spec-named endpoints were formally present but unreachable at
 * runtime — every one of those defects was found only by a manual boot-and-probe after the run.
 * This gate mechanizes that ritual: launch the packaged artifact once, verify it starts and
 * serves, probe the spec-declared parameterless GET endpoints live, and exercise the
 * spec-declared path-variable-free POST endpoints with synthesized bodies (T4-CB — the fourth
 * false-completion sighting fulfilled with a POST crashing on an id cast that no gate had ever
 * exercised; only a 5xx or connection death on a write blocks, never a 4xx).
 *
 * <p>Same cost discipline as the sibling gates: runs ONCE per completion claim, never per-cycle.
 * The process is always destroyed before returning; the launch happens on an ephemeral local
 * port, chosen here and handed to the artifact via the configured command template and/or
 * environment variable (which mechanism the target ecosystem honours is config data, not code).
 *
 * <p>Blocks (returns a work-item summary) when the process exits at startup, when the spec names
 * endpoints and the process never serves HTTP, or when a spec-named probeable endpoint answers
 * 404/5xx at runtime. Abstains (empty) on: gate or build executor disabled, no packaged artifact
 * found, an artifact older than the sources (no fresh packaging observation — never judge old code),
 * and a spec that names no endpoints while the process runs cleanly — no observation is never
 * treated as failure.
 */
@Slf4j
@Component
public class BootGate {

    private final boolean enabled;
    private final String commandTemplate;
    private final String portEnvVar;
    private final long readinessTimeoutMs;
    private final long requestTimeoutMs;
    private final BuildExecutor buildExecutor;
    private final EndpointSurfaceQuery endpointSurfaceQuery;

    @Value("${file.base-path}")
    @Setter
    private String fileBasePath;

    /** T5-N: optional behaviour trace — emits {@code build_fix_attempt} with source {@code boot-gate}. */
    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    @Autowired
    public BootGate(
            @Value("${autobuilder.build.boot-gate.enabled:true}") boolean enabled,
            @Value("${autobuilder.build.boot-gate.command:java -jar {artifact}}") String commandTemplate,
            @Value("${autobuilder.build.boot-gate.port-env-var:}") String portEnvVar,
            @Value("${autobuilder.build.boot-gate.readiness-timeout-ms:60000}") long readinessTimeoutMs,
            @Value("${autobuilder.build.boot-gate.request-timeout-ms:5000}") long requestTimeoutMs,
            BuildExecutor buildExecutor,
            EndpointSurfaceQuery endpointSurfaceQuery) {
        this.enabled = enabled;
        this.commandTemplate = commandTemplate;
        this.portEnvVar = portEnvVar;
        this.readinessTimeoutMs = readinessTimeoutMs;
        this.requestTimeoutMs = requestTimeoutMs;
        this.buildExecutor = buildExecutor;
        this.endpointSurfaceQuery = endpointSurfaceQuery;
    }

    /**
     * Launches the packaged artifact once and observes it. Returns a short failure summary when
     * the artifact cannot start or does not serve what the spec names (→ caller refuses
     * completion and injects the summary as a work item), or empty when the artifact runs
     * clean — or when no answer could be obtained.
     */
    public Optional<String> verifyBootsAndServes(String cycleId, String specText) {
        if (!enabled || !buildExecutor.isEnabled()) {
            return Optional.empty();
        }
        // T1-FJ field run (2026-07-07): must be absolute. buildCommand() embeds artifact.toString()
        // literally into the {artifact} command-line token, but launchAndObserve() also changes
        // the CHILD PROCESS's cwd via ProcessBuilder.directory(...) — a relative artifact path
        // (workingDir was built from the relative FILE_BASE_PATH/output config) then gets
        // re-resolved against that NEW cwd instead of the one it was actually relative to,
        // producing "Unable to access jarfile ...": the gate blocked every completion attempt on
        // its own path bug, not a real defect. JUnit's @TempDir always hands back absolute paths,
        // so no unit test exercised this relative-path interaction.
        Path workingDir = Path.of(buildExecutor.resolveWorkingDir(fileBasePath)).toAbsolutePath().normalize();
        Optional<Path> artifact = findPackagedArtifact(workingDir);
        if (artifact.isEmpty()) {
            log.info("T4-BV: no packaged artifact under {} — abstaining (packageability is the package gate's question)",
                    workingDir.resolve("target"));
            return Optional.empty();
        }
        if (artifactOlderThanSources(artifact.get(), workingDir)) {
            log.info("T4-BV: packaged artifact predates the sources — no fresh execution observation, abstaining");
            return Optional.empty();
        }
        Set<EndpointSurfaceQuery.Endpoint> declared = specText == null
                ? Set.of() : endpointSurfaceQuery.declaredInSpec(specText);

        long start = System.currentTimeMillis();
        Optional<String> failure = launchAndObserve(artifact.get(), declared, workingDir);
        long elapsed = System.currentTimeMillis() - start;
        log.info("T4-BV: boot gate result={} (durationMs={})", failure.isEmpty() ? "OK" : "BLOCKED", elapsed);
        if (behaviourLogger != null) {
            behaviourLogger.buildFixAttempt(cycleId, "boot-gate", 0, null, null,
                    null, List.of(), failure.isEmpty() ? "OK" : "BLOCKED", elapsed);
        }
        return failure;
    }

    /**
     * The launch-observe-destroy lifecycle. The process is destroyed on every exit path; an
     * environment failure to even spawn the process is an abstain, but everything the process
     * itself does (crash, silence, wrong answers) is the artifact's own behaviour and can block.
     */
    private Optional<String> launchAndObserve(Path artifact, Set<EndpointSurfaceQuery.Endpoint> declared,
                                              Path workingDir) {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        } catch (IOException e) {
            log.warn("T4-BV: could not allocate a local port ({}) — abstaining", e.getMessage());
            return Optional.empty();
        }
        List<String> command = buildCommand(artifact, port);
        if (command.isEmpty()) {
            return Optional.empty();
        }
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(artifact.getParent().getParent().toFile())
                    .redirectErrorStream(true);
            if (portEnvVar != null && !portEnvVar.isBlank()) {
                pb.environment().put(portEnvVar.trim(), String.valueOf(port));
            }
            process = pb.start();
            Deque<String> outputTail = new ArrayDeque<>();
            Thread drainer = drainerThread(process.getInputStream(), outputTail);
            drainer.start();

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(Math.min(requestTimeoutMs, 1000)))
                    .build();
            boolean serving = awaitHttpReadiness(process, client, port);

            if (!process.isAlive()) {
                int exit = process.exitValue();
                drainer.join(1000);
                if (exit != 0) {
                    return Optional.of("the application exits at startup (exit " + exit + ") — "
                            + summariseOutput(outputTail));
                }
                if (!declared.isEmpty()) {
                    return Optional.of("the application exits immediately instead of staying up to serve"
                            + " the endpoints the goal specification names — " + summariseOutput(outputTail));
                }
                return Optional.empty(); // ran and exited clean; spec asks for no served surface — no opinion
            }
            if (!serving) {
                if (!declared.isEmpty()) {
                    return Optional.of("the application starts but never answers HTTP on its assigned port"
                            + " within " + readinessTimeoutMs / 1000 + "s, although the goal specification"
                            + " names endpoints — " + summariseOutput(outputTail));
                }
                return Optional.empty(); // long-running non-serving process, nothing expected of it — no opinion
            }
            List<String> endpointFailures = probeDeclaredEndpoints(client, port, declared, workingDir);
            if (!endpointFailures.isEmpty()) {
                // T1-FJ (2026-07-07 run 22): a bare status code ("GET /loans answers 500") is not
                // actionable — the actual cause (e.g. "Ensure that the compiler uses the
                // '-parameters' flag") is sitting in the process's own console output the whole
                // time (Spring logs the unhandled exception there when a request 500s) but was
                // never threaded through, unlike the exit-code and never-serving branches below.
                // Same summariseOutput used there, so a 500 becomes a diagnosable work item
                // instead of a bare code the evolution agent has no lead on.
                return Optional.of("the application runs, but spec-named endpoint(s) fail at runtime: "
                        + String.join("; ", endpointFailures) + " — " + summariseOutput(outputTail));
            }
            return Optional.empty();
        } catch (IOException e) {
            log.warn("T4-BV: could not launch '{}' ({}) — abstaining", String.join(" ", command), e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** Polls the root path until the process answers HTTP (any status), dies, or the deadline passes. */
    private boolean awaitHttpReadiness(Process process, HttpClient client, int port) throws InterruptedException {
        long deadline = System.currentTimeMillis() + readinessTimeoutMs;
        while (System.currentTimeMillis() < deadline && process.isAlive()) {
            if (respondsToHttp(client, port, "/")) {
                return true;
            }
            Thread.sleep(Math.min(250, Math.max(50, readinessTimeoutMs / 10)));
        }
        return process.isAlive() && respondsToHttp(client, port, "/");
    }

    private boolean respondsToHttp(HttpClient client, int port, String path) {
        try {
            client.send(request(port, path), HttpResponse.BodyHandlers.discarding());
            return true; // any HTTP status counts — the server is up and routing
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Probes the deterministically probeable subset of the spec's endpoint surface: GET and POST,
     * no path variables (an unknown id would 404/behave legitimately), query strings already
     * normalized away. GET: a 404 means the mapping is absent at runtime even if the source
     * declares it (component not registered, wrong context) — the run-4b class; 5xx means the
     * endpoint crashes on first touch. POST (T4-CB): candidate bodies from
     * {@link RequestBodySynthesizer}; ONLY 5xx or connection death blocks — a 404 can be a
     * legitimate domain not-found on a write and a 400 is the validation surface working.
     * Anything else proves the endpoint is live.
     */
    private List<String> probeDeclaredEndpoints(HttpClient client, int port,
                                                Set<EndpointSurfaceQuery.Endpoint> declared, Path workingDir) {
        List<String> failures = new ArrayList<>();
        for (EndpointSurfaceQuery.Endpoint endpoint : declared) {
            if (failures.size() >= MAX_NAMED_FAILURES) break;
            if (endpoint.path().contains("{}")) continue; // an unknown id would 404/behave legitimately
            if ("GET".equals(endpoint.verb())) {
                try {
                    HttpResponse<Void> response = client.send(
                            request(port, endpoint.path()), HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() == 404) {
                        failures.add(endpoint + " answers 404 — the mapping is not reachable in the running application");
                    } else if (response.statusCode() >= 500) {
                        failures.add(endpoint + " answers " + response.statusCode());
                    }
                } catch (IOException e) {
                    failures.add(endpoint + " gets no answer (" + e.getMessage() + ")");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return failures;
                }
            } else if ("POST".equals(endpoint.verb())) {
                // T4-CB: exercise the write surface. The 2026-07-11 false completion of this class
                // fulfilled with POST /loans crashing on an id cast — no gate had ever sent a POST.
                // Precision-first assertion: ONLY >=500 or connection death blocks. A 404 on a
                // write can be a legitimate domain not-found (unlike the parameterless-GET case
                // above) and a 400 is the validation surface working — both prove the endpoint is
                // alive and handled, which is all this gate claims.
                for (String body : RequestBodySynthesizer.candidateBodies(endpoint.path(), workingDir)) {
                    if (failures.size() >= MAX_NAMED_FAILURES) break;
                    try {
                        HttpResponse<Void> response = client.send(
                                postRequest(port, endpoint.path(), body), HttpResponse.BodyHandlers.discarding());
                        if (response.statusCode() >= 500) {
                            failures.add(endpoint + " answers " + response.statusCode()
                                    + " when exercised with body " + body);
                        }
                    } catch (IOException e) {
                        failures.add(endpoint + " gets no answer when exercised (" + e.getMessage() + ")");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return failures;
                    }
                }
            }
        }
        return failures;
    }

    private HttpRequest request(int port, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofMillis(requestTimeoutMs))
                .GET()
                .build();
    }

    private HttpRequest postRequest(int port, String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofMillis(requestTimeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    /**
     * The newest packaged archive under {@code target/} (top level only), skipping the
     * classifier archives that are never the runnable artifact.
     */
    private Optional<Path> findPackagedArtifact(Path workingDir) {
        Path target = workingDir.resolve("target");
        if (!Files.isDirectory(target)) return Optional.empty();
        try (Stream<Path> entries = Files.list(target)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".jar")
                                && !name.endsWith("-sources.jar")
                                && !name.endsWith("-javadoc.jar");
                    })
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()));
        } catch (IOException e) {
            log.debug("T4-BV: artifact scan failed ({}) — abstaining", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * True when any source file is newer than the packaged artifact — then the archive proves
     * nothing about the code being judged for completion (e.g. the package gate is disabled and
     * a stale build lingers), so the gate must stay silent rather than grade old code.
     */
    private boolean artifactOlderThanSources(Path artifact, Path workingDir) {
        long artifactModified = artifact.toFile().lastModified();
        Path sourceRoot = workingDir.resolve("src");
        if (!Files.isDirectory(sourceRoot)) return false;
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            return walk.anyMatch(p -> Files.isRegularFile(p)
                    && p.toFile().lastModified() > artifactModified);
        } catch (IOException e) {
            log.debug("T4-BV: source-freshness scan failed ({}) — treating artifact as fresh", e.getMessage());
            return false;
        }
    }

    /** Same minimal tokenization contract as {@link BuildExecutor}: whitespace split, no shell semantics. */
    private List<String> buildCommand(Path artifact, int port) {
        if (commandTemplate == null || commandTemplate.isBlank()) return List.of();
        String rendered = commandTemplate
                .replace("{artifact}", artifact.toString())
                .replace("{port}", String.valueOf(port));
        return Arrays.stream(rendered.trim().split("\\s+")).toList();
    }

    private static final int MAX_NAMED_FAILURES = 5;
    private static final int OUTPUT_TAIL_LINES = 50;
    private static final int SUMMARY_LINES = 3;

    /** Last diagnostic-looking lines of the process output, ANSI-stripped — feeds a subgoal description. */
    private static String summariseOutput(Deque<String> outputTail) {
        List<String> lines = outputTail.stream()
                .map(l -> BuildErrorClassifier.ANSI_ESCAPE.matcher(l).replaceAll("").strip())
                .filter(l -> !l.isBlank())
                .toList();
        if (lines.isEmpty()) return "no output before the observation ended";
        List<String> diagnostic = lines.stream()
                .filter(l -> l.contains("Exception") || l.contains("ERROR") || l.contains("Error")
                        || l.toLowerCase(Locale.ROOT).contains("caused by"))
                .toList();
        List<String> chosen = diagnostic.isEmpty() ? lines : diagnostic;
        return String.join(" | ", chosen.subList(Math.max(0, chosen.size() - SUMMARY_LINES), chosen.size()));
    }

    private static Thread drainerThread(InputStream in, Deque<String> tail) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    tail.addLast(line);
                    while (tail.size() > OUTPUT_TAIL_LINES) {
                        tail.removeFirst();
                    }
                }
            } catch (IOException ignored) {
                // process exited or stream closed — fine.
            }
        });
        t.setDaemon(true);
        return t;
    }
}
