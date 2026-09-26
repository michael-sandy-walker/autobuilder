package com.walpro.autobuilder.core.stuck;

import com.walpro.autobuilder.core.build.ProtectedPaths;
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
                    requestDiagnosis(e, "no authority in the loop answers this kind of stuck");
                }
            }
        }
        if (change != StuckLedger.Change.NONE && change != StuckLedger.Change.COUNTED) writeLedger();
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
        StuckDiagnosis.Authorities authorities = new StuckDiagnosis.Authorities(
                context == null ? Map.of() : context.previousOutput(), adoptedSources(), seed());
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
                    requestDiagnosis(e, asked.get().answered()
                            ? "the answer names a requirement only the operator may bind or release"
                            : "every question was asked; none ended it");
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
        if (standing.isEmpty()) return null;
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
        return sb.toString().stripTrailing();
    }

    private void requestDiagnosis(StuckLedger.Entry e, String why) {
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

    private StuckLedger.Entry find(StuckObservation o) {
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
