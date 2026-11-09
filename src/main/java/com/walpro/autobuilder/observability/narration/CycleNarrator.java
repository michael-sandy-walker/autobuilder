package com.walpro.autobuilder.observability.narration;

import com.walpro.autobuilder.client.NarratorChannel;
import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * T4-EM: tells each cycle to a person listening, in one or two sentences the narrator model writes from that
 * cycle's own events ({@link CycleRecord}), over the narrator's own channel ({@link NarratorChannel}).
 *
 * <p><b>Never in the loop's way.</b> Events are collected on the emitting thread, which only appends; the model is
 * asked on one background thread after the cycle ends. When cycles end faster than the narrator answers, the
 * oldest waiting cycle is dropped: a late story about an old cycle is worth less than the current one.
 *
 * <p><b>Only what the record says.</b> The prompt forbids causes the record does not state; the agents' own reasons
 * (planner, evaluator) are in the record so that the why has a source.
 */
@Slf4j
@Component
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "what was told aloud and the cycle being collected -- a rewind does not unsay a telling, and a "
                + "half-collected cycle of the abandoned path must not be finished on the restored one")
public class CycleNarrator {

    static final String SYSTEM = """
            You tell a person, who is listening and not reading, what an automated software-building loop just did.
            You get the record of one cycle of that loop. Answer with one or two short spoken sentences, at most 45 words.
            Say what happened and, when the record gives a reason, why. Use only what the record states: never add a
            cause, a fact or a plan of your own. Something was done only when the record says it was changed, kept,
            completed or accepted; the planner's reason says what it plans next, so tell it as a plan, not as done.
            Name things only as the record names them, and say a file as its name without the ending.
            Plain spoken words: no file paths, no code, no symbols, no quotation marks, no lists, no headings.
            Do not start with "In this cycle". Do not repeat what the previous telling already said.""";

    private static final Pattern THINK = Pattern.compile("(?s)<think>.*?</think>");
    private static final int MAX_WORDS = 60;

    private final NarratorChannel channel;
    private final BehaviourLogger behaviourLogger;
    private final ObjectProvider<GoalPlannerService> goals;

    /** The open cycle's record; only the emitting thread touches it. */
    private final Map<String, CycleRecord> open = new HashMap<>();
    private volatile String previous;

    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), r -> {
                Thread t = new Thread(r, "narrator");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());

    public CycleNarrator(NarratorChannel channel, BehaviourLogger behaviourLogger, ObjectProvider<GoalPlannerService> goals) {
        this.channel = channel;
        this.behaviourLogger = behaviourLogger;
        this.goals = goals;
    }

    @PostConstruct
    void listen() {
        if (channel.enabled()) behaviourLogger.addListener(this::accept);
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    /** Called on the emitting thread for every event: append, and on a cycle's end hand its record to the worker. */
    synchronized void accept(Map<String, Object> envelope) {
        String cycleId = CycleRecord.cycleOf(envelope.get("cycleId"));
        if (cycleId == null) return;
        CycleRecord record = open.computeIfAbsent(cycleId, k -> new CycleRecord());
        GoalEntity goal = goalOf(envelope);
        boolean root = goal != null && goal.getParentId() == null;
        record.add(envelope, goal == null ? null : goal.getDescription(), root);
        if (!record.ended()) return;
        open.remove(cycleId);
        open.clear(); // a cycle that never ended (a crash, a rewind) is not told later
        if (record.worthTelling()) {
            String text = record.render();
            worker.execute(() -> tell(cycleId, text));
        }
    }

    /** Visible for testing: asks the narrator for one cycle and emits what it said. */
    void tell(String cycleId, String record) {
        long start = System.currentTimeMillis();
        String user = (previous != null ? "The previous telling: " + previous + "\n\n" : "")
                + "The record of this cycle:\n" + record;
        Optional<String> said = channel.ask(SYSTEM, user, cycleId).map(CycleNarrator::spoken).filter(s -> !s.isBlank());
        if (said.isEmpty()) return;
        previous = said.get();
        behaviourLogger.cycleNarrated(cycleId, said.get(), System.currentTimeMillis() - start, channel.model());
    }

    /** The answer as one line to be said: reasoning blocks, markup and surrounding quotes removed, length capped. */
    static String spoken(String answer) {
        String s = THINK.matcher(answer).replaceAll("");
        s = s.replaceAll("[*_#`>]+", "").replaceAll("\\s+", " ").trim();
        if (s.length() > 1 && (s.startsWith("\"") && s.endsWith("\""))) s = s.substring(1, s.length() - 1).trim();
        String[] words = s.split(" ");
        if (words.length > MAX_WORDS) {
            String cut = String.join(" ", java.util.Arrays.copyOf(words, MAX_WORDS));
            int dot = Math.max(cut.lastIndexOf(". "), cut.lastIndexOf('.'));
            s = dot > 0 ? cut.substring(0, dot + 1) : cut + ".";
        }
        return s;
    }

    private GoalEntity goalOf(Map<String, Object> envelope) {
        if (!"goal_status_changed".equals(envelope.get("event"))) return null;
        try {
            Object p = envelope.get("payload");
            Object goalId = p instanceof Map<?, ?> m ? m.get("goalId") : null;
            GoalPlannerService service = goals.getIfAvailable();
            if (goalId == null || service == null) return null;
            return service.getGoalById(String.valueOf(goalId));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
