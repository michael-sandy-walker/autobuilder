package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * T4-AN-2: test-run gate at the completion edge. Compile probes prove the code builds; the
 * T4-AN-8 package gate proves it assembles into a startable artifact; neither proves the
 * artifact's own tests pass. Ground truth (2026-06-12, the first verified-packageable
 * GOAL_FULFILLED artifact): its 4 test files compiled clean and 7+ of their tests FAILED at
 * runtime — invisible to every per-cycle mechanism, because tests are written but never run.
 *
 * <p>Same cost discipline as the package gate: runs ONCE per completion claim, never per-cycle.
 * Failure returns a blocker summary naming the failing tests; the caller injects it as a work
 * item and refuses completion — the 8-refusal package-gate convergence of 2026-06-12 17:06→18:00
 * is the field precedent that the loop can work such a gate to green.
 *
 * <p>Abstains (empty result) on: gate or build executor disabled, no tests present in the
 * project ("no tests to run" is not a failure — smoke-test authoring is separate work), and
 * TIMEOUT/SKIPPED outcomes (no answer is never treated as failure).
 *
 * <p><b>T4-AN-2b (2026-06-22): unenforced-test-requirement guard.</b> The abstain-on-no-tests
 * rule above is correct only when tests are <i>optional</i>. When the goal itself requires
 * automated tests, their absence is not a non-event — {@code mvn test} then succeeds
 * <i>vacuously</i> (nothing to run) and a test-required goal reaches GOAL_FULFILLED with zero
 * tests written (observed 2026-06-22: a run fulfilled the "provide tests for every endpoint" seed
 * with a real main artifact but 0 test files). When the caller signals {@code testsRequired}, the
 * gate treats missing test sources as a completion blocker. The discriminator is the presence of
 * test sources on disk (deterministic, no test-runner-output parsing); the requirement itself is
 * read from the goal spec via {@link #specRequiresTests(String)} using the generic notion of a
 * "test" (no test-framework names — those are ecosystem data, not framework vocabulary).
 */
@Slf4j
@Component
@com.walpro.autobuilder.core.checkpoint.StateHolder(
        value = com.walpro.autobuilder.core.checkpoint.StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "when the last test run started, to tell its reports from earlier ones")
public class TestRunGate {

    /**
     * Surefire/JUnit failure marker lines, e.g. {@code com.example.FooTest.bar()  ... <<< FAILURE!}.
     * Tolerates the runner's leading severity tag ({@code [ERROR] com.example.FooTest.bar ... <<< ERROR!})
     * — without it, run 6 (2026-07-03) injected work items with a bare tally and no failing-test
     * names, and the model churned blind for 6 gate blocks.
     */
    private static final Pattern FAILED_TEST_LINE =
            Pattern.compile("(?m)^\\s*(?:\\[[A-Z]+\\]\\s*)?([\\w.$]+(?:\\.[\\w$]+\\([^)]*\\))?)\\s+.*<<< (?:FAILURE|ERROR)!");

    private static final Pattern TESTS_RUN_SUMMARY =
            Pattern.compile("Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+)");

    private static final Pattern NO_TESTS = Pattern.compile("(?i)no tests to run|Tests run:\\s*0\\b");

    /** Generic "the spec asks for tests" signal — the word test(s), never a framework name. */
    private static final Pattern TEST_REQUIREMENT = Pattern.compile("(?i)\\btests?\\b");

    private static final int MAX_NAMED_FAILURES = 5;

    /**
     * Per-test report locations of the configured test command, relative to the project root —
     * report-location data tied to {@code test-gate.command}'s default, not framework vocabulary
     * (rows belong to the T4-BM discovery inventory alongside the command itself). The second
     * row is the other common JVM build's location for the same per-class XML format, so a project
     * built that way still gives the completion verifier its test run (T4-CQ); the first that holds
     * a report of this run wins.
     */
    private static final List<String> TEST_REPORT_DIRS = List.of("target/surefire-reports", "build/test-results/test");

    private static final int MAX_ROOT_CAUSES = 3;
    private static final int MAX_CAUSE_LENGTH = 300;

    private final boolean enabled;
    private final String testCommand;
    private final BuildExecutor buildExecutor;

    @Value("${file.base-path}")
    @Setter
    private String fileBasePath;

    /** T5-N: optional behaviour trace — emits {@code build_fix_attempt} with source {@code test-gate}. */
    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    /** T4-CQ: when the last test run started; its reports are the ones written after this. */
    private volatile long lastRunStartedAt = -1L;

    /**
     * T4-CQ: the last run's per-test report, while it still describes the tree -- empty once any
     * source under {@code src} has changed since that run started, or when no run has left a
     * report. A reader given a stale report would be told the outcome of code that no longer exists.
     */
    public Optional<TestRunReport> freshReport() {
        long since = lastRunStartedAt;
        if (since < 0) return Optional.empty();
        Path out = Path.of(fileBasePath, "output");
        try (Stream<Path> tree = Files.walk(out.resolve("src"))) {
            boolean changed = tree.filter(Files::isRegularFile).anyMatch(p -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis() > since;
                } catch (Exception e) {
                    return true;
                }
            });
            if (changed) return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
        for (String dir : TEST_REPORT_DIRS) {
            Optional<TestRunReport> r = SurefireReports.read(out.resolve(dir), since);
            if (r.isPresent()) return r;
        }
        return Optional.empty();
    }

    /** T4-CQ: whether a closure check can take this gate's verdict -- it abstains when off. */
    public boolean isLive() {
        return enabled && buildExecutor.isEnabled();
    }

    @Autowired
    public TestRunGate(
            @Value("${autobuilder.build.test-gate.enabled:true}") boolean enabled,
            @Value("${autobuilder.build.test-gate.command:mvn test}") String testCommand,
            BuildExecutor buildExecutor) {
        this.enabled = enabled;
        this.testCommand = testCommand;
        this.buildExecutor = buildExecutor;
    }

    /**
     * Runs the test command once. Returns a short failure summary naming failing tests when
     * the suite fails (→ caller refuses completion and injects it as a work item), or empty
     * when the suite passes, the project has no tests, or no answer could be obtained.
     */
    public Optional<String> verifyTestsPass(String cycleId) {
        return verifyTestsPass(cycleId, false);
    }

    /**
     * As {@link #verifyTestsPass(String)}, but when {@code testsRequired} is true the absence of
     * any test sources is itself a blocker (the goal asked for tests; none were authored), instead
     * of the vacuous {@code mvn test} success that would otherwise let completion through.
     */
    public Optional<String> verifyTestsPass(String cycleId, boolean testsRequired) {
        if (!enabled || !buildExecutor.isEnabled()) {
            return Optional.empty();
        }
        // T4-AN-2b: a test-required goal with no test sources must not pass on a vacuous build.
        if (testsRequired && !testSourcesPresent()) {
            log.info("T4-AN-2: goal requires automated tests but no test sources are present — blocking completion");
            return Optional.of("the goal requires automated tests but none were authored "
                    + "(no test sources found under src/test) — write the tests the goal calls for");
        }
        long startedAt = System.currentTimeMillis();
        BuildOutcome outcome = buildExecutor.execute(fileBasePath, cycleId, testCommand);
        lastRunStartedAt = startedAt;
        log.info("T4-AN-2: test gate result={} (durationMs={})", outcome.result(), outcome.durationMs());
        if (behaviourLogger != null) {
            behaviourLogger.buildFixAttempt(cycleId, "test-gate", 0, null, null,
                    null, List.of(), outcome.result().name(), outcome.durationMs());
        }
        if (outcome.result() != BuildResult.COMPILE_ERROR) {
            return Optional.empty();
        }
        String plain = BuildErrorClassifier.ANSI_ESCAPE.matcher(
                outcome.stderrTail() == null ? "" : outcome.stderrTail()).replaceAll("");
        if (NO_TESTS.matcher(plain).find() && !plain.contains("<<< FAILURE!") && !plain.contains("<<< ERROR!")) {
            log.info("T4-AN-2: test command failed but no tests ran — abstaining (not a test failure)");
            return Optional.empty();
        }
        String summary = summarise(plain);
        // The stderr tail carries names + tally but rarely the failure's root cause (run 6,
        // 2026-07-03: 'Errors: 4' reached the work item while 'No qualifying bean of type
        // BookRepository' — the repairable fact — sat only in the per-test reports; the model
        // churned blind to a T2-CB halt). Surface the deepest recorded cause per failing test.
        List<String> causes = rootCausesFromReports();
        if (!causes.isEmpty()) {
            summary += "; root cause(s): " + String.join(" | ", causes);
        }
        return Optional.of(summary);
    }

    /**
     * Deepest {@code Caused by:} line per test-report file (the root of each failure's chain),
     * deduplicated across tests, capped at {@value #MAX_ROOT_CAUSES} distinct causes. Empty when
     * no reports exist (e.g. the failure happened before any test ran) — never blocks on a read
     * error.
     */
    private List<String> rootCausesFromReports() {
        List<String> causes = new ArrayList<>();
        for (String dir : TEST_REPORT_DIRS) {
            Path reportDir = Path.of(fileBasePath, "output").resolve(dir);
            if (!Files.isDirectory(reportDir)) continue;
            try (Stream<Path> files = Files.list(reportDir)) {
                files.filter(p -> p.toString().endsWith(".txt")).sorted().forEach(report -> {
                    try {
                        String deepest = null;
                        for (String line : Files.readAllLines(report)) {
                            String t = line.strip();
                            if (t.startsWith("Caused by:")) {
                                deepest = t.substring("Caused by:".length()).strip();
                            }
                        }
                        if (deepest != null) {
                            if (deepest.length() > MAX_CAUSE_LENGTH) {
                                deepest = deepest.substring(0, MAX_CAUSE_LENGTH) + "…";
                            }
                            if (!causes.contains(deepest)) {
                                causes.add(deepest);
                            }
                        }
                    } catch (IOException e) {
                        log.debug("T4-AN-2: could not read test report {} ({})", report, e.getMessage());
                    }
                });
            } catch (IOException e) {
                log.debug("T4-AN-2: could not list test reports in {} ({})", reportDir, e.getMessage());
            }
        }
        return causes.size() > MAX_ROOT_CAUSES ? causes.subList(0, MAX_ROOT_CAUSES) : causes;
    }

    /**
     * True when the goal spec calls for automated tests. Generic by design — it matches the word
     * "test"/"tests", never a test-framework name (ecosystem data lives in tables, not framework
     * code). Conservative direction: if a spec mentions tests at all, requiring them errs toward an
     * honest "tests missing" non-completion rather than a vacuous pass.
     */
    public static boolean specRequiresTests(String spec) {
        return spec != null && TEST_REQUIREMENT.matcher(spec).find();
    }

    /** True when at least one {@code .java} file lives under a {@code src/test} dir in the project. */
    private boolean testSourcesPresent() {
        Path projectRoot = Path.of(fileBasePath, "output");
        if (!Files.isDirectory(projectRoot)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            return walk.anyMatch(p -> {
                String s = p.toString().replace('\\', '/');
                return s.endsWith(".java") && s.contains("/src/test/");
            });
        } catch (IOException e) {
            // Fail safe: a scan error must not block an otherwise-clean completion.
            log.debug("T4-AN-2: test-source scan failed ({}) — treating as present", e.getMessage());
            return true;
        }
    }

    /** Failing-test names (capped) + run summary — feeds a subgoal description. */
    private static String summarise(String plainOutput) {
        List<String> failed = new ArrayList<>();
        Matcher m = FAILED_TEST_LINE.matcher(plainOutput);
        while (m.find() && failed.size() < MAX_NAMED_FAILURES) {
            failed.add(m.group(1));
        }
        Matcher s = TESTS_RUN_SUMMARY.matcher(plainOutput);
        String tally = null;
        while (s.find()) tally = s.group(0); // last summary line is the aggregate
        StringBuilder sb = new StringBuilder("test suite failing");
        if (tally != null) sb.append(" (").append(tally).append(")");
        if (!failed.isEmpty()) sb.append(": ").append(String.join(", ", failed));
        return sb.toString();
    }
}
