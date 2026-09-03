package com.walpro.autobuilder.goal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;
import com.walpro.autobuilder.core.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T4-DQ blocker 1: the append-only history of goal-status changes, and the fold that reads a
 * lineage's goal state back as of any earlier point.
 *
 * <p><b>Why an event log rather than a status snapshot.</b> The completeness hazard is symmetric —
 * "every field enumerated" and "every transition emitted" are the same requirement in dual form —
 * but the asymmetry is in when you find out. A fold can be compared against live state on every
 * cycle, so a gap surfaces continuously; snapshot completeness is only ever exercised at restore,
 * which is the worst possible moment to discover one. It also completes an existing pattern rather
 * than importing one: the orchestration graph has always been append-only and cycle-tagged, and
 * goal state was the one mutable island in it.
 *
 * <p><b>One home.</b> Events are appended from the single durability chokepoint that already
 * detects status changes — {@code GoalPlannerService.emitStatusTransitions()}, the T5-I shadow-map
 * diff that every status write passes through on its way to {@code persist()}. Nothing here
 * instruments the ~20 individual mutation sites, and nothing should.
 *
 * <p><b>The file is authoritative; the graph is a projection.</b> The log lives under
 * {@code meta/} beside the goal stack, so it survives a resume (which clears only the run-scoped
 * {@code mutations/} and {@code snapshots/}) and is cleared by a reset (which severs the lineage).
 * Neo4j gets the same events as {@code (:GoalStatusTransition)} nodes for the graph browser, but
 * the fold never reads them — the loop must be able to rewind with the database unavailable.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@StateHolder(value = StateBucket.ALREADY_PERSISTED,
        note = "the log IS the durable home — meta/goal-transitions.ndjson; the only in-memory "
                + "field is a sequence counter mirroring the file's last line")
public class GoalTransitionLog {

    /** Relative to {@code file.base-path}; beside {@code meta/GoalStack.json}. */
    public static final String LOG_PATH = "meta/goal-transitions.ndjson";

    /** Created on first append — a reset wipes {@code meta/} and the log starts empty with it. */
    static final String LOG_DIR = "meta";

    private final FileService fileService;
    private final ObjectMapper objectMapper;

    /** T4-DQ: the lineage whose transitions the graph is asked for when the file has none. */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.init.RunIdentityService runIdentityService;

    /** Mirrors the last line's sequence; {@code -1} until first read from the file. */
    private long cachedSeq = -1L;

    /** Transitions recovered from the graph because the file had none; read at most once. */
    private List<GoalTransition> graphFallback;

    /** Goal ids the log already carries at least one event for; mirrors the file. */
    private final Set<String> knownGoalIds = new LinkedHashSet<>();

    /** Optional graph projection for the browser — the fold never reads it. */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.graph.goal.GoalGraphService goalGraphService;

    /**
     * Append one transition. Returns the assigned sequence number, or -1 when the append failed —
     * a failed append is loud but never throws into the goal-state write path, because a lost
     * history entry must not lose the status change itself.
     */
    public synchronized long append(String goalId, String from, String to, GoalTransition.Kind kind,
                                    String cycleId, String resolution) {
        if (goalId == null || to == null) return -1;
        long seq = currentSeq() + 1;
        cachedSeq = seq;
        knownGoalIds.add(goalId);
        GoalTransition transition = new GoalTransition(
                seq, cycleId, goalId, from, to, kind, resolution, Instant.now());
        try {
            fileService.ensureFolderExists(LOG_DIR);
            // ONE LINE PER EVENT, explicitly. The injected mapper has INDENT_OUTPUT enabled, so
            // writeValueAsString would spread each event over ten lines and the file would stop
            // being appendable-and-readable as a log — caught in the field on the first run: the
            // in-memory counter carried the sequence within the JVM, so nothing looked wrong until
            // a restart, when the fold would have re-read zero events and re-created every goal.
            fileService.writeString(LOG_PATH,
                    compactWriter().writeValueAsString(transition) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            log.warn("T4-DQ: could not append goal transition {} {}->{}: {}", goalId, from, to, e.getMessage());
            cachedSeq = seq - 1;
            return -1;
        }
        projectToGraph(transition);
        return seq;
    }

    /**
     * The highest sequence number written so far; 0 when the log is empty or unreadable. Read
     * from the file once and then carried, so appending stays a single write rather than a
     * re-read of the whole history per event.
     */
    public synchronized long currentSeq() {
        loadOnce();
        return cachedSeq;
    }

    /**
     * Has the log ever recorded this goal? Distinguishes a goal being CREATED now from a goal
     * being re-observed at startup — a resumed run reloads a whole tree the log already knows,
     * and re-stamping every node with a fresh creation event would misdate the history the fold
     * depends on.
     */
    public synchronized boolean knowsGoal(String goalId) {
        loadOnce();
        return knownGoalIds.contains(goalId);
    }

    private void loadOnce() {
        if (cachedSeq >= 0) return;
        List<GoalTransition> all = readAll();
        cachedSeq = all.isEmpty() ? 0L : all.get(all.size() - 1).seq();
        knownGoalIds.clear();
        all.forEach(t -> knownGoalIds.add(t.goalId()));
    }

    /** Drop the carried sequence so the next read re-derives it — used after a reset clears meta/. */
    public synchronized void forgetCachedSeq() {
        cachedSeq = -1L;
        knownGoalIds.clear();
        graphFallback = null;
    }

    /**
     * Every transition, oldest first.
     *
     * <p>Reads the file as a stream of concatenated JSON values rather than line by line, so it
     * recovers a log written by a pretty-printing mapper as well as the compact form written now.
     * A history the reader cannot parse is a fold that silently returns nothing, which is the one
     * failure mode this whole design exists to avoid discovering at a restore.
     */
    public synchronized List<GoalTransition> readAll() {
        List<GoalTransition> out = readFromFile();
        if (!out.isEmpty()) return out;
        // The file is authoritative and is normally there. When it is not — lost, or cleared by
        // something that should not have — the graph holds the same events. Falling back beats the
        // alternative, which is a fold that quietly yields nothing and a rewind that restores no
        // goal state while reporting success.
        return readFromGraph();
    }

    private List<GoalTransition> readFromGraph() {
        if (graphFallback != null) return graphFallback;
        graphFallback = List.of();
        if (goalGraphService == null || runIdentityService == null) return graphFallback;
        try {
            List<GoalTransition> fromGraph =
                    goalGraphService.transitionsForLineage(runIdentityService.lineageId(), 10_000);
            if (!fromGraph.isEmpty()) {
                log.info("T4-DQ: the goal-transition log is absent or empty — recovered {} event(s) "
                        + "for this lineage from the graph.", fromGraph.size());
            }
            graphFallback = fromGraph;
        } catch (Exception e) {
            log.warn("T4-DQ: could not recover goal transitions from the graph: {}", e.getMessage());
        }
        return graphFallback;
    }

    private List<GoalTransition> readFromFile() {
        List<GoalTransition> out = new ArrayList<>();
        try {
            if (!fileService.exists(LOG_PATH)) return out;
            String content = fileService.readString(LOG_PATH);
            if (content.isBlank()) return out;
            try (var values = objectMapper.readerFor(GoalTransition.class).readValues(content)) {
                while (values.hasNextValue()) {
                    out.add((GoalTransition) values.nextValue());
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("T4-DQ: could not read the goal-transition log ({} event(s) recovered): {}",
                    out.size(), e.getMessage());
        }
        return out;
    }

    /** The injected mapper with indentation off — see {@link #append}. */
    private com.fasterxml.jackson.databind.ObjectWriter compactWriter() {
        return objectMapper.writer()
                .without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * The fold: every goal's status as of {@code seq}, in the order the goals were first seen.
     * A goal whose first event is later than the cut point is absent from the result — which is
     * how a rewind knows a goal planned after the cut did not exist yet.
     */
    public Map<String, GoalStatus> statusesAsOf(long seq) {
        Map<String, GoalStatus> statuses = new LinkedHashMap<>();
        for (GoalTransition t : readAll()) {
            if (t.seq() > seq) break;
            if (t.kind() == GoalTransition.Kind.REMOVED) {
                statuses.remove(t.goalId());
                continue;
            }
            GoalStatus status = t.toStatus();
            if (status != null) statuses.put(t.goalId(), status);
        }
        return statuses;
    }

    /** The goals that existed as of {@code seq} — those with at least one event at or before it. */
    public Set<String> goalsExistingAsOf(long seq) {
        Set<String> ids = new LinkedHashSet<>();
        for (GoalTransition t : readAll()) {
            if (t.seq() > seq) break;
            if (t.kind() == GoalTransition.Kind.REMOVED) {
                ids.remove(t.goalId());
            } else {
                ids.add(t.goalId());
            }
        }
        return ids;
    }

    /**
     * The whole fold at one cut point, from ONE read of the log: which goals existed, what status
     * each had, and how many later transitions a rewind there would undo.
     *
     * <p>The three views are taken together on purpose. Read separately they are three reads of a
     * file the loop is still appending to, and a rewind that took its statuses from one read and
     * its existence set from another could restore a goal it also believes never existed.
     */
    public Fold foldAt(long seq) {
        Map<String, GoalStatus> statuses = new LinkedHashMap<>();
        Set<String> existing = new LinkedHashSet<>();
        int after = 0;
        for (GoalTransition t : readAll()) {
            if (t.seq() > seq) {
                after++;
                continue;
            }
            if (t.kind() == GoalTransition.Kind.REMOVED) {
                statuses.remove(t.goalId());
                existing.remove(t.goalId());
                continue;
            }
            existing.add(t.goalId());
            GoalStatus status = t.toStatus();
            if (status != null) statuses.put(t.goalId(), status);
        }
        return new Fold(seq, statuses, existing, after);
    }

    /** The goal tree as of a cut point, plus how much history a rewind there would undo. */
    public record Fold(long seq, Map<String, GoalStatus> statuses, Set<String> existingGoalIds,
                       int transitionsUndone) {}

    /**
     * The transitions a rewind to {@code seq} would undo, newest first. Rendered into the rewind's
     * behaviour event so what a rewind cost is visible without re-reading the log.
     */
    public List<GoalTransition> transitionsAfter(long seq) {
        List<GoalTransition> after = new ArrayList<>();
        for (GoalTransition t : readAll()) {
            if (t.seq() > seq) after.add(t);
        }
        return after;
    }

    /**
     * The highest sequence recorded at or before {@code cycleId} — the cut point for a rewind
     * addressed by cycle rather than by sequence. Returns 0 when nothing was recorded that early,
     * which correctly folds to "no goal existed yet".
     */
    public long seqAtCycle(String cycleId) {
        if (cycleId == null) return currentSeq();
        long cut = 0L;
        for (GoalTransition t : readAll()) {
            if (t.cycleId() == null || t.cycleId().compareTo(cycleId) <= 0) {
                cut = t.seq();
            }
        }
        return cut;
    }

    private void projectToGraph(GoalTransition transition) {
        if (goalGraphService == null) return;
        try {
            goalGraphService.recordStatusTransition(transition);
        } catch (Exception e) {
            log.debug("T4-DQ: graph projection of goal transition {} failed (log is authoritative): {}",
                    transition.seq(), e.getMessage());
        }
    }
}
