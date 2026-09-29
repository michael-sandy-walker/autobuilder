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

    /** Which class a type name denotes: the JDK and the project's own classpath; optional (JDK only without it). */
    @Autowired(required = false)
    private ProjectTypeAuthority projectTypeAuthority;

    /** T4-DZ: the observation authority, asked after structure; optional. */
    @Autowired(required = false)
    private TerminalAgent terminalAgent;

    @Autowired(required = false)
    private TerminalAgentRecorder terminalAgentRecorder;

    /** T4-DY dossier: the model's move when no question is left; optional (no decider, no move). */
    @Autowired(required = false)
    private DossierDecider dossierDecider;

    /** T4-CI phase 3: the run's shape so far against healthy runs, one of the dossier's facts; optional. */
    @Autowired(required = false)
    private com.walpro.autobuilder.core.health.RunHealth runHealth;

    /** The last test run's per-test report, for the dossier's failing tests; optional. */
    @Autowired(required = false)
    private com.walpro.autobuilder.core.build.TestRunGate testRunGate;

    /** Criteria status and the DIVIDE actuator. A provider: the planner service reports into this layer. */
    @Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<com.walpro.autobuilder.goal.GoalPlannerService> goalPlannerService;

    /**
     * Diagnosis requests waiting for the dossier, each with why no question was left: decided at the next
     * cycle, where the context is at hand. Every request gets its own dossier -- one slot let the second of
     * two signatures exhausted in one cycle overwrite the first (field run 2026-09-28_18-29-08).
     */
    private final Map<StuckLedger.Entry, String> pendingDossiers = new LinkedHashMap<>();

    /** The moves the dossier chose, per signature, each with what followed -- what the next dossier is shown. */
    private final Map<String, List<String>> movesBySignature = new LinkedHashMap<>();

    /** A HINT (or a REPLAN's reason) per subgoal: shown to the next change's prompt as the model's reading. */
    private final Map<String, String> hintBySubGoal = new LinkedHashMap<>();

    /** The last failed change per signature -- rolled back in the tree, needed by the observer's copy. Memory only. */
    private final Map<String, Map<String, String>> lastChange = new java.util.concurrent.ConcurrentHashMap<>();

    private String currentCycle;
    private String currentSubGoal;
    private boolean ledgerFileStarted;
    private Map<String, String> adoptedSources;
    private String seed;

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
        StuckLedger.Change change = ledger.observe(o);
        if (change == StuckLedger.Change.RECURRED_AFTER_DELIVERY) {
            StuckLedger.Entry e = find(o);
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
                    requestDiagnosis(e, "no authority in the loop answers this kind of stuck", true);
                }
            }
        }
        if (change != StuckLedger.Change.NONE && change != StuckLedger.Change.COUNTED) writeLedger();
    }

    /** Keeps the files of the change that failed with {@code signature}, for the observer's read-only copy. */
    public void rememberChange(String signature, Map<String, String> files) {
        if (signature != null && files != null && !files.isEmpty()) lastChange.put(signature, Map.copyOf(files));
    }

    /** A build compiled: the build signatures whose answer was delivered have ended. */
    public void buildSucceeded(String cycleId) {
        List<StuckLedger.Entry> ended = ledger.buildSucceeded(cycleId);
        for (StuckLedger.Entry e : ended) {
            log.info("T4-DY: '{}' ended after its answer was delivered in {} ({} recurrence(s) after delivery)",
                    e.signature(), e.deliveredCycle(), e.recurrencesAfterDelivery());
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
        StuckDiagnosis.Authorities authorities = new StuckDiagnosis.Authorities(live, adoptedSources(), seed(), observer, types);
        boolean changed = false;
        for (StuckLedger.Entry e : ledger.pending()) {
            changed = true;
            while (e.status() == StuckLedger.Status.PENDING) {
                var asked = StuckDiagnosis.ask(e.source(), e.signature(), e.detail(), e.rung(), authorities);
                if (asked.isEmpty()) {
                    if (ledger.noQuestionLeft(e)) requestDiagnosis(e, "every question was asked; none ended it", true);
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
                            : "every question was asked; none ended it", !asked.get().answered());
                }
            }
        }
        if (changed) writeLedger();
        // A build answer is about the tree and stands until a build compiles; any other answer is bound
        // to the subgoal it was delivered to, so it does not follow the loop into unrelated work.
        List<StuckLedger.Entry> standing = ledger.delivered().stream()
                .filter(e -> e.source() == StuckObservation.Source.BUILD || e.subGoalId() == null
                        || e.subGoalId().equals(subGoal))
                .toList();
        String hint = hintBlock(subGoal);
        if (standing.isEmpty()) return hint;
        StringBuilder sb = new StringBuilder("--- ANSWERS TO WHAT KEPT FAILING (read from the sources, not advice) ---\n")
                .append("These failures repeated, so the loop looked up what the code and the specification actually say."
                        + " Write against these facts:\n");
        // One answer once, under every signature it answers: several failures in one file are often
        // answered by that one file, and showing it five times would crowd out the rest of the prompt.
        Map<String, List<StuckLedger.Entry>> byAnswer = new LinkedHashMap<>();
        for (StuckLedger.Entry e : standing) {
            if (byAnswer.size() >= PROMPT_ANSWERS && !byAnswer.containsKey(e.deliveredAnswer())) continue;
            byAnswer.computeIfAbsent(e.deliveredAnswer(), k -> new ArrayList<>()).add(e);
        }
        int left = 0;
        for (Map.Entry<String, List<StuckLedger.Entry>> a : byAnswer.entrySet()) {
            StringBuilder one = new StringBuilder();
            for (StuckLedger.Entry e : a.getValue()) {
                one.append("  - ").append(e.signature()).append(" (").append(e.occurrences()).append(" times)\n");
            }
            one.append("    ").append(a.getKey().replace("\n", "\n    ")).append('\n');
            if (PromptTokenBudget.estimateTokens(sb) + PromptTokenBudget.estimateTokens(one) > maxTokens) {
                left++;
                log.warn("T4-DY: an answer for '{}' did not fit this prompt ({} token(s) free) -- left out whole;"
                        + " the ledger still counts it delivered", a.getValue().get(0).signature(), maxTokens);
                continue;
            }
            sb.append(one);
        }
        if (left > 0) sb.append("  (").append(left).append(" more answer(s) left out: no room in this prompt)\n");
        return hint == null ? sb.toString().stripTrailing() : sb.toString().stripTrailing() + "\n\n" + hint;
    }

    /** A second reading for the same subgoal joins the first; neither replaces the other. */
    private synchronized void addHint(String subGoalId, String hint) {
        hintBySubGoal.merge(subGoalId, hint, (a, b) -> a.contains(b) ? a : a + "\n" + b);
    }

    private synchronized String hintBlock(String subGoal) {
        String hint = subGoal == null ? null : hintBySubGoal.get(subGoal);
        if (hint == null) return null;
        return "--- A READING OF WHERE THE LOOP IS STUCK (a model's reading of the facts the loop gathered, not a verified fact) ---\n"
                + "  " + hint.replace("\n", "\n  ");
    }

    /**
     * T4-DY dossier: when a diagnosis request is waiting, gathers the facts the loop holds about it, lets
     * the model choose one move from the menu, and carries it out. Called at the start of a cycle, where
     * the context and the actuators are at hand. Returns the context, with a replan request when that was
     * the move.
     */
    public CycleContextData consultDossier(CycleContextData context) {
        Map<StuckLedger.Entry, String> due;
        synchronized (this) {
            due = new LinkedHashMap<>(pendingDossiers);
            pendingDossiers.clear();
        }
        if (due.isEmpty() || context == null || dossierDecider == null || !dossierDecider.available()) return context;
        // ONE decision per cycle: the case that says most leads, the others stand beside it in the dossier
        // and the move is recorded under each. One per request took 8 decisions in run 2026-09-29_16-21-43,
        // every one a HINT, one per compile error that had run out of questions.
        StuckLedger.Entry lead = due.keySet().stream()
                .min(java.util.Comparator.comparingInt((StuckLedger.Entry e) -> LEAD_ORDER.indexOf(e.source()))
                        .thenComparing(java.util.Comparator.comparingInt(StuckLedger.Entry::occurrences).reversed()))
                .orElseThrow();
        return consultDossier(lead, due.get(lead), context, due.keySet());
    }

    /** Which stuck case leads a dossier: the one that says most about where the run is circling. */
    private static final List<StuckObservation.Source> LEAD_ORDER = List.of(StuckObservation.Source.TEST_FAILURES,
            StuckObservation.Source.BOOT, StuckObservation.Source.FLAT_ROOT, StuckObservation.Source.EXHAUSTION, StuckObservation.Source.CLOSURE_REFUSAL,
            StuckObservation.Source.BUILD, StuckObservation.Source.PROTECTED_WRITE);

    private CycleContextData consultDossier(StuckLedger.Entry e, String why, CycleContextData context,
                                            java.util.Collection<StuckLedger.Entry> answered) {
        String subGoalId = context.currentSubGoalId();
        StuckDossier dossier = dossierFor(e, why, context);
        DossierDecision d = dossierDecider.decide(dossier, context.cycleName());
        boolean applied = false;
        String outcome;
        CycleContextData out = context;
        switch (d.move()) {
            case HINT -> {
                synchronized (this) { addHint(subGoalId, d.hint()); }
                applied = subGoalId != null;
                outcome = applied ? "the hint goes to the next change's prompt" : "no current subgoal to give it to";
            }
            case REPLAN -> {
                out = context.issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(
                        com.walpro.autobuilder.core.lifecycle.BasicSignals.REPLAN_REQUESTED, 1.0, "stuck-dossier",
                        com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.2)));
                if (d.hint() != null && subGoalId != null) {
                    synchronized (this) { addHint(subGoalId, d.hint()); }
                }
                applied = true;
                outcome = "replan requested";
            }
            case DIVIDE -> {
                List<String> unmet = new ArrayList<>();
                dossier.criteria().forEach((c, met) -> { if (!met) unmet.add(c); });
                var gps = goalPlannerService == null ? null : goalPlannerService.getIfAvailable();
                if (gps == null || subGoalId == null || unmet.size() < 2) {
                    // Nothing to split: the move's intent -- change the plan for this subgoal -- is a replan.
                    out = context.issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(
                            com.walpro.autobuilder.core.lifecycle.BasicSignals.REPLAN_REQUESTED, 1.0, "stuck-dossier",
                            com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.2)));
                    applied = true;
                    outcome = "replan requested instead: " + (unmet.size() < 2 ? "fewer than two unmet criteria to divide" : "no planner at hand");
                } else {
                    List<String> children = gps.divideExhaustedSubGoal(subGoalId, unmet);
                    applied = !children.isEmpty();
                    outcome = applied ? "divided into " + children.size() + " work item(s)" : "the planner did not divide it";
                }
            }
            default -> outcome = "no move; the diagnosis request stands";
        }
        synchronized (this) {
            String record = d.move() + " at " + context.cycleName() + " (" + outcome + ")"
                    + (d.because() == null ? "" : " because: " + d.because());
            for (StuckLedger.Entry a : answered) {
                movesBySignature.computeIfAbsent(a.signature(), k -> new ArrayList<>()).add(record);
            }
        }
        log.warn("T4-DY: dossier move for '{}': {} -- {}", e.signature(), d.move(), outcome);
        if (behaviourLogger != null) {
            behaviourLogger.dossierDecision(context.cycleName(), e.source().name(), e.signature(), d.move().name(),
                    d.because(), d.hint(), applied, outcome, dossier.factSummary());
        }
        return out;
    }

    /** The facts, gathered. Every source is optional; a missing one is an empty section, never a guess. */
    StuckDossier dossierFor(StuckLedger.Entry e, String why, CycleContextData context) {
        List<String> asked = new ArrayList<>();
        for (StuckLedger.Question q : e.asked()) {
            asked.add(q.question() + " -> " + (q.answer() == null ? "no answer from " + q.authority()
                    : "answered by " + q.authority() + ": " + crop(q.answer(), 600)));
        }
        List<String> others = new ArrayList<>();
        for (StuckLedger.Entry o : ledger.entries()) {
            if (o == e || o.status() == StuckLedger.Status.ENDED) continue;
            others.add(o.source() + ": " + o.signature() + " (" + o.occurrences() + " time(s), " + o.status() + ")");
        }
        List<String> health = new ArrayList<>();
        if (runHealth != null) runHealth.deviations().forEach(dv -> health.add(dv.describe()));
        Map<String, Boolean> criteria = new LinkedHashMap<>();
        var gps = goalPlannerService == null ? null : goalPlannerService.getIfAvailable();
        if (gps != null && context.currentSubGoalId() != null) criteria.putAll(gps.criteriaStatus(context.currentSubGoalId()));
        List<String> rejections = context.rejectionFeedback() == null ? List.of()
                : context.rejectionFeedback().stream().filter(r -> r != null && !r.isBlank()).map(r -> crop(r, 600)).toList();
        List<String> failing = new ArrayList<>();
        if (testRunGate != null) {
            testRunGate.freshReport().ifPresent(r -> r.failing().stream().limit(8).forEach(f -> failing.add(
                    f.className().substring(f.className().lastIndexOf('.') + 1) + "." + f.method()
                            + (f.input() == null ? "" : " -- " + com.walpro.autobuilder.core.build.ParameterizedCaseInput.LABEL + crop(f.input(), 300))
                            + " -- " + crop(f.message(), 300))));
        }
        List<String> earlier;
        synchronized (this) { earlier = List.copyOf(movesBySignature.getOrDefault(e.signature(), List.of())); }
        return new StuckDossier(e.source().name(), e.signature(), why, e.occurrences(), asked, others, health,
                context.currentSubGoalDescription(), criteria, rejections, failing, earlier,
                StuckDossier.verifyOnly(criteria));
    }

    private static String crop(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private void requestDiagnosis(StuckLedger.Entry e, String why, boolean forDossier) {
        if (forDossier && dossierDecider != null && dossierDecider.available()) {
            synchronized (this) {
                pendingDossiers.putIfAbsent(e, why);
            }
        }
        List<String> asked = new ArrayList<>();
        for (StuckLedger.Question q : e.asked()) {
            asked.add(q.question() + " -> " + (q.answer() == null ? "no answer from " + q.authority()
                    : "answered by " + q.authority()));
        }
        log.warn("T4-DY: DIAGNOSIS REQUEST -- '{}' ({}, {} time(s)): {}", e.signature(), e.source(), e.occurrences(), why);
        if (behaviourLogger != null) {
            behaviourLogger.diagnosisRequest(e.lastCycle(), e.source().name(), e.signature(), e.occurrences(),
                    e.firstCycle(), why, asked, e.detail());
        }
        writeLedger();
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

    private synchronized String seed() {
        if (seed != null) return seed;
        try {
            seed = fileService != null && fileService.exists("bootstrap/seed.txt") ? fileService.readString("bootstrap/seed.txt") : "";
        } catch (Exception ex) {
            seed = "";
        }
        return seed;
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
