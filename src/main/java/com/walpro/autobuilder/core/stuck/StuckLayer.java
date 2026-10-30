package com.walpro.autobuilder.core.stuck;

import com.walpro.autobuilder.core.build.ProtectedPaths;
import com.walpro.autobuilder.core.build.StuckQuestions;
import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import com.walpro.autobuilder.protocol.PromptTokenBudget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T4-DY slice B: the diagnostic layer in the loop -- one home for "stuck", consulted before the next
 * move is made.
 *
 * <p>Detectors report {@link StuckObservation}s here and nothing else. When an observation becomes a
 * question ({@link StuckLedger#noticeAt}), the question is asked of STRUCTURE -- the current tree and
 * the adopted original ({@link ProtectedPaths#BASELINE_DIR}), the seed -- when the next evolution
 * prompt is built, and the answer goes into that prompt ({@link #promptBlock}). This is what the
 * deleted meta lane lacked (T4-AI): its output was never read by the move it was about.
 *
 * <p>Every question, answer, delivery and recurrence is a behaviour event and a line of the ledger
 * file ({@value #LEDGER_PATH}). A signature that comes back after its answer was delivered moves to
 * the next question. When no question is left, or an answer shows a requirement only the operator may
 * bind, the layer emits a {@code diagnosis_request} event: the operator's hand-off. It does NOT halt
 * the run -- the operator's decision for this slice is recorded on T4-DY; the existing brakes keep
 * their own halts.
 */
@Slf4j
@Component
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "per-run stuck ledger; a restored run starts it afresh, as GrindDetector's counts")
public class StuckLayer {

    public static final String LEDGER_PATH = "meta/stuck-ledger.json";

    /** At most this many answers in one prompt, most recently delivered first. */
    static final int PROMPT_ANSWERS = 5;

    private final StuckLedger ledger = new StuckLedger();

    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    @Autowired(required = false)
    private FileService fileService;

    /** T4-EC slice 1: the project's seed, as the seed authority and as the key earlier runs' priors are under; optional. */
    @Autowired(required = false)
    private com.walpro.autobuilder.goal.purpose.PurposeService purposeService;

    /** Which class a type name denotes: the JDK and the project's own classpath; optional (JDK only without it). */
    @Autowired(required = false)
    private ProjectTypeAuthority projectTypeAuthority;

    /** T4-DZ: the observation authority, asked after structure; optional. */
    @Autowired(required = false)
    private TerminalAgent terminalAgent;

    @Autowired(required = false)
    private TerminalAgentRecorder terminalAgentRecorder;

    /** T4-CT: what earlier runs of the same task learned about a signature, and where this run's outcomes are kept; optional. */
    @Autowired(required = false)
    private StuckPriors priors;

    /** The test gate, whose failing test the execution trace re-runs; optional. */
    @Autowired(required = false)
    private com.walpro.autobuilder.core.build.TestRunGate testRunGate;

    /** The last failed change per signature -- rolled back in the tree, needed by the observer's copy. Memory only. */
    private final Map<String, Map<String, String>> lastChange = new java.util.concurrent.ConcurrentHashMap<>();

    private String currentCycle;
    private String currentSubGoal;
    private boolean ledgerFileStarted;
    private Map<String, String> adoptedSources;

    /** The cycle and subgoal observations without their own are attributed to. */
    public synchronized void beginCycle(String cycleId, String subGoalId) {
        currentCycle = cycleId;
        currentSubGoal = subGoalId;
        if (!ledgerFileStarted) {
            ledgerFileStarted = true;
            writeLedger(); // a run's ledger file never shows the previous run's entries
        }
    }

    /** Reports one observation from a detector. */
    public void report(StuckObservation.Source source, String signature, List<String> detail) {
        StuckObservation o;
        synchronized (this) {
            o = new StuckObservation(source, signature, currentSubGoal, currentCycle, detail);
        }
        report(o);
    }

    public void report(StuckObservation o) {
        consultPriors(o);
        StuckLedger.Change change = ledger.observe(o);
        if (change == StuckLedger.Change.RECURRED_AFTER_DELIVERY) {
            StuckLedger.Entry e = find(o);
            if (e != null) recordOutcome(e, StuckPriors.Outcome.RECURRED, o.cycleId());
            log.warn("T4-DY: '{}' came back after its answer was delivered ({} time(s)) -- the next question is due",
                    o.signature(), e == null ? 0 : e.recurrencesAfterDelivery());
            if (behaviourLogger != null && e != null) {
                behaviourLogger.stuckRecurred(o.cycleId(), o.source().name(), o.signature(),
                        e.recurrencesAfterDelivery(), e.rung());
            }
        }
        // A brake reports once its window is spent: nothing here can answer it, so the operator is
        // asked at once rather than at the next evolution prompt, which may never come.
        if (StuckDiagnosis.questionsFor(o.source()) == 0) {
            for (StuckLedger.Entry e : ledger.pending()) {
                if (e.source() == o.source() && e.signature().equals(o.signature()) && ledger.noQuestionLeft(e)) {
                    requestDiagnosis(e, "no authority in the loop answers this kind of stuck");
                }
            }
        }
        if (change != StuckLedger.Change.NONE && change != StuckLedger.Change.COUNTED) writeLedger();
    }

    /** Whether a source's answers are about the tree -- standing whatever subgoal is current -- rather than one subgoal. */
    static boolean aboutTheTree(StuckObservation.Source source) {
        return source == StuckObservation.Source.BUILD || source == StuckObservation.Source.TEST_FAILURES;
    }

    /** The suite passed: the answers to tests that kept failing have ended. */
    public void testsPassed(String cycleId) {
        List<StuckLedger.Entry> ended = ledger.testsPassed(cycleId);
        for (StuckLedger.Entry e : ended) {
            log.info("T4-DY: '{}' ended -- the suite passes since its answer was delivered in {}", e.signature(), e.deliveredCycle());
            recordOutcome(e, StuckPriors.Outcome.ENDED, cycleId);
            if (behaviourLogger != null) {
                behaviourLogger.stuckEnded(cycleId, e.source().name(), e.signature(), e.deliveredCycle(),
                        e.recurrencesAfterDelivery(), e.asked().size());
            }
        }
        if (!ended.isEmpty()) writeLedger();
    }

    /** Keeps the files of the change that failed with {@code signature}, for the observer's read-only copy. */
    public void rememberChange(String signature, Map<String, String> files) {
        if (signature != null && files != null && !files.isEmpty()) {
            lastChange.put(signature, Map.copyOf(files));
            lastFailedChange = Map.copyOf(files);
        }
    }

    /** The most recent change that failed to build, path -> content; empty before any. Memory only. */
    public Map<String, String> lastFailedChange() {
        return lastFailedChange;
    }

    private volatile Map<String, String> lastFailedChange = Map.of();

    /** A build compiled: the build signatures whose answer was delivered have ended. */
    public void buildSucceeded(String cycleId) {
        List<StuckLedger.Entry> ended = ledger.buildSucceeded(cycleId);
        for (StuckLedger.Entry e : ended) {
            log.info("T4-DY: '{}' ended after its answer was delivered in {} ({} recurrence(s) after delivery)",
                    e.signature(), e.deliveredCycle(), e.recurrencesAfterDelivery());
            recordOutcome(e, StuckPriors.Outcome.ENDED, cycleId);
            if (behaviourLogger != null) {
                behaviourLogger.stuckEnded(cycleId, e.source().name(), e.signature(), e.deliveredCycle(),
                        e.recurrencesAfterDelivery(), e.asked().size());
            }
        }
        if (!ended.isEmpty()) writeLedger();
    }

    /**
     * Asks every due question of the authorities, as the tree is now, and returns the block of
     * answers the evolution prompt carries; null when there is none. Called while the prompt is built,
     * so the answer is in the move it is about.
     */
    public String promptBlock(CycleContextData context) {
        return promptBlock(context, Integer.MAX_VALUE);
    }

    /**
     * As {@link #promptBlock(CycleContextData)}, within {@code maxTokens}: an answer that does not fit
     * is left out WHOLE and named as left out -- never cut, because half a declaration invites the
     * invention the answer exists to prevent.
     */
    public String promptBlock(CycleContextData context, int maxTokens) {
        String cycle = context == null ? null : context.cycleName();
        String subGoal = context == null ? null : context.currentSubGoalId();
        Map<String, String> live = context == null || context.previousOutput() == null ? Map.of() : context.previousOutput();
        StuckDiagnosis.Observer observer = terminalAgent == null || !terminalAgent.available() ? null
                : (signature, evidence) -> {
                    if (terminalAgentRecorder != null) terminalAgentRecorder.attribute(context);
                    return terminalAgent.ask(signature, evidence, live, lastChange.getOrDefault(signature, Map.of()), cycle);
                };
        StuckQuestions.TypeAuthority types = projectTypeAuthority == null || context == null || context.outputPath() == null
                ? StuckQuestions.TypeAuthority.platform()
                : projectTypeAuthority.forProject(java.nio.file.Path.of(context.outputPath()));
        StuckDiagnosis.Observer executor = !executionTrace || testRunGate == null || context == null || context.outputPath() == null
                ? null : (signature, evidence) -> traceFailingTest(signature, evidence, java.nio.file.Path.of(context.outputPath()), live);
        StuckDiagnosis.Authorities authorities = new StuckDiagnosis.Authorities(live, adoptedSources(), seed(), observer, types,
                executor);
        boolean changed = false;
        for (StuckLedger.Entry e : ledger.pending()) {
            changed = true;
            while (e.status() == StuckLedger.Status.PENDING) {
                var asked = StuckDiagnosis.ask(e.source(), e.signature(), e.detail(), e.rung(), authorities);
                if (asked.isEmpty()) {
                    if (ledger.noQuestionLeft(e)) requestDiagnosis(e, "every question was asked; none ended it");
                    break;
                }
                boolean operator = ledger.recordAsked(e, asked.get(), cycle, subGoal);
                if (behaviourLogger != null) {
                    behaviourLogger.stuckQuestionAsked(cycle, e.source().name(), e.signature(), e.rung(),
                            asked.get().question(), asked.get().authority(), asked.get().answered(),
                            asked.get().answered() ? asked.get().answer() : null, subGoal);
                }
                if (asked.get().answered()) {
                    log.warn("T4-DY: asked '{}' of {} for '{}' -- answer delivered to {}",
                            asked.get().question(), asked.get().authority(), e.signature(), subGoal);
                }
                if (operator) {
                    // A requirement only the operator may bind is not the model's to route around.
                    requestDiagnosis(e, asked.get().answered()
                            ? "the answer names a requirement only the operator may bind or release"
                            : "every question was asked; none ended it");
                }
            }
        }
        if (changed) writeLedger();
        // A build answer and an answer to failing tests are about the TREE and stand until a build compiles /
        // the suite passes; any other answer is bound to the subgoal it was delivered to, so it does not follow
        // the loop into unrelated work. Field batch 2026-10-02: a trace of a failing test was taken and never
        // shown -- the subgoal had moved on between the test run and the next prompt.
        List<StuckLedger.Entry> standing = ledger.delivered().stream()
                .filter(e -> aboutTheTree(e.source()) || e.subGoalId() == null || e.subGoalId().equals(subGoal))
                .toList();
        if (standing.isEmpty()) return correctedBlock(maxTokens, live, types);
        // T6-T: an answer left out of an earlier prompt is given room before one already read.
        standing = standing.stream().sorted(java.util.Comparator.comparing((StuckLedger.Entry e) -> !e.withheld())).toList();
        StringBuilder sb = new StringBuilder("--- ANSWERS TO WHAT KEPT FAILING (read from the sources, not advice) ---\n")
                .append("These failures repeated, so the loop looked up what the code and the specification actually say."
                        + " Write against these facts:\n");
        // One answer once, under every signature it answers: several failures in one file are often
        // answered by that one file, and showing it five times would crowd out the rest of the prompt.
        Map<String, List<StuckLedger.Entry>> byAnswer = new LinkedHashMap<>();
        java.util.Optional<java.util.Set<String>> failing = failingOnTree();
        for (StuckLedger.Entry e : standing) {
            String shown = shownAnswer(e, failing);
            if (shown == null) continue;   // every test it traced passes now: nothing of it is still true
            if (byAnswer.size() >= PROMPT_ANSWERS && !byAnswer.containsKey(shown)) {
                ledger.withheld(e, true);
                continue;
            }
            byAnswer.computeIfAbsent(shown, k -> new ArrayList<>()).add(e);
        }
        if (byAnswer.isEmpty()) return correctedBlock(maxTokens, live, types);
        int left = 0;
        boolean anyShown = false;
        for (Map.Entry<String, List<StuckLedger.Entry>> a : byAnswer.entrySet()) {
            StringBuilder one = new StringBuilder();
            for (StuckLedger.Entry e : a.getValue()) {
                one.append("  - ").append(e.signature()).append(" (").append(e.occurrences()).append(" times)\n");
                if (e.prior().seenBefore()) one.append("    ").append(provenance(e)).append('\n');
            }
            one.append("    ").append(a.getKey().replace("\n", "\n    ")).append('\n');
            if (PromptTokenBudget.estimateTokens(sb) + PromptTokenBudget.estimateTokens(one) > maxTokens) {
                left++;
                // Left out behind others: it is still owed, and is first in line next time. Left out with the
                // block still empty: it fits no prompt of this size at all, and holding the question for it
                // would hold it for ever -- that one counts as delivered, as before.
                boolean owed = anyShown;
                for (StuckLedger.Entry e : a.getValue()) ledger.withheld(e, owed);
                log.warn("T4-DY: an answer for '{}' did not fit this prompt ({} token(s) free) -- left out whole; {}",
                        a.getValue().get(0).signature(), maxTokens,
                        owed ? "it stays owed and goes first in the next prompt" : "it fits no prompt of this size; counted delivered");
                continue;
            }
            sb.append(one);
            anyShown = true;
            for (StuckLedger.Entry e : a.getValue()) ledger.withheld(e, false);
        }
        if (left > 0) sb.append("  (").append(left).append(" more answer(s) left out: no room in this prompt)\n");
        String corrected = correctedBlock(maxTokens - PromptTokenBudget.estimateTokens(sb), live, types);
        if (corrected != null) sb.append('\n').append(corrected);
        return sb.toString().stripTrailing();
    }

    /**
     * T6-T: the calls this run already wrote, was corrected on and stopped writing -- said before they are written
     * again, one line each, within {@code maxTokens}. Null when there is none, or no room for the first.
     *
     * <p><b>Only what is still true of the tree.</b> The register keeps what happened; whether "X has no m()"
     * holds is asked of the sources each time, never remembered. Field68 run 1 (2026-10-13, stopped for it): the
     * first version listed every corrected build failure, and one of them had been put right by ADDING the member
     * the ticket asks for -- the prompt then said of a method the code needs and has: "do not write it again".
     */
    String correctedBlock(int maxTokens, Map<String, String> live, StuckQuestions.TypeAuthority types) {
        List<String> lines = new ArrayList<>();
        for (StuckLedger.Entry e : ledger.corrected()) {
            var now = StuckQuestions.answer(e.signature(), live, types == null ? StuckQuestions.TypeAuthority.platform() : types);
            if (now.isEmpty() || now.get().kind() != StuckQuestions.Kind.MEMBERS || now.get().member() == null) continue;
            String member = now.get().member();
            if (now.get().declared().stream().anyMatch(d -> d.startsWith(member + "("))) continue;   // it is declared now
            lines.add("  - " + now.get().type() + " has no " + member + "()"
                    + (now.get().meant() == null ? "" : " -- it declares " + now.get().meant()) + '\n');
        }
        if (lines.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("--- CORRECTED EARLIER IN THIS RUN ---\n")
                .append("Each of these calls was written in this run, failed the build, and was put right. The type still"
                        + " does not declare it; do not write it again:\n");
        int shown = 0;
        for (String line : lines) {
            if (PromptTokenBudget.estimateTokens(sb) + PromptTokenBudget.estimateTokens(line) > maxTokens) break;
            sb.append(line);
            shown++;
        }
        return shown == 0 ? null : sb.toString().stripTrailing();
    }

    private static final java.util.regex.Pattern TRACED_TEST = java.util.regex.Pattern.compile("^The failing test ([\\w$]+\\.[\\w$]+) was run again under the debugger");

    /**
     * What an entry's standing answer says in a prompt. For tests that keep failing it is every trace the entry
     * took whose test still fails on the tree, the latest first -- a trace of a test that passes now describes code
     * that has since been put right, and is left out (T6-T: field61's last 25 minutes showed the trace of a test
     * that had passed since minute 93, and two candidates broke that test again). Null when nothing is left.
     */
    static String shownAnswer(StuckLedger.Entry e, java.util.Optional<java.util.Set<String>> failingOnTree) {
        if (e.source() != StuckObservation.Source.TEST_FAILURES) return e.deliveredAnswer();
        List<String> traces = new ArrayList<>();
        List<StuckLedger.Question> asked = e.asked();
        for (int i = asked.size() - 1; i >= 0; i--) {
            String answer = asked.get(i).answer();
            if (answer == null) continue;
            java.util.regex.Matcher m = TRACED_TEST.matcher(answer);
            if (m.find() && failingOnTree.isPresent() && !failingOnTree.get().contains(m.group(1))) continue;
            if (!traces.contains(answer)) traces.add(answer);
        }
        return traces.isEmpty() ? null : String.join("\n", traces);
    }

    /** A second reading for the same subgoal joins the first; neither replaces the other. */
    private void requestDiagnosis(StuckLedger.Entry e, String why) {
        List<String> asked = new ArrayList<>();
        for (StuckLedger.Question q : e.asked()) {
            asked.add(q.question() + " -> " + (q.answer() == null ? "no answer from " + q.authority()
                    : "answered by " + q.authority()));
        }
        log.warn("T4-DY: DIAGNOSIS REQUEST -- '{}' ({}, {} time(s)): {}", e.signature(), e.source(), e.occurrences(), why);
        if (e.status() == StuckLedger.Status.UNANSWERED) recordOutcome(e, StuckPriors.Outcome.UNANSWERED, e.lastCycle());
        if (behaviourLogger != null) {
            behaviourLogger.diagnosisRequest(e.lastCycle(), e.source().name(), e.signature(), e.occurrences(),
                    e.firstCycle(), why, asked, e.detail());
        }
        writeLedger();
    }

    /**
     * T4-CT: before a signature's first observation, what earlier runs of this task recorded for it. A
     * signature they noticed is primed: noticed at this first sighting, starting with the question that
     * ended it there. Read once per signature per run.
     */
    private void consultPriors(StuckObservation o) {
        if (priors == null || o == null || o.source() == null || o.signature() == null) return;
        if (ledger.has(o.source(), o.signature())) return;
        String task = taskKey();
        if (task == null) return;
        StuckPriors.Prior prior = priors.priorFor(task, o.source().name(), o.signature());
        if (!prior.seenBefore()) return;
        ledger.prime(o.source(), o.signature(), prior);
        StuckLedger.Entry e = find(o);
        int rung = e == null ? 0 : e.rung();
        log.warn("T4-CT: '{}' was noticed in {} earlier run(s) of this task (ended by an answer in {}) -- asked at"
                + " its first sighting here, starting with question {}", o.signature(), prior.runs(), prior.endedRuns(), rung);
        if (behaviourLogger != null) {
            behaviourLogger.stuckPriorConsulted(o.cycleId(), o.source().name(), o.signature(), prior.runs(),
                    prior.endedRuns(), prior.endingRung(), rung);
        }
    }

    /** T4-CT: keeps how an entry turned out, for later runs of the task; the question is the last one asked. */
    private void recordOutcome(StuckLedger.Entry e, StuckPriors.Outcome outcome, String cycleId) {
        if (priors == null) return;
        String task = taskKey();
        if (task == null) return;
        List<StuckLedger.Question> asked = e.asked();
        StuckLedger.Question last = asked.isEmpty() ? null : asked.get(asked.size() - 1);
        priors.record(new StuckPriors.Recorded(task, e.source().name(), e.signature(),
                last == null ? e.rung() : last.rung(), last == null ? null : last.question(), outcome), cycleId);
    }

    /**
     * The provenance line under an answer whose signature earlier runs of the task had seen: a count, not
     * a verdict, and "the same question" only when the answer shown is to the question that ended it there.
     */
    static String provenance(StuckLedger.Entry e) {
        StuckPriors.Prior p = e.prior();
        List<StuckLedger.Question> asked = e.asked();
        Integer shown = null;
        for (int i = asked.size() - 1; i >= 0 && shown == null; i--) {
            if (asked.get(i).answer() != null) shown = asked.get(i).rung();
        }
        String ended = p.endedRuns() == 0 ? "nothing the loop looked up ended it there"
                : java.util.Objects.equals(shown, p.endingRung())
                ? "the answer to this same question ended it in " + p.endedRuns() + " of them"
                : "an answer the loop looked up ended it in " + p.endedRuns() + " of them";
        return "(this failure also came up in " + p.runs() + " earlier run(s) of this same task; " + ended + ")";
    }

    /** The user's decision 2026-10-01: a test that keeps failing is re-run under the debugger (ExecutionTrace). */
    @org.springframework.beans.factory.annotation.Value("${autobuilder.stuck.execution-trace.enabled:true}")
    private boolean executionTrace = true;

    /** The time one trace may take: the build compiles first, then the one test runs under the debugger. */
    @org.springframework.beans.factory.annotation.Value("${autobuilder.stuck.execution-trace.budget-ms:240000}")
    private long executionTraceBudgetMs = 240_000;

    /** Most trace lines one answer carries. */
    static final int TRACE_LINES = 40;

    /**
     * T6-T: after the trace, the methods whose empty, false or null answer the failure turned on are walked line by
     * line ({@link StepTrace}) -- a second run of the test under the debugger, about as long as the first.
     */
    @org.springframework.beans.factory.annotation.Value("${autobuilder.stuck.step-trace.enabled:true}")
    private boolean stepTrace = true;

    /** Most walked lines one answer carries. */
    static final int STEP_LINES = 40;

    /** A failing test named by the stuck observation's evidence: {@code Class.method} and the message after " -- ". */
    record FailingTest(String testClass, String method, String message) {}

    /**
     * The first failing test the evidence names -- {@code TestRunGate.failingDetail}'s lines, "Class.method -- ... --
     * message" -- which stays right however the tree changed since; a FRESH report would not. The field batch of
     * 2026-10-02 asked the question 33 ms after the observation and got nothing: the merge after the test run had
     * made the report stale, so no trace was ever taken.
     */
    static java.util.Optional<FailingTest> failingTestIn(List<String> evidence) {
        List<FailingTest> all = failingTestsIn(evidence);
        return all.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(all.get(0));
    }

    /** Every failing test the evidence names, each once, in the evidence's order. */
    static List<FailingTest> failingTestsIn(List<String> evidence) {
        List<FailingTest> out = new ArrayList<>();
        if (evidence == null) return out;
        for (String line : evidence) {
            String[] parts = line.split(" -- ");
            String head = parts[0].strip();
            int dot = head.indexOf('.');
            if (dot <= 0) continue;
            String cls = head.substring(0, dot), method = head.substring(dot + 1).replaceAll("[(\\[].*$", "");
            if (!cls.matches("[A-Za-z_$][\\w$]*") || !method.matches("[A-Za-z_$][\\w$]*")) continue;
            if (out.stream().anyMatch(t -> t.testClass().equals(cls) && t.method().equals(method))) continue;
            out.add(new FailingTest(cls, method, parts.length > 1 ? parts[parts.length - 1].strip() : null));
        }
        return out;
    }

    /** T6-T: the tree each test was last traced on (a digest of the live sources), so the next trace goes to another. */
    private final Map<String, Integer> tracedOnTree = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong traceSeq = new java.util.concurrent.atomic.AtomicLong();
    private final Map<String, Long> tracedAt = new java.util.concurrent.ConcurrentHashMap<>();

    /** The tests failing on the tree as merged, as {@code Class.method}; empty Optional while that is not known. */
    java.util.Optional<java.util.Set<String>> failingOnTree() {
        if (testRunGate == null) return java.util.Optional.empty();
        return testRunGate.treeFailing().map(names -> {
            java.util.Set<String> out = new java.util.HashSet<>();
            for (String n : names) out.add(n.replaceAll("[(\\[].*$", ""));
            return out;
        });
    }

    /**
     * T6-T: the test the trace question is put to this time -- of the tests the evidence names that still fail on
     * the tree, one never traced; when each was, the one whose trace is of another tree than this and was taken
     * longest ago; none when every one was traced on this very tree (the question then has nothing new to say).
     */
    java.util.Optional<FailingTest> nextToTrace(List<String> evidence, Map<String, String> live) {
        java.util.Optional<java.util.Set<String>> failing = failingOnTree();
        int tree = live == null ? 0 : live.hashCode();
        FailingTest stale = null;
        for (FailingTest t : failingTestsIn(evidence)) {
            String name = t.testClass() + "." + t.method();
            if (failing.isPresent() && !failing.get().contains(name)) continue;
            Integer on = tracedOnTree.get(name);
            if (on == null) return java.util.Optional.of(t);
            if (on != tree && (stale == null || tracedAt.getOrDefault(name, 0L)
                    < tracedAt.getOrDefault(stale.testClass() + "." + stale.method(), 0L))) stale = t;
        }
        return java.util.Optional.ofNullable(stale);
    }

    /**
     * The failing test the observation names, run again under the debugger in a copy of {@code project}: the calls into
     * the project's own code, with their values.
     */
    private StuckDiagnosis.Asked traceFailingTest(String signature, List<String> evidence, java.nio.file.Path project,
                                                  Map<String, String> live) {
        var named = nextToTrace(evidence, live);
        if (named.isEmpty()) return new StuckDiagnosis.Asked("", "", null, false);
        FailingTest chosen = named.get();
        tracedOnTree.put(chosen.testClass() + "." + chosen.method(), live == null ? 0 : live.hashCode());
        tracedAt.put(chosen.testClass() + "." + chosen.method(), traceSeq.incrementAndGet());
        var calls = ExecutionTrace.trace(project, chosen.testClass(), chosen.method(), executionTraceBudgetMs);
        if (calls.isEmpty()) {
            log.info("T4-DY: no trace of {}.{} -- the run could not be attached to, or it called none of the project's code",
                    chosen.testClass(), chosen.method());
            return new StuckDiagnosis.Asked("", "", null, false);
        }
        String test = chosen.testClass() + "." + chosen.method();
        recordTrace(test, calls.get());
        String answer = "The failing test " + test + " was run again under the debugger. It failed with: "
                + (chosen.message() == null || chosen.message().isBlank() ? "(no message)" : chosen.message().replace('\n', ' '))
                + "\n    The calls into the project's own code, in the order they returned -- a call's own calls come before it, indented one step more:\n"
                + ExecutionTrace.render(calls.get(), live, TRACE_LINES).replace("\n", "\n    ")
                + walked(project, chosen, calls.get(), live);
        log.warn("T4-DY: traced {} under the debugger -- {} call(s) into the project's own code", test, calls.get().size());
        return new StuckDiagnosis.Asked("", "", answer, false);
    }

    /** T6-T: the deciding methods of a traced failure, walked line by line; empty when there are none or the walk failed. */
    private String walked(java.nio.file.Path project, FailingTest chosen, List<ExecutionTrace.Call> calls, Map<String, String> live) {
        if (!stepTrace) return "";
        List<String[]> deciding = StepTrace.decidingMethods(calls);
        if (deciding.isEmpty()) return "";
        var walks = StepTrace.walk(project, chosen.testClass(), chosen.method(), deciding, executionTraceBudgetMs);
        if (walks.isEmpty()) {
            log.info("T6-T: no walk of {} in {}.{}", deciding.stream().map(d -> d[1]).toList(), chosen.testClass(), chosen.method());
            return "";
        }
        log.warn("T6-T: walked {} invocation(s) of {} line by line", walks.get().size(), deciding.stream().map(d -> d[1]).toList());
        return "\n    Where the answer was empty, false or null, the method run again line by line:\n"
                + StepTrace.render(walks.get(), live, STEP_LINES).replace("\n", "\n    ");
    }

    /** T6-T: the calls each traced test made, by test, as last traced. */
    private final Map<String, List<ExecutionTrace.Call>> tracedCalls = new java.util.concurrent.ConcurrentHashMap<>();

    /** The calls the test {@code Class.method} made, as just traced. */
    void recordTrace(String test, List<ExecutionTrace.Call> calls) {
        tracedCalls.put(test, calls);
    }

    /**
     * T6-T: the methods of the project type named {@code simpleType} that the traces of the tests still failing on the
     * tree went through, in the order they returned, constructors left out; empty when none was traced.
     */
    public List<String> methodsOnFailingPath(String simpleType) {
        return tracedPath(false).getOrDefault(simpleType, List.of());
    }

    /**
     * T6-T: the project types the traces of the tests still failing on the tree went through (by simple name, a
     * nested type under its top-level one), each with its methods in the order they returned, constructors left out --
     * of each trace, only the types of the methods whose empty, false or null answer decided it
     * ({@link StepTrace#decidingMethods}), when it has any. Ladder-rows run 2026-10-16_04-11-22: every type the trace
     * went through brought the test's setup objects ({@code BuildExecutor}, {@code BuildOutcome}) into six prompts
     * beside the class the fault was in.
     */
    public Map<String, List<String>> failingPath() {
        return tracedPath(true);
    }

    private Map<String, List<String>> tracedPath(boolean decidingTypesOnly) {
        java.util.Optional<java.util.Set<String>> failing = failingOnTree();
        Map<String, java.util.LinkedHashSet<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<ExecutionTrace.Call>> t : tracedCalls.entrySet()) {
            if (failing.isPresent() && !failing.get().contains(t.getKey())) continue;
            java.util.Set<String> deciding = new java.util.HashSet<>();
            if (decidingTypesOnly) StepTrace.decidingMethods(t.getValue()).forEach(d -> deciding.add(topLevel(d[0])));
            for (ExecutionTrace.Call c : t.getValue()) {
                String type = topLevel(c.type());
                if (!deciding.isEmpty() && !deciding.contains(type)) continue;
                if (!c.method().startsWith("<")) out.computeIfAbsent(type, k -> new java.util.LinkedHashSet<>()).add(c.method());
            }
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        out.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return copy;
    }

    /** The simple name of a type's top-level type: {@code a.b.Outer$Inner} -> {@code Outer}. */
    private static String topLevel(String type) {
        String simple = type.substring(type.lastIndexOf('.') + 1);
        return simple.contains("$") ? simple.substring(0, simple.indexOf('$')) : simple;
    }

    StuckLedger.Entry find(StuckObservation o) {
        for (StuckLedger.Entry e : ledger.entries()) {
            if (e.source() == o.source() && e.signature().equals(o.signature())) return e;
        }
        return null;
    }

    /** The adopted text of every member-protected file, read once: a baseline is written once and never replaced. */
    private synchronized Map<String, String> adoptedSources() {
        if (adoptedSources != null) return adoptedSources;
        Map<String, String> out = new LinkedHashMap<>();
        try {
            if (fileService != null && fileService.exists(ProtectedPaths.BASELINE_DIR)) {
                for (String rel : fileService.listAllRelativeFilePathsUnder(ProtectedPaths.BASELINE_DIR)) {
                    out.put(rel, fileService.readString(ProtectedPaths.BASELINE_DIR + "/" + rel));
                }
            }
        } catch (Exception ex) {
            log.debug("T4-DY: adopted sources unreadable: {}", ex.getMessage());
        }
        adoptedSources = out;
        return out;
    }

    private String seed() {
        String spec = purposeService == null ? null : purposeService.specification();
        return spec == null ? "" : spec;
    }

    /** The key earlier runs of the same task stored their priors under: the digest of the seed's source text. */
    private String taskKey() {
        return seed().isBlank() ? null : purposeService.seedKey();
    }

    private void writeLedger() {
        if (fileService == null) return;
        try {
            fileService.writeJson(LEDGER_PATH, ledger.snapshot());
        } catch (Exception ex) {
            log.debug("T4-DY: ledger file not written: {}", ex.getMessage());
        }
    }

    /** The ledger, for tests and the run report. */
    public StuckLedger ledger() {
        return ledger;
    }
}
