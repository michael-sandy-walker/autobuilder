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
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import com.walpro.autobuilder.goal.ClaimRefutation;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Stream;

/**
 * T4-BV: execution-observation gate at the completion edge. The package gate proves the artifact
 * assembles; the test gate proves its own tests pass (and abstains when there are none — the
 * common case); NOTHING ever runs the artifact. Ground truth from the preserved corpus: false
 * completion #2 (2026-07-02) shipped a jar that died at launch, and the two fulfilled runs of
 * 2026-07-02/03 shipped jars whose spec-named endpoints were formally present but unreachable at
 * runtime — every one of those defects was found only by a manual boot-and-probe after the run.
 * This gate mechanizes that ritual: launch the packaged artifact once, verify it completes the
 * way its specification asks, probe the spec-declared parameterless GET endpoints live, and exercise the
 * spec-declared path-variable-free POST endpoints with synthesized bodies (T4-CB — the fourth
 * false-completion sighting fulfilled with a POST crashing on an id cast that no gate had ever
 * exercised; only a 5xx or connection death on a write blocks, never a 4xx).
 *
 * <p>Same cost discipline as the sibling gates: runs ONCE per completion claim, never per-cycle.
 * The process is always destroyed before returning; the launch happens on an ephemeral local
 * port, chosen here and handed to the artifact via the configured command template and/or
 * environment variable (which mechanism the target ecosystem honours is config data, not code).
 *
 * <p><b>Two completion models, and the gate does not assume one.</b> An artifact whose
 * specification names a served surface completes by becoming ready and STAYING UP; we tear it
 * down, so its exit status is ours rather than its own. An artifact whose specification names no
 * served surface completes by RUNNING AND TERMINATING; its exit status is the channel it reports
 * through. Which model applies is decided by what the specification asked for, before the exit
 * status is read — a terminating artifact is not a crashed one, and this gate has no expectation
 * to judge its status against (that expectation is a criterion, and criteria are still stated as
 * a response status; see T4-DD item 2).
 *
 * <p>Blocks (returns a work-item summary) only where the specification named a served surface:
 * when the process exits at startup, when it never serves HTTP, or when a spec-named probeable
 * endpoint answers 404/5xx at runtime. Abstains (empty) on: gate or build executor disabled, no
 * packaged artifact found, an artifact older than the sources (no fresh packaging observation —
 * never judge old code), and any outcome of an artifact whose spec names no served surface — no
 * observation is never treated as failure.
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

    /**
     * T4-DD: the operations a specification declares. Stateless and defaulted rather than
     * required, so the gate keeps working wherever it is constructed directly.
     */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.analysis.structure.CommandSurfaceQuery commandSurfaceQuery =
            new com.walpro.autobuilder.analysis.structure.CommandSurfaceQuery();

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
        return verifyBootsAndServesWithAttribution(cycleId, specText, behaviouralCriteria,
                allowModelFallback)
                .map(ClaimRefutation::description);
    }

    /**
     * T4-DW: what the gate POSITIVELY established, rather than only what it refuted.
     *
     * <p>Every prose overload above answers with the problem and {@code Optional.empty()} for
     * "clean" — and this class returns that same empty for eleven abstentions: no packaged
     * artifact, an artifact older than the sources, no free port, an unlaunchable command, a
     * process that could not be spawned, a long-running process the specification expects nothing
     * of. A caller that retires work on an empty is retiring it on "nothing was observed" as often
     * as on "nothing was wrong", which is the completion-edge error this project has already paid
     * for ([[project-false-completion-2]]).
     *
     * <p>{@code launchedAndServed} is set only on the path where the process started, answered
     * HTTP, and every endpoint the specification names was probed without a failure.
     * {@code criteriaObservedPassing} holds the acceptance criteria that were actually executed
     * against the running artifact and matched — never the ones that were skipped, unparsed or
     * left unobserved, which are about half of them in the field.
     */
    public record BootObservation(boolean launchedAndServed,
                                  List<String> criteriaObservedPassing,
                                  Optional<ClaimRefutation> refutation) {

        public static BootObservation abstained() {
            return new BootObservation(false, List.of(), Optional.empty());
        }

        /** True when the artifact was seen to run and serve what the specification names. */
        public boolean servesCleanly() {
            return launchedAndServed && refutation.isEmpty();
        }

        /** True when this criterion was executed against the running artifact and passed. */
        public boolean observedPassing(String criterion) {
            return criterion != null && criteriaObservedPassing.contains(criterion);
        }
    }

    /** Collects what the launch established; see {@link BootObservation}. */
    private static final class Evidence {
        private boolean launchedAndServed;
        private final List<String> passingCriteria = new ArrayList<>();
    }

    /**
     * T4-DW: run the gate and report what it established as well as what it refuted.
     *
     * <p>The three prose overloads and the attribution overload all delegate here, so there is one
     * launch path and one place that decides what "clean" means.
     */
    public BootObservation observeBootsAndServes(String cycleId, String specText,
                                                 List<String> behaviouralCriteria,
                                                 boolean allowModelFallback) {
        long t0 = System.nanoTime();
        try {
            return observeTimed(cycleId, specText, behaviouralCriteria, allowModelFallback);
        } finally {
            if (buildCostTracker != null) {
                buildCostTracker.record(cycleId, "boot-gate", (System.nanoTime() - t0) / 1_000_000L);
            }
        }
    }

    /**
     * T4-CU: as {@link #verifyBootsAndServes(String, String, List, boolean)}, but keeping WHICH
     * criteria the probes refuted instead of only the summary sentence.
     *
     * <p><b>Why this shares the {@code verify} verb rather than introducing a second one.</b>
     * Every gate in this package spells the question {@code verifyX} and answers it with the
     * PROBLEM — empty means clean ({@code verifyPackageable}, {@code verifyTestsPass},
     * {@code verifyDeclaredEntryPoint}, and this method's own three prose overloads: ten methods
     * across three gates). A method here named for what it returns instead would leave one class
     * holding two verbs for one operation, and the prose overload above would read as delegating
     * to a different question rather than to a fuller answer of the same one. The suffix carries
     * the difference, which is attribution and nothing else.
     *
     * <p>The prose overloads map to {@link ClaimRefutation#description()}, so all three per-cycle
     * callers are unchanged and none of them learns about attribution. Only the closure edge reads
     * this, because only the closure edge has criteria of its own to repair.
     */
    public Optional<ClaimRefutation> verifyBootsAndServesWithAttribution(
            String cycleId, String specText, List<String> behaviouralCriteria,
            boolean allowModelFallback) {
        return observeBootsAndServes(cycleId, specText, behaviouralCriteria, allowModelFallback)
                .refutation();
    }

    private BootObservation observeTimed(String cycleId, String specText,
                                                       List<String> behaviouralCriteria,
                                                       boolean allowModelFallback) {
        Evidence evidence = new Evidence();
        Optional<ClaimRefutation> refutation =
                verifyBootsAndServesTimed(cycleId, specText, behaviouralCriteria,
                        allowModelFallback, evidence);
        return new BootObservation(evidence.launchedAndServed && refutation.isEmpty(),
                List.copyOf(evidence.passingCriteria), refutation);
    }

    private Optional<ClaimRefutation> verifyBootsAndServesTimed(String cycleId, String specText,
                                                       List<String> behaviouralCriteria,
                                                       boolean allowModelFallback,
                                                       Evidence evidence) {
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
        Optional<ClaimRefutation> failure = launchAndObserve(artifact.get(), declared, workingDir,
                specText, behaviouralCriteria, allowModelFallback, evidence);
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
    private Optional<ClaimRefutation> launchAndObserve(Path artifact, Set<EndpointSurfaceQuery.Endpoint> declared,
                                              Path workingDir, String specText,
                                              List<String> behaviouralCriteria,
                                              boolean allowModelFallback,
                                              Evidence evidence) {
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
                // T4-DD item 3: which COMPLETION MODEL applies is asked once, before the exit
                // status is read at all. An artifact whose specification names a served surface
                // is expected to become ready and stay up, so terminating — cleanly or not — is
                // a failure to do the thing asked of it. An artifact whose specification names
                // no served surface is expected to run and TERMINATE, so its exit status is the
                // channel it reports through, not evidence that it failed to start. This gate
                // holds no expectation to compare such a status against, so it has no opinion.
                //
                // Asking in the other order is the assumption this fixes: the clean-exit branch
                // already asked the question and the non-zero branch never did, so an artifact
                // that terminated by design was reported as one that crashed at startup. On the
                // CLI target that misreading demanded repair 41 times at the closure edge and
                // spent 25 of 37 merges churning an artifact that had been finished since
                // merge 12. Note the asymmetry cost nothing on a served target — where the spec
                // names endpoints, every branch below is exactly what it was.
                if (declared.isEmpty()) {
                    // T1-GD: before asking what the status MEANS, ask whether the program ran.
                    //
                    // An archive the launcher cannot enter terminates non-zero without executing a
                    // line, and "exits non-zero on bad input" is a status a specification states
                    // routinely — so on this branch alone a criterion could be probed, observed to
                    // match, and recorded as holding against code that never started. Measured for
                    // nineteen minutes in run 2026-09-20_09-55-09: the status agreed and only a
                    // quoted-message assertion, which that artifact happened to carry, caught it.
                    //
                    // The serving branch has never had this hole — an artifact that cannot start
                    // never becomes ready, so its criteria are not reached — and its refusal is
                    // deliberately unattributed for the same reason this one is: a verdict the
                    // artifact did not earn belongs to no criterion.
                    //
                    // Asked of the ARCHIVE, not of the launcher's complaint, so no ecosystem's
                    // wording enters this class: {@link RunnableJarGuarantee#declaresEntryPoint}
                    // is the home for that question and answers "nothing is wrong" for anything
                    // it cannot open — a target launched some other way keeps its probes.
                    //
                    // ⚠ AND THE SECOND QUESTION IS WHAT MAKES THE FIRST SAFE. "Declares no entry
                    // point" is also true of a LIBRARY, which owes none. Refuting on the archive
                    // alone told a correct library to "declare the entry point the sources
                    // provide" — T4-DD item 3's own defect rebuilt one level up, demanding a
                    // completion model the specification never asked for, and the misreading that
                    // cost the CLI target 25 of 37 merges. So the project's own sources decide
                    // whether it is meant to be launched, through the same home that already
                    // asks it before offering the repair. Cheap question first: the manifest read
                    // short-circuits the tree walk on every healthy artifact.
                    if (!RunnableJarGuarantee.declaresEntryPoint(artifact)) {
                        // The PROBE abstains either way — a library is still an archive that did
                        // not run, so a verdict drawn from its exit status is still the
                        // launcher's. Only the DEMAND is conditional. Splitting it the other way
                        // round, so that a project owing no entry point fell through and was
                        // probed anyway, narrowed this defect instead of closing it.
                        if (!RunnableJarGuarantee.sourcesNameSoleEntryPoint(workingDir)) {
                            // Said out loud, at a level a field log shows. Falling through in
                            // silence made this indistinguishable from a healthy launchable
                            // artifact — the ambiguity that left T4-CU's own "never executed"
                            // unreadable for five runs, and the reason its third silence was
                            // promoted to INFO.
                            log.info("T1-GD: {} declares no entry point and the sources name no"
                                    + " single one — the criterion probe abstains, and this gate"
                                    + " demands nothing of a project that owes no entry point",
                                    artifact.getFileName());
                            return Optional.empty();
                        }
                        log.info("T1-GD: {} declares no entry point — the criterion probe abstains;"
                                + " no status it observed would be the criterion's own",
                                artifact.getFileName());
                        // Refuted rather than abstained-from, because empty here means "nothing
                        // contradicted the claim" and the closure would proceed on exactly the
                        // unearned pass this guard exists to stop.
                        //
                        // T4-DO (routing, 2026-09-21): this gate OBSERVES and does not repair.
                        // The deterministic packaging repair briefly lived on this line, which
                        // fixed its reachability and mistimed it: the self-check packages and then
                        // boots, so a pom written here lands seconds after a build and waits a
                        // whole interval (4 cycles) to be used. It is asked in PackageGate instead,
                        // immediately before the build that consumes it, where the package gate's
                        // own verifyDeclaredEntryPointExists() then checks the declaration against
                        // the build output rather than the repair certifying itself.
                        return Optional.of(ClaimRefutation.unattributed(
                                "the packaged artifact declares no entry point, so it cannot be"
                                + " launched and nothing the specification asks of it has been"
                                + " observed — declare the entry point the sources provide"));
                    }
                    // T4-DD item 2: the artifact terminated and its specification asked for no
                    // served surface, so THIS is the shape whose criteria state an exit status.
                    // Ask them. Item 3 alone left the gate with nothing to ask a terminating
                    // artifact — measured on run 2026-08-26_02-20-47, which fulfilled with
                    // criteria_executable_pct=0: correct output, and nothing in the loop checking it.
                    List<ProbeFailure> commandFailures = probeCommandCriteria(artifact, specText, behaviouralCriteria);
                    if (!commandFailures.isEmpty()) {
                        return Optional.of(new ClaimRefutation(
                                "the tool runs, but acceptance criteria fail when exercised"
                                + " against it: " + joinMessages(commandFailures),
                                attributedCriteria(commandFailures)));
                    }
                    return Optional.empty();
                }
                if (exit != 0) {
                    return Optional.of(ClaimRefutation.unattributed("the application exits at startup (exit " + exit + ") — "
                            + summariseOutput(outputTail, true)));
                }
                return Optional.of(ClaimRefutation.unattributed("the application exits immediately instead of staying up to serve"
                        + " the endpoints the goal specification names — " + summariseOutput(outputTail, true)));
            }
            if (!serving) {
                if (!declared.isEmpty()) {
                    return Optional.of(ClaimRefutation.unattributed("the application starts but never answers HTTP on its assigned port"
                            + " within " + readinessTimeoutMs / 1000 + "s, although the goal specification"
                            + " names endpoints — " + summariseOutput(outputTail)));
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
            List<ProbeFailure> criterionFailures = probeBehaviouralCriteria(allowModelFallback,
                    client, port, specText, behaviouralCriteria, workingDir, evidence);
            // T4-DW: the process started, answered HTTP, and every endpoint the specification
            // names was probed without a failure. This is the one path in this method that
            // establishes the claim a boot-gate work item makes, so it is the one path that may
            // retire one. Recorded BEFORE the criterion verdict below, because the endpoint claim
            // and the criterion claims are different claims with different evidence: a
            // contradicted criterion does not unsay "it runs and serves", and the observation's
            // own refutation field keeps the caller from acting on a run that was refuted.
            evidence.launchedAndServed = endpointFailures.isEmpty();
            if (!endpointFailures.isEmpty() || !criterionFailures.isEmpty()) {
                StringBuilder summary = new StringBuilder("the application runs, but ");
                if (!endpointFailures.isEmpty()) {
                    summary.append("spec-named endpoint(s) fail at runtime: ")
                            .append(String.join("; ", endpointFailures));
                }
                if (!criterionFailures.isEmpty()) {
                    if (!endpointFailures.isEmpty()) summary.append(". Also, ");
                    summary.append("acceptance criteria fail when exercised against it: ")
                            .append(joinMessages(criterionFailures));
                }
                if (!endpointFailures.isEmpty()) {
                    summary.append(" — ").append(summariseOutput(outputTail));
                }
                // T4-CU: attribute to criteria ONLY when the criterion probes are the whole story.
                // With an endpoint fault in the mix a probe's setup sequence fails for a reason
                // that is not the criterion's own — the comment above says exactly that — so
                // naming those criteria would point the repair at the symptom. Unattributed there,
                // and the consumer falls back to the whole-claim behaviour.
                return Optional.of(new ClaimRefutation(summary.toString(),
                        endpointFailures.isEmpty() ? attributedCriteria(criterionFailures) : List.of()));
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

    /**
     * T4-DD item 2: exercise the criteria that state an EXIT STATUS, the terminating artifact's
     * equivalent of {@link #probeBehaviouralCriteria}. Same discipline throughout: a criterion
     * that does not parse into one literal invocation and one expected status is not judged, and
     * an invocation that cannot be run produces no observation rather than a failure.
     *
     * <p>Note what is NOT here: no surface discovery, and no model. The corpus showed the
     * invocation is written into the criterion the same way an endpoint is
     * ({@code Running `<launch> unknown` … exits with code 3}), so reading it is a parser's job.
     */
    private List<ProbeFailure> probeCommandCriteria(Path artifact, String specText,
                                              List<String> behaviouralCriteria) {
        List<ProbeFailure> failures = new ArrayList<>();
        if (!criterionProbeEnabled || behaviouralCriteria == null || behaviouralCriteria.isEmpty()) {
            return failures;
        }
        List<String> launchTokens = CommandCriterionParser.launchTokensOf(commandTemplate);
        if (launchTokens.isEmpty()) return failures;

        int probed = 0;
        int unparsed = 0;
        int unobserved = 0;
        // T4-DT: counted apart from `probed`, which lumps every shape together. A run could report
        // probed=8 while no CONTENT criterion was among them, and the claim this mechanism exists
        // to check is precisely about content — so the field could not answer its own question.
        int valueProbed = 0;
        int valueContradicted = 0;
        // T4-DU: counted apart again, for the same reason the value probe was -- a figure that
        // pools shapes cannot answer whether THIS shape was judged.
        int messageProbed = 0;
        int messageContradicted = 0;
        for (String criterion : behaviouralCriteria) {
            if (failures.size() >= MAX_NAMED_FAILURES) break;
            Optional<CommandCriterionParser.CommandAssertion> parsed =
                    CommandCriterionParser.parse(criterion, launchTokens);
            if (parsed.isEmpty()) {
                unparsed++;
                continue;
            }
            CommandCriterionParser.CommandAssertion a = parsed.get();
            OptionalInt observed = invokeOnce(artifact, a.args());
            if (observed.isEmpty()) {
                unobserved++;
                continue;
            }
            probed++;
            if (observed.getAsInt() != a.expectedExitStatus()) {
                failures.add(ProbeFailure.of(a.criterionText(),
                        "\"" + a.criterionText() + "\" — " + a.describe()
                        + ", but running it exited " + observed.getAsInt()));
            }
        }

        // T4-DD: criteria that name a DECLARED OPERATION rather than a literal invocation — the
        // shape the field corpus is almost entirely made of. The surface supplies what the
        // criterion leaves implicit; the synthesizer declines anything needing the input's format.
        Map<String, com.walpro.autobuilder.analysis.structure.CommandSurfaceQuery.Command> surface =
                commandSurfaceQuery.declaredInSpec(specText);
        Optional<String> unrecognised = commandSurfaceQuery.unrecognisedWord(specText);
        // The specification's own worked example, which is what makes a fixture about an input's
        // CONTENT possible at all. Absent means those criteria abstain, as they did before.
        Optional<com.walpro.autobuilder.analysis.structure.InputFormatExample.Example> format =
                com.walpro.autobuilder.analysis.structure.InputFormatExample.readFrom(specText);
        Path fixtureDir = null;
        try {
            for (String criterion : behaviouralCriteria) {
                if (failures.size() >= MAX_NAMED_FAILURES) break;
                if (CommandCriterionParser.parse(criterion, launchTokens).isPresent()) continue;
                Optional<CommandExerciseSynthesizer.Exercise> ex =
                        CommandExerciseSynthesizer.synthesize(criterion, surface, unrecognised, format, specText);
                if (ex.isEmpty()) continue;
                CommandExerciseSynthesizer.Exercise e = ex.get();
                if (fixtureDir == null) fixtureDir = Files.createTempDirectory("boot-gate-fixture");
                Optional<Grounded> grounded = ground(e, fixtureDir, format);
                if (grounded.isEmpty()) {
                    unobserved++;
                    continue;
                }
                Optional<Invocation> observed = invoke(artifact, grounded.get().args());
                if (observed.isEmpty()) {
                    unobserved++;
                    continue;
                }
                unparsed--;   // it did not parse as a literal invocation, but it WAS exercised
                probed++;
                // T4-DU: an exercise built from a criterion that states no status asserts none.
                // Comparing against the record's default 0 would refuse `check` on a malformed file
                // for exiting 2 -- the specification's own answer -- which is T1-FY's defect rebuilt.
                if (e.assertsExitStatus() && observed.get().exitStatus() != e.expectedExitStatus()) {
                    failures.add(ProbeFailure.of(e.criterionText(),
                            "\"" + e.criterionText() + "\" — " + e.describe()
                            + ", but running it exited " + observed.get().exitStatus()));
                    continue;
                }
                // A right status with a wrong answer is the defect an exit code cannot see, so the
                // message is checked too — but only when the criterion quoted one AND its
                // placeholder can be grounded in the value actually passed.
                Optional<String> want = groundedMessage(e, grounded.get().args());
                if (want.isPresent() && !observed.get().output().contains(want.get())) {
                    failures.add(ProbeFailure.of(e.criterionText(),
                            "\"" + e.criterionText() + "\" — " + e.describe()
                            + ", and it exited " + observed.get().exitStatus()
                            + " but never said \"" + want.get() + "\""));
                    continue;
                }
                // T4-DU: a stated message carrying MORE THAN ONE placeholder is never substituted —
                // see groundedMessage for the walling that caused. But the fixture knows one thing
                // about it regardless of which placeholder means what: the line its bad row is on.
                // Asserting that the tool MENTIONS that number needs no opinion about the words
                // around it, so no target vocabulary enters this class
                // ([[feedback-no-target-domain-in-framework-strings]]).
                //
                // Applied ONLY where grounding already abstains, so no existing verdict can change.
                Optional<String> unmentioned = lineNumberNotMentioned(e, grounded.get(), observed.get());
                if (unmentioned.isPresent()) {
                    messageProbed++;
                    messageContradicted++;
                    failures.add(ProbeFailure.of(e.criterionText(), unmentioned.get()));
                    continue;
                }
                if (judgesLineNumber(e, grounded.get())) messageProbed++;
                // T4-DT: the value the SPECIFICATION says the tool should print for its own worked
                // example.
                //
                // ⚠ EQUALITY, NOT CONTAINMENT, and that is the whole detecting power here. The
                // artifact of run 2026-09-10_21-46-44 printed "b,c,de" where the seed states
                // "b,c" — and "b,c,de".contains("b,c") is TRUE, so a containment check would have
                // called the defect satisfied exactly as the source-reading verifier did.
                if (e.expectedStdout().isPresent() && observed.get().output() != null) valueProbed++;
                Optional<String> contradiction = e.contradictedBy(observed.get().output());
                if (contradiction.isPresent()) {
                    valueContradicted++;
                    failures.add(ProbeFailure.of(e.criterionText(), contradiction.get()));
                }
            }
        } catch (IOException io) {
            log.info("T4-DD: could not stage a fixture ({}) — those criteria are not judged", io.getMessage());
        } finally {
            deleteRecursively(fixtureDir);
        }
        log.info("T4-DD: exit-status criteria probed={} skipped={} (unparsed={} unobserved={}) contradicted={}",
                probed, unparsed + unobserved, unparsed, unobserved, failures.size());
        // Emitted even at zero, and that is the point: a zero here says "no content criterion
        // reached the probe on this closure", which is a different fact from the line being absent.
        // Silence that reads as good news is what left four watches blind until 2026-09-10.
        log.info("T4-DT: value criteria probed={} contradicted={}", valueProbed, valueContradicted);
        log.info("T4-DU: stated-message line numbers probed={} contradicted={}",
                messageProbed, messageContradicted);
        return failures;
    }

    /**
     * Run the artifact once with {@code args} and report the status it terminated with. Empty is
     * "no observation": the invocation could not be started, or it did not terminate within the
     * per-exercise bound — a tool that hangs has told us nothing about the criterion, and the
     * process is destroyed rather than left behind.
     */

    /**
     * T4-DU: whether this exercise can judge a stated message's line number at all.
     *
     * <p>True only when the specification quoted a message with more than one placeholder — the
     * case {@link #groundedMessage} refuses to substitute — and the fixture established which line
     * its bad row sits on. A single-placeholder message is already judged by substitution and must
     * keep that verdict.
     */
    private boolean judgesLineNumber(CommandExerciseSynthesizer.Exercise e, Grounded g) {
        if (g.malformedRowLine().isEmpty() || e.expectedMessage().isEmpty()) return false;
        Matcher m = MESSAGE_PLACEHOLDER.matcher(e.expectedMessage().get());
        int placeholders = 0;
        while (m.find()) placeholders++;
        return placeholders > 1;
    }

    /**
     * The contradiction when a tool prints the specification's message shape but never names the
     * line the fixture put the bad row on, or empty when there is none.
     *
     * <p><b>Containment of a bare number, deliberately, and leniently.</b> The point is to detect a
     * tool that reports a CONSTANT where the specification asks it to count — run
     * {@code 2026-09-11_16-27-02} printed {@code line 1} for every malformed row, at 17/19, twice
     * in two days. Formatting is not this rule's business: {@code line 6}, {@code line#6} and
     * {@code 6:} all satisfy it. Anything stricter would refuse correct tools over punctuation,
     * which is the failure mode this whole chain is built to avoid.
     */
    private Optional<String> lineNumberNotMentioned(CommandExerciseSynthesizer.Exercise e, Grounded g,
                                                    Invocation observed) {
        if (!judgesLineNumber(e, g)) return Optional.empty();
        String output = observed.output();
        if (output == null) return Optional.empty();

        // T4-DU, second pass: the ticket's filed DoD asked that EVERY placeholder be grounded in a
        // value the fixture established. That was first amended away as impossible without teaching
        // this class what <h> and <f> mean — and the amendment was wrong. The values do not have to
        // be MATCHED to placeholders to be required: the fixture establishes the bad row's line, the
        // header's width and that row's width, and a message with three placeholders that names all
        // three is satisfied without this class holding an opinion about which is which.
        //
        // Asserted only when the message has room for them all, so a two-placeholder message keeps
        // the weaker line-only verdict rather than being refused for a value it never claimed.
        List<Integer> required = g.knownValues();
        boolean namesThemAll = placeholderCount(e) >= required.size();
        for (int value : required) {
            if (!namesThemAll && value != g.malformedRowLine().orElse(Integer.MIN_VALUE)) continue;
            // Not a substring match: "6" must not be found inside "16" or "60".
            if (Pattern.compile("(?<!\\d)" + value + "(?!\\d)").matcher(output).find()) continue;
            return Optional.of("\"" + e.criterionText() + "\" — " + e.describe()
                    + ", and its fixture establishes " + value
                    + ", but nothing it printed names that: \"" + output.strip() + "\"");
        }
        return Optional.empty();
    }

    /** How many placeholders the criterion's quoted message carries. */
    private int placeholderCount(CommandExerciseSynthesizer.Exercise e) {
        if (e.expectedMessage().isEmpty()) return 0;
        Matcher m = MESSAGE_PLACEHOLDER.matcher(e.expectedMessage().get());
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /**
     * T4-DU: what the staged fixture established, beside the arguments it produced.
     *
     * <p>The probe writes the fixture, so it knows things about it that no amount of reading the
     * criterion can supply — here, which physical line the malformed row landed on. Before this,
     * grounding read values off {@code args} by POSITION, which is a guess: it is right only when
     * the exercise varied exactly one value and the message names that one. Carrying the fact
     * instead of re-deriving it is what lets a multi-placeholder message be judged at all.
     */
    private record Grounded(List<String> args, OptionalInt malformedRowLine,
                            OptionalInt headerWidth, OptionalInt malformedRowWidth) {

        /**
         * T4-DU: every number the staged fixture established, in no particular order and with no
         * claim about which placeholder names which. That ignorance is the point — deciding that
         * {@code <h>} means a header width would put one target's vocabulary in this class.
         */
        java.util.List<Integer> knownValues() {
            java.util.List<Integer> v = new ArrayList<>();
            malformedRowLine.ifPresent(v::add);
            headerWidth.ifPresent(v::add);
            malformedRowWidth.ifPresent(v::add);
            return v;
        }
    }

    /**
     * Put the exercise's fixture on disk and substitute its path. Empty when the fixture cannot be
     * staged — no observation rather than an invented argument.
     */
    private Optional<Grounded> ground(CommandExerciseSynthesizer.Exercise e, Path dir,
                                         Optional<com.walpro.autobuilder.analysis.structure
                                                 .InputFormatExample.Example> format) throws IOException {
        List<String> out = new ArrayList<>();
        OptionalInt malformedRowLine = OptionalInt.empty();
        OptionalInt headerWidth = OptionalInt.empty();
        OptionalInt malformedRowWidth = OptionalInt.empty();
        for (String a : e.args()) {
            if (a != null) { out.add(a); continue; }
            switch (e.fixture()) {
                case ABSENT_PATH -> out.add(dir.resolve("absent-" + System.nanoTime()).toString());
                case EMPTY_FILE -> out.add(Files.createFile(dir.resolve("empty-" + System.nanoTime())).toString());
                case WELL_FORMED_FILE -> {
                    if (format.isEmpty()) return Optional.empty();
                    var f = format.get();
                    // The header carries the width the specification states and none of the syntax
                    // the sample exercises: that disagreement is the whole detecting power, and a
                    // fixture repeating the sample as its own header has none.
                    out.add(write(dir, "wellformed", f.headerLine() + "\n" + f.sample() + "\n"));
                }
                case MALFORMED_FILE -> {
                    if (format.isEmpty()) return Optional.empty();
                    var f = format.get();
                    String sep = String.valueOf(f.separator());
                    String wide = String.join(sep, java.util.Collections.nCopies(f.trueParts() + 1, "x"));
                    String ok = String.join(sep, java.util.Collections.nCopies(f.trueParts(), "x"));
                    // T4-DU: the bad row is padded DOWN to a line whose number collides with nothing
                    // else the specification's own message can carry. The stated message names the
                    // header's width and the row's width; placing the row at trueParts + 3 keeps its
                    // line number distinct from both, so finding that number in the output means the
                    // tool counted lines rather than printed a constant. Same principle as the
                    // discriminating column: a fixture a careless implementation also gets right is
                    // coverage with no power to detect.
                    int line = f.trueParts() + 3;
                    StringBuilder content = new StringBuilder(f.headerLine()).append("\n");
                    for (int i = 2; i < line; i++) content.append(ok).append("\n");
                    content.append(wide).append("\n");
                    out.add(write(dir, "malformed", content.toString()));
                    malformedRowLine = OptionalInt.of(line);
                    headerWidth = OptionalInt.of(f.trueParts());
                    malformedRowWidth = OptionalInt.of(f.trueParts() + 1);
                }
                case NONE -> {
                    return Optional.empty();
                }
            }
        }
        if (e.column().isPresent()) {
            if (format.isEmpty()) return Optional.empty();
            var names = format.get().headerNames();
            switch (e.column().get()) {
                case PRESENT -> out.add(names.get(0));
                case ABSENT -> out.add(absentName(names));
                // T4-DT: the column holding the part that contains the separator. Any other column
                // is printed correctly by naive splitting too, so this is the only one whose value
                // separates a correct reader from a careless one.
                case DISCRIMINATING -> {
                    OptionalInt i = format.get().discriminatingPartIndex();
                    if (i.isEmpty()) return Optional.empty();
                    out.add(names.get(i.getAsInt()));
                }
            }
        }
        return Optional.of(new Grounded(List.copyOf(out), malformedRowLine,
                headerWidth, malformedRowWidth));
    }

    /** A name the header definitely does not carry, extended until it collides with nothing. */
    private static String absentName(List<String> present) {
        String candidate = present.isEmpty() ? "absent" : present.get(0) + "z";
        while (present.contains(candidate)) candidate = candidate + "z";
        return candidate;
    }

    private static String write(Path dir, String prefix, String content) throws IOException {
        Path f = dir.resolve(prefix + "-" + System.nanoTime());
        Files.writeString(f, content);
        return f.toString();
    }

    /**
     * The message the criterion quoted, with its single placeholder replaced by the value actually
     * passed. Empty when no message was quoted, or when the placeholder cannot be tied to an
     * argument — an ungrounded message would be compared against text the run never chose.
     */
    private Optional<String> groundedMessage(CommandExerciseSynthesizer.Exercise e, List<String> args) {
        if (e.expectedMessage().isEmpty()) return Optional.empty();
        String msg = e.expectedMessage().get();
        Matcher pm = MESSAGE_PLACEHOLDER.matcher(msg);
        List<int[]> spans = new ArrayList<>();
        while (pm.find()) spans.add(new int[]{pm.start(), pm.end()});
        if (spans.isEmpty()) return Optional.of(msg);
        // ⚠ MORE THAN ONE PLACEHOLDER MEANS THE MESSAGE DESCRIBES VALUES THIS EXERCISE NEVER
        // CHOSE, and substituting anyway manufactures a contradiction against a correct artifact.
        // Field defect, run 2026-08-26_14-53-24: a criterion reading "prints \"line <n>: expected
        // <h> fields, found <f>\"" had the FIXTURE PATH substituted into <n> — a line number —
        // while <h> and <f> stayed literal, and the resulting nonsense blocked closure six times
        // on an artifact that answered that criterion correctly. A single placeholder is the only
        // case where the value the exercise varied is unambiguously the one the message names.
        if (spans.size() != 1) return Optional.empty();
        int[] span = spans.get(0);
        String value = args.get(args.size() - 1);
        return Optional.of(msg.substring(0, span[0]) + value + msg.substring(span[1]));
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
        } catch (IOException ignored) {
            // a leftover temp directory is not worth failing a gate over
        }
    }

    /** What one invocation reported: the status it terminated with, and everything it wrote. */
    /** T4-CU: the reader-facing prose, unchanged in wording from before the carrier existed. */
    private static String joinMessages(List<ProbeFailure> failures) {
        return failures.stream().map(ProbeFailure::message).collect(java.util.stream.Collectors.joining("; "));
    }

    /** T4-CU: the distinct criteria these probes refuted, in encounter order, nulls dropped. */
    private static List<String> attributedCriteria(List<ProbeFailure> failures) {
        return failures.stream().map(ProbeFailure::criterionText).filter(java.util.Objects::nonNull)
                .distinct().toList();
    }

    private record Invocation(int exitStatus, String output) {}

    /**
     * T4-CU: one probe verdict, with the criterion it belongs to kept BESIDE the message rather
     * than only inside it. The message has always embedded the criterion text for a human reader;
     * what was missing was a field a caller could read without parsing prose, which is what left
     * the closure gate unable to name what it had refuted.
     *
     * {@code criterionText} is null for a failure that is not a criterion's own — an endpoint that
     * does not answer, an artifact that never boots.
     */
    private record ProbeFailure(String criterionText, String message) {
        static ProbeFailure of(String criterionText, String message) {
            return new ProbeFailure(criterionText, message);
        }
    }

    private OptionalInt invokeOnce(Path artifact, List<String> args) {
        return invoke(artifact, args).map(i -> OptionalInt.of(i.exitStatus())).orElse(OptionalInt.empty());
    }

    private Optional<Invocation> invoke(Path artifact, List<String> args) {
        List<String> command = new ArrayList<>(CommandCriterionParser.launchTokensOf(commandTemplate));
        command.add(artifact.toString());
        command.addAll(args);
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .directory(artifact.getParent().getParent().toFile())
                    .redirectErrorStream(true)
                    .start();
            Deque<String> tail = new ArrayDeque<>();
            Thread drainer = drainerThread(process.getInputStream(), tail);
            drainer.start();
            if (!process.waitFor(requestTimeoutMs, TimeUnit.MILLISECONDS)) {
                return Optional.empty();
            }
            drainer.join(1000);
            return Optional.of(new Invocation(process.exitValue(), String.join("\n", tail)));
        } catch (IOException e) {
            log.info("T4-DD: could not invoke '{}' ({}) — no observation", String.join(" ", command), e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
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
    private List<ProbeFailure> probeBehaviouralCriteria(boolean allowModelFallback,
                                                  HttpClient client, int port, String specText,
                                                  List<String> criteria, Path artifactRoot,
                                                  Evidence evidence) {
        List<ProbeFailure> failures = new ArrayList<>();
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
            List<CriterionProbeSynthesizer.ProbeRequest> requests = sequence.get();
            CriterionProbeSynthesizer.ProbeRequest asserted = requests.get(requests.size() - 1);
            // T4-DB: a success probe sends a body this side derived, and no derivation can be sure
            // it satisfies validation it never discovered. A client-error answer to THAT request
            // therefore means "the body was refused", which says nothing about the criterion — the
            // application may well create correctly from a body it likes. Reading it as a
            // contradiction would score a defect against a strict, correct artifact, which is the
            // objection that kept creates unprobed in the first place. A server error is left
            // alone: it is not a refusal, and the existing comparison already reports it.
            if (asserted.body() != null
                    && assertion.expectedStatus() < 300
                    && observed.status() >= 400 && observed.status() < 500) {
                log.info("T4-CU: probe body for {} was refused with {} — criterion not judged",
                        asserted, observed.status());
                unobserved.merge(Unobserved.BODY_REFUSED, 1, Integer::sum);
                continue;
            }
            // T1-FV: the guard above covers SUCCESS criteria only, and its reasoning — no
            // derivation can be sure it satisfies validation it never discovered — holds just as
            // well for a criterion asserting an error. Widening it outright would be wrong the
            // other way: a criterion expecting 404 answered 400 is sometimes a genuine
            // wrong-error-code finding, and blanket abstention would discard it. So ask instead
            // of assuming — resend carrying only what the criterion itself names. If the artefact
            // then answers as specified, the derived body was at fault and the criterion PASSES;
            // if it still does not, the contradiction is real and is reported against the fairer
            // request. Measured on run 2026-08-28_21-47-39, where this exact case cost a 23/23
            // artefact its only closure attempt.
            CriterionProbeSynthesizer.ProbeRequest judged = asserted;
            if (asserted.body() != null
                    && observed.status() != assertion.expectedStatus()
                    && observed.status() >= 400 && observed.status() < 500) {
                Optional<String> minimal = minimalBodyFor(assertion.criterionText(), asserted.body());
                // Say so when the opportunity was there and the reduction declined. Without this
                // the mechanism is silent in BOTH the "never engaged" and "engaged, declined"
                // cases, and run 2026-08-28_23-29-25 had to be told apart by reconstructing the
                // contradiction text offline. Measured on that corpus, the decline is the COMMON
                // path: criteria are usually phrased in summary ("if either not found") and name
                // no field, so there is nothing to keep. That is a real limit on this rule's
                // reach, and it should be visible in the run rather than rediscovered.
                if (minimal.isEmpty()) {
                    log.info("T1-FV: {} was refused with {} but the criterion names no field to "
                                    + "reduce to — judging the original request",
                            asserted, observed.status());
                }
                if (minimal.isPresent()) {
                    List<CriterionProbeSynthesizer.ProbeRequest> retry = new ArrayList<>(requests);
                    CriterionProbeSynthesizer.ProbeRequest reduced =
                            new CriterionProbeSynthesizer.ProbeRequest(
                                    asserted.method(), asserted.path(), minimal.get());
                    retry.set(retry.size() - 1, reduced);
                    SequenceOutcome second = executeSequence(client, port, retry);
                    // An unobserved retry proves nothing, so the first answer stands.
                    if (second.isObserved()) {
                        log.info("T1-FV: {} was refused with {}; resent as {} and got {} — "
                                        + "judging the reduced request",
                                asserted, observed.status(), reduced, second.status());
                        observed = second;
                        judged = reduced;
                    }
                }
            }
            probed++;
            if (observed.status() != assertion.expectedStatus()) {
                failures.add(ProbeFailure.of(assertion.criterionText(),
                        "\"" + assertion.criterionText() + "\" — " + assertion.describe()
                        + ", but the running application answered " + observed.status()
                        + " to " + judged));
            } else if (evidence != null) {
                // T4-DW: reached only after a completed sequence whose final status matched, so
                // this list holds criteria the artifact was actually asked and answered. Every
                // `continue` above — unparsed, unprobeable, setup refused, body refused, transport
                // failure — leaves the criterion OUT of it, which is the whole point: a criterion
                // that was skipped must not retire the work item that quotes it.
                evidence.passingCriteria.add(criterion);
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
     *
     * <p>{@code BODY_REFUSED} is the same distinction one step later: the ASSERTED request of a
     * success probe was declined, so the body was wrong rather than the endpoint. Counting it as a
     * contradiction would be the false positive that kept creates unprobed; counting it as probed
     * would claim an observation nobody made.
     */
    enum Unobserved { SETUP_REFUSED, UNRESOLVED_REFERENCE, TRANSPORT, BODY_REFUSED }

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

    /**
     * {@code body} reduced to the top-level fields the criterion's own text names.
     *
     * <p>Empty when the reduction would not settle anything: unparseable body, nothing named,
     * or nothing dropped. Emptiness therefore means "no second opinion available", and the
     * caller keeps the answer it already has.
     *
     * <p>Why this exists. A probe body is derived from the artefact's storage model, so it
     * carries components the interface never accepts — the shape {@code T1-FV} describes. On
     * run {@code 2026-08-28_21-47-39} the criterion was <em>"POST /loans with a non-existent
     * bookId or memberId returns 404"</em>; the derived body added {@code "returned":true} from
     * the {@code Loan} entity, the handler declared {@code Map<String, Long>}, the boolean could
     * not bind, and the artefact answered 400. It was recorded as the artefact failing the
     * requirement, and it alone cost a 23/23 run its only closure attempt. Sending
     * {@code {"bookId":…,"memberId":…}} to that same artefact returns exactly the specified 404.
     *
     * <p>The rule carries no knowledge of HTTP or of any domain: it keeps what the requirement
     * mentions and drops what only the storage model knows about. A field counts as named only
     * on a whole-token match, so {@code id} is not claimed by the text "bookId" — matching a
     * substring would keep the very components this is meant to drop.
     */
    static Optional<String> minimalBodyFor(String criterionText, String body) {
        if (criterionText == null || body == null || body.isBlank()) return Optional.empty();
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(body);
            if (!node.isObject()) return Optional.empty();
            String haystack = criterionText.toLowerCase(Locale.ROOT);
            com.fasterxml.jackson.databind.node.ObjectNode kept = mapper.createObjectNode();
            int dropped = 0;
            java.util.Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String field = names.next();
                if (namedIn(haystack, field)) {
                    kept.set(field, node.get(field));
                } else {
                    dropped++;
                }
            }
            if (dropped == 0 || kept.isEmpty()) return Optional.empty();
            return Optional.of(mapper.writeValueAsString(kept));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Whether {@code field} appears in already-lowercased {@code haystack} as a whole token. */
    private static boolean namedIn(String haystack, String field) {
        if (field == null || field.isBlank()) return false;
        Matcher m = Pattern.compile("(?<![a-z0-9])" + Pattern.quote(field.toLowerCase(Locale.ROOT))
                + "(?![a-z0-9])").matcher(haystack);
        return m.find();
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

    /** A component a quoted message describes rather than gives. */
    private static final Pattern MESSAGE_PLACEHOLDER = Pattern.compile("<[^>]+>");

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
        return summariseOutput(outputTail, false);
    }

    /**
     * @param failedToStart the process never reached a running state — its output is the reason,
     *                      not boot chatter, so keyword-free lines are still the best answer.
     */
    static String summariseOutput(Deque<String> outputTail, boolean failedToStart) {
        List<String> lines = outputTail.stream()
                .map(l -> BuildErrorClassifier.ANSI_ESCAPE.matcher(l).replaceAll("").strip())
                .map(l -> VOLATILE_LOG_TOKENS.matcher(l).replaceAll("").replaceAll("\\s{2,}", " ").strip())
                .filter(l -> !l.isBlank())
                .toList();
        if (lines.isEmpty()) return "no output before the observation ended";
        // T4-DN, corrected 2026-08-28 by run 2026-08-28_13-58-46: keyword filtering is wrong for a
        // process that never started, not merely insufficient. That run's artifact printed
        //     Description: Failed to configure a DataSource: 'url' attribute is not specified…
        //     Action: If you want an embedded database (H2, HSQL or Derby), please put it on the classpath.
        // — the cause AND its remedy. Neither line contains Exception/ERROR/caused by, so both were
        // dropped, while Spring's generic wrapper ("Exception encountered during context
        // initialization - cancelling refresh attempt") matched and won. The loop was handed that
        // wrapper 133 times and never learned what was wrong.
        //
        // A process that failed to start has no routine chatter to filter OUT — everything it
        // printed is about the failure. So the whole tail is the diagnostic, and keyword selection
        // only applies to a process that was running and logging normally.
        if (failedToStart) {
            return String.join(" | ", lines.subList(Math.max(0, lines.size() - SUMMARY_LINES), lines.size()));
        }
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
        // A RUNNING process that logged nothing diagnostic gets that stated plainly rather than
        // having its boot chatter welded onto a behavioural work item (run 2026-07-30_21-25-43:
        // 1119 characters of "Initializing Spring DispatcherServlet" re-rendered 46 times).
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
