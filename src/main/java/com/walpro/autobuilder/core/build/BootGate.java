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

    /**
     * T4-CU: optional probe synthesis. Absent (or disabled) means behavioural criteria are not
     * executed and the gate behaves exactly as before — the criterion check can only ever ADD an
     * observation, never remove one the gate already made.
     */
    @Setter
    @Autowired(required = false)
    private CriterionProbeSynthesizer criterionProbeSynthesizer;

    @Value("${autobuilder.build.boot-gate.criterion-probe-enabled:true}")
    @Setter
    private boolean criterionProbeEnabled = true;

    /**
     * T2-EV: the specification's own per-endpoint response expectations. When present, they
     * outrank this gate's fixed status rules — absent (or unwired), the fixed rules apply as
     * before, so an unavailable query can only ever lose precision, never invent a failure.
     */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.analysis.structure.SpecEndpointBehaviourQuery specBehaviourQuery;

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
        return verifyBootsAndServes(cycleId, specText, List.of());
    }

    /**
     * As {@link #verifyBootsAndServes(String, String)}, additionally executing the subset of
     * {@code behaviouralCriteria} that state one endpoint's response status (T4-CU). A criterion
     * the running artifact contradicts becomes part of the failure summary — which is the only way
     * a criterion the LLM evaluator marked {@code ✓} can ever be taken back.
     */
    /**
     * T6-J: optional cost tracking. This gate packages an artifact and launches a JVM with a
     * readiness wait of up to 60s, and until now nothing timed it — which is most of why 70 of a
     * run's 89 minutes could not be attributed.
     */
    @Autowired(required = false)
    @Setter
    private BuildCostTracker buildCostTracker;

    public Optional<String> verifyBootsAndServes(String cycleId, String specText,
                                                 List<String> behaviouralCriteria) {
        return verifyBootsAndServes(cycleId, specText, behaviouralCriteria, false);
    }

    /**
     * T4-DB: as above, permitting the model fallback for probe synthesis on THIS call.
     *
     * <p>Only the completion edge should pass {@code true}. T4-CY measured the model path costing
     * 29.5 and 14.9 minutes of a 90-minute run when called synchronously inside cycles, and left
     * it globally off with the note that it is <i>"affordable at the completion edge, and not
     * inside a cycle"</i>. Of this gate's four callers, one is that edge; the other three run per
     * cycle and must keep the deterministic-only path.
     */
    public Optional<String> verifyBootsAndServes(String cycleId, String specText,
                                                 List<String> behaviouralCriteria,
                                                 boolean allowModelFallback) {
        long t0 = System.nanoTime();
        try {
            return verifyBootsAndServesTimed(cycleId, specText, behaviouralCriteria,
                    allowModelFallback);
        } finally {
            if (buildCostTracker != null) {
                buildCostTracker.record(cycleId, "boot-gate", (System.nanoTime() - t0) / 1_000_000L);
            }
        }
    }

    private Optional<String> verifyBootsAndServesTimed(String cycleId, String specText,
                                                       List<String> behaviouralCriteria,
                                                       boolean allowModelFallback) {
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
        Optional<String> failure = launchAndObserve(artifact.get(), declared, workingDir,
                specText, behaviouralCriteria, allowModelFallback);
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
                                              Path workingDir, String specText,
                                              List<String> behaviouralCriteria,
                                              boolean allowModelFallback) {
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
            // T2-EV: endpoints whose behaviour the specification states — for these the criterion
            // probes below hold the verdict, so the fixed status rules defer.
            Set<EndpointSurfaceQuery.Endpoint> specStated = specBehaviourQuery == null
                    ? Set.of() : specBehaviourQuery.statedBehaviours(specText).keySet();
            List<String> endpointFailures = probeDeclaredEndpoints(
                    client, port, declared, workingDir, specStated);
            if (!endpointFailures.isEmpty()) {
                // T1-FJ (2026-07-07 run 22): a bare status code ("GET /loans answers 500") is not
                // actionable — the actual cause (e.g. "Ensure that the compiler uses the
                // '-parameters' flag") is sitting in the process's own console output the whole
                // time (Spring logs the unhandled exception there when a request 500s) but was
                // never threaded through, unlike the exit-code and never-serving branches below.
                // Same summariseOutput used there, so a 500 becomes a diagnosable work item
                // instead of a bare code the evolution agent has no lead on.
                // fall through — the criteria are asked as well, see below
            }
            // T4-CU: the artifact is up — now ask it the questions the acceptance criteria already
            // asked in prose.
            //
            // These run whenever the application is SERVING, not only when the observations above
            // came back clean. The original ordering ("judge a criterion only against a healthy
            // application") kept the only correctness signal in the system from ever producing a
            // reading: across four runs it never once executed, because the endpoint probes above
            // were blocking on 500s every time. A 500 does not make a criterion unjudgeable — it
            // definitively contradicts "POST /books returns HTTP 400 for invalid input", and
            // naming the status the spec asked for is MORE actionable than the bare code.
            //
            // Endpoint failures are still reported FIRST so cause precedes consequence: when
            // creation 500s, a probe's setup sequence fails and later criteria are contradicted
            // for a reason that is not their own, and the reader needs the 500 before the five
            // symptoms. The shared cap keeps the item short.
            List<String> criterionFailures = probeBehaviouralCriteria(allowModelFallback,
                    client, port, specText, behaviouralCriteria, workingDir);
            if (!endpointFailures.isEmpty() || !criterionFailures.isEmpty()) {
                StringBuilder summary = new StringBuilder("the application runs, but ");
                if (!endpointFailures.isEmpty()) {
                    summary.append("spec-named endpoint(s) fail at runtime: ")
                            .append(String.join("; ", endpointFailures));
                }
                if (!criterionFailures.isEmpty()) {
                    if (!endpointFailures.isEmpty()) summary.append(". Also, ");
                    summary.append("acceptance criteria fail when exercised against it: ")
                            .append(String.join("; ", criterionFailures));
                }
                if (!endpointFailures.isEmpty()) {
                    summary.append(" — ").append(summariseOutput(outputTail));
                }
                return Optional.of(summary.toString());
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
                                                Set<EndpointSurfaceQuery.Endpoint> declared, Path workingDir,
                                                Set<EndpointSurfaceQuery.Endpoint> specStatesStatusFor) {
        List<String> failures = new ArrayList<>();
        for (EndpointSurfaceQuery.Endpoint endpoint : declared) {
            if (failures.size() >= MAX_NAMED_FAILURES) break;
            if (endpoint.path().contains("{}")) continue; // an unknown id would 404/behave legitimately
            // T2-EV: where the SPECIFICATION states what this endpoint returns, the specification
            // decides — the criterion probes carry that verdict and these fixed rules stay out of
            // it. The rules below are stand-ins from before the loop could derive an expectation,
            // and the 404 rule in particular contradicts specs that say "404 if absent": it is
            // only safe today because path-variable endpoints are skipped two lines up, an
            // exclusion that also puts the /{id} defect class out of reach.
            if (specStatesStatusFor.contains(endpoint)) continue;
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

    /**
     * T4-CU: executes the acceptance criteria that state one endpoint's response status, and
     * reports the ones the running artifact contradicts.
     *
     * <p>Precision rules, all of them chosen because a false failure here costs real cycles:
     * <ul>
     *   <li>a criterion the parser cannot ground to (one endpoint, one stated status, no path
     *       variable) is skipped — see {@link BehaviouralCriterionParser};</li>
     *   <li>a criterion whose probe could not be synthesized, or whose synthesized probe drifts
     *       off the asserted endpoint, is skipped;</li>
     *   <li>a transport failure mid-sequence is an ABSTAIN for that criterion, not a failure: the
     *       probe learned nothing, and "no answer" must never be reported as "wrong answer";</li>
     *   <li>only a completed sequence whose final response status differs from the stated one is
     *       reported, and the report quotes the criterion verbatim so the repair reads the same
     *       words the planner wrote.</li>
     * </ul>
     */
    private List<String> probeBehaviouralCriteria(boolean allowModelFallback,
                                                  HttpClient client, int port, String specText,
                                                  List<String> criteria, Path artifactRoot) {
        List<String> failures = new ArrayList<>();
        if (!criterionProbeEnabled || criterionProbeSynthesizer == null
                || criteria == null || criteria.isEmpty()) {
            return failures;
        }
        int probed = 0;
        // T4-DB: three unrelated causes used to share one `skipped` counter, which made the
        // ticket's central claim — "the loop repairs what it can probe and fails what it cannot" —
        // unmeasurable per run. They want opposite responses: an UNPARSED criterion is an
        // authoring problem (T2-EX), an UNPROBEABLE one is a missing probe shape (this ticket),
        // and a TRANSPORT failure is the environment. A run reporting skipped=46 said none of that.
        int unparsed = 0;
        int unprobeable = 0;
        java.util.Map<Unobserved, Integer> unobserved = new java.util.EnumMap<>(Unobserved.class);
        // T4-DB: a HARD CAP on model-fallback calls per sweep, and it is not a tuning knob — it
        // was bought. Run 2026-08-05_20-58-50 reached the completion edge with 49 criteria of
        // which 30 were unprobed; the fallback fired against a model already returning timeouts,
        // the first network error landed in the same second as the sweep, five consecutive
        // failures opened the circuit breaker and the run was dead 15 seconds later. T4-CY had
        // measured this exact hazard and switched the path off; "affordable at the completion
        // edge" is true of a handful of calls and false of thirty.
        int modelFallbacksLeft = MAX_MODEL_FALLBACKS_PER_SWEEP;
        // Which SHAPES are missing, not just how many. "unprobeable=5" cannot tell a missing 422
        // synthesizer from a missing 409 one, and those are different builds. Aggregated onto the
        // existing line rather than logged per criterion: a gate run can carry dozens, and a
        // debug-level line is invisible at the level runs actually use.
        java.util.Map<Integer, Integer> unprobeableByStatus = new java.util.TreeMap<>();
        for (String criterion : criteria) {
            if (failures.size() >= MAX_NAMED_FAILURES) break;
            Optional<BehaviouralCriterionParser.Assertion> parsed = BehaviouralCriterionParser.parse(criterion);
            if (parsed.isEmpty()) {
                unparsed++;
                continue;
            }
            BehaviouralCriterionParser.Assertion assertion = parsed.get();
            // Deterministic first, always. Only if it abstains is the model consulted, and the
            // budget is spent on the CONSULTATION rather than on its outcome — the cost that took
            // a run down is the call itself, whether or not it yields a usable probe. Asking twice
            // is free: the deterministic path is milliseconds and reads only the artifact sources.
            Optional<List<CriterionProbeSynthesizer.ProbeRequest>> sequence =
                    criterionProbeSynthesizer.synthesize(specText, assertion, artifactRoot, false);
            if (sequence.isEmpty() && allowModelFallback && modelFallbacksLeft > 0) {
                modelFallbacksLeft--;
                sequence = criterionProbeSynthesizer.synthesize(specText, assertion, artifactRoot, true);
            }
            if (sequence.isEmpty()) {
                // Parsed cleanly and still no probe: the shape has no synthesizer. This is the
                // set T4-DB is about, and the status it asserts names which shape is missing.
                unprobeable++;
                unprobeableByStatus.merge(assertion.expectedStatus(), 1, Integer::sum);
                continue;
            }
            SequenceOutcome observed = executeSequence(client, port, sequence.get());
            if (!observed.isObserved()) {
                unobserved.merge(observed.abstain(), 1, Integer::sum);  // no observation, no opinion
                continue;
            }
            probed++;
            if (observed.status() != assertion.expectedStatus()) {
                failures.add("\"" + assertion.criterionText() + "\" — " + assertion.describe()
                        + ", but the running application answered " + observed.status()
                        + " to " + sequence.get().get(sequence.get().size() - 1));
            }
        }
        int unobservedTotal = unobserved.values().stream().mapToInt(Integer::intValue).sum();
        log.info("T4-CU: behavioural criteria probed={} skipped={} "
                        + "(unparsed={} unprobeable={}{} unobserved={}{}) contradicted={}",
                probed, unparsed + unprobeable + unobservedTotal,
                unparsed, unprobeable,
                unprobeableByStatus.isEmpty() ? "" : unprobeableByStatus.toString(),
                unobservedTotal, unobserved.isEmpty() ? "" : unobserved.toString(),
                failures.size());
        return failures;
    }

    /**
     * Why a sequence produced no observation. Naming these apart matters: they were reported as
     * one "transport" number until 2026-08-04, and transport was the RAREST of the three. The
     * common case is {@code SETUP_REFUSED} — the probe's own setup body is derived from field
     * names ({@code {"email":"value"}}) and a correct application declines it, 53 times in one
     * run. Calling that a transport failure sends the reader looking at the network.
     */
    enum Unobserved { SETUP_REFUSED, UNRESOLVED_REFERENCE, TRANSPORT }

    /**
     * How many criteria in one completion-edge sweep may fall through to the model. Five, because
     * a sweep of 49 criteria left 30 unprobed and firing 30 synchronous calls at a local model
     * already timing out took the run down (2026-08-05_20-58-50). The point of the fallback is to
     * recover a few high-value criteria the deterministic path cannot express, not to re-ask the
     * model about everything it declined.
     */
    static final int MAX_MODEL_FALLBACKS_PER_SWEEP = 5;

    /** The final status, or the reason there is none. Exactly one field is set. */
    record SequenceOutcome(Integer status, Unobserved abstain) {
        static SequenceOutcome observed(int status) { return new SequenceOutcome(status, null); }
        static SequenceOutcome none(Unobserved why) { return new SequenceOutcome(null, why); }
        boolean isObserved() { return status != null; }
    }

    /**
     * Sends the sequence in order and returns the FINAL response's status, or the reason no
     * observation was made — a partial sequence proves nothing about the criterion.
     */
    private SequenceOutcome executeSequence(HttpClient client, int port,
                                            List<CriterionProbeSynthesizer.ProbeRequest> sequence) {
        int status = -1;
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < sequence.size(); i++) {
            CriterionProbeSynthesizer.ProbeRequest r = sequence.get(i);
            // T4-CU slice 2: a criterion about a resource that must EXIST cannot name its id in
            // advance — the server assigns it. The probe refers to it as $N.field and it is
            // resolved here from the recorded response. Unresolvable means the setup did not
            // produce what the probe expected, so the criterion is not judged at all: guessing an
            // id would manufacture a contradiction against a correct artifact.
            Optional<CriterionProbeSynthesizer.ProbeRequest> resolved = resolveReferences(r, bodies);
            if (resolved.isEmpty()) {
                log.info("T4-CU: could not ground {} from earlier responses — criterion not judged", r);
                return SequenceOutcome.none(Unobserved.UNRESOLVED_REFERENCE);
            }
            try {
                HttpResponse<String> response = client.send(
                        probeRequest(port, resolved.get()), HttpResponse.BodyHandlers.ofString());
                status = response.statusCode();
                bodies.add(response.body());
                // A SETUP request that was refused means the state the asserted request needs was
                // never created, so whatever the asserted request answers is about the wrong
                // situation. Measured on the live specification: the duplicate-loan probe's first
                // POST is refused when the book and member it references do not exist, and without
                // this guard the second POST's 404 would be reported as "409 expected, got 404" —
                // a defect the artifact does not have. Setup failure is an abstain, like transport
                // failure: the probe learned nothing.
                if (i < sequence.size() - 1 && status >= 400) {
                    log.info("T4-CU: probe setup request {} was refused with {} — criterion not judged",
                            resolved.get(), status);
                    return SequenceOutcome.none(Unobserved.SETUP_REFUSED);
                }
            } catch (IOException e) {
                log.info("T4-CU: probe request {} got no answer ({}) — criterion not judged", r, e.getMessage());
                return SequenceOutcome.none(Unobserved.TRANSPORT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return SequenceOutcome.none(Unobserved.TRANSPORT);
            }
        }
        return status < 0 ? SequenceOutcome.none(Unobserved.TRANSPORT) : SequenceOutcome.observed(status);
    }

    /** {@code $N.field} → that field of response N (1-based). Empty when any reference is unresolvable. */
    static Optional<CriterionProbeSynthesizer.ProbeRequest> resolveReferences(
            CriterionProbeSynthesizer.ProbeRequest request, List<String> priorBodies) {
        Optional<String> path = substituteReferences(request.path(), priorBodies);
        if (path.isEmpty()) return Optional.empty();
        String body = request.body();
        if (body != null) {
            Optional<String> substituted = substituteReferences(body, priorBodies);
            if (substituted.isEmpty()) return Optional.empty();
            body = substituted.get();
        }
        return Optional.of(new CriterionProbeSynthesizer.ProbeRequest(request.method(), path.get(), body));
    }

    private static final java.util.regex.Pattern REFERENCE =
            java.util.regex.Pattern.compile("\\$(\\d+)\\.([A-Za-z_][A-Za-z0-9_]*)");

    private static Optional<String> substituteReferences(String text, List<String> priorBodies) {
        if (text == null || text.indexOf('$') < 0) return Optional.ofNullable(text);
        java.util.regex.Matcher m = REFERENCE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int index = Integer.parseInt(m.group(1));
            if (index < 1 || index > priorBodies.size()) return Optional.empty();
            Optional<String> value = jsonField(priorBodies.get(index - 1), m.group(2));
            if (value.isEmpty()) return Optional.empty();
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(value.get()));
        }
        m.appendTail(out);
        return Optional.of(out.toString());
    }

    /** A top-level field of a JSON object, as its raw text. Empty when absent or unparseable. */
    static Optional<String> jsonField(String json, String field) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            com.fasterxml.jackson.databind.JsonNode value = node.get(field);
            if (value == null || value.isNull()) return Optional.empty();
            return Optional.of(value.asText());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private HttpRequest probeRequest(int port, CriterionProbeSynthesizer.ProbeRequest r) {
        HttpRequest.Builder builder = HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:" + port + r.path()))
                .timeout(Duration.ofMillis(requestTimeoutMs));
        if (r.body() == null || r.body().isBlank()) {
            builder.method(r.method(), HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(r.method(), HttpRequest.BodyPublishers.ofString(r.body(), StandardCharsets.UTF_8));
        }
        return builder.build();
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

    /**
     * Per-run tokens that make the same failure read as a different one every time it is observed:
     * an ISO timestamp, the JVM's pid after the log level, the servlet thread name, and the
     * ephemeral port. Stripped because this text becomes a WORK-ITEM DESCRIPTION.
     *
     * <p>Field defect this fixes (run {@code 2026-07-30_04-36-29}): the self-check observed the same
     * two unreachable endpoints twice, seven minutes apart, and injected <b>two ACTIVE twins</b> of
     * one goal. The findings were 1286 characters each and differed in about forty — the timestamp,
     * pid, thread name and port. That defeated every layer at once: the suppression guard that
     * compares a finding to the last one could never match, the T1-DE paraphrase filter saw
     * different text, and T1-FG's per-gap idempotency keys on the description. So a defect the loop
     * had not yet fixed re-minted itself every interval and inflated the backlog — the one thing
     * that separates every good run from every bad one.
     */
    private static final java.util.regex.Pattern VOLATILE_LOG_TOKENS = java.util.regex.Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T[\\d:.]+(?:[+-]\\d{2}:\\d{2}|Z)?"   // ISO timestamp
                    + "|(?<=\\b(?:INFO|WARN|ERROR|DEBUG|TRACE)\\s)\\d+"      // pid after the level
                    + "|\\[[a-z0-9]+-\\d+-exec-\\d+\\]"                    // servlet thread name
                    + "|(?<=localhost:)\\d+|(?<=port\\s)\\d+");               // ephemeral port

    /**
     * Last diagnostic-looking lines of the process output, ANSI-stripped and freed of per-run
     * tokens — feeds a subgoal description, so the same failure must yield the same text.
     */
    static String summariseOutput(Deque<String> outputTail) {
        List<String> lines = outputTail.stream()
                .map(l -> BuildErrorClassifier.ANSI_ESCAPE.matcher(l).replaceAll("").strip())
                .map(l -> VOLATILE_LOG_TOKENS.matcher(l).replaceAll("").replaceAll("\\s{2,}", " ").strip())
                .filter(l -> !l.isBlank())
                .toList();
        if (lines.isEmpty()) return "no output before the observation ended";
        List<String> diagnostic = lines.stream()
                .filter(l -> l.contains("Exception") || l.contains("ERROR") || l.contains("Error")
                        || l.toLowerCase(Locale.ROOT).contains("caused by"))
                .toList();
        // When nothing in the output is diagnostic, SAY SO rather than attaching whatever the
        // process last printed. The old fallback to `lines` welded routine startup logging onto
        // work items whose defect is behavioural rather than a crash — there is no exception to
        // find, so the tail is just the boot chatter. Run 2026-07-30_21-25-43 carried 1119
        // characters of it inside one goal description ("Initializing Spring DispatcherServlet",
        // and stale 404 claims alongside), re-rendered into prompts 46 times: ~12.9k tokens of
        // text that was false by then and never actionable when written.
        //
        // "The process logged nothing unusual" is itself information, and it is three tokens.
        // Absence of a diagnostic line must not be reported as if the last three lines were one.
        if (diagnostic.isEmpty()) return "the process logged nothing diagnostic before the observation ended";
        return String.join(" | ", diagnostic.subList(Math.max(0, diagnostic.size() - SUMMARY_LINES), diagnostic.size()));
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
