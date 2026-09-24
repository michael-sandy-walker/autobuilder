package com.walpro.autobuilder.controller.goal;

import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.SignalReadout;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * T4-AM: maintains an active registry of {@link SseEmitter} subscribers and fans cycle-end
 * snapshots out to all of them.
 *
 * <p>Emitters are pruned on error or completion. Slow/dead clients are best-effort: if a write
 * fails we remove the emitter and move on. Publishing is synchronous but bounded by the number
 * of live connections — not expected to be hot-path significant.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "live SSE subscriber list")
public class GoalStateEventPublisher {

    /** Never time out — the client controls lifetime by closing the connection. */
    private static final long EMITTER_TIMEOUT_MS = Long.MAX_VALUE;

    /** Emitted on register() so a freshly-connected client sees state without waiting. */
    private static final String EVENT_HELLO = "hello";

    /** Published at end of every CycleExecutor.runCycle(). */
    private static final String EVENT_CYCLE_END = "cycle-end";

    /** Published after any write action mutates the goal tree (T4-AM write actions). */
    private static final String EVENT_TREE_CHANGED = "tree-changed";

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    private final GoalPlannerService goalPlannerService;

    /**
     * Registers a new SSE subscriber. The returned emitter has an effectively-infinite timeout
     * and is automatically removed from the registry on completion, timeout, or error.
     * A {@code hello} event is sent immediately with the current full snapshot so the client
     * can seed its view without a round-trip to {@code /tree}.
     */
    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitters.add(emitter);
        emitter.onCompletion(() -> {
            emitters.remove(emitter);
            log.debug("SSE subscriber completed ({} remaining)", emitters.size());
        });
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            log.debug("SSE subscriber timed out ({} remaining)", emitters.size());
        });
        emitter.onError(t -> {
            emitters.remove(emitter);
            log.debug("SSE subscriber errored: {} ({} remaining)", t.getMessage(), emitters.size());
        });

        try {
            GoalStateEvent hello = snapshotEvent(GoalStateEvent.KIND_CYCLE_END, null);
            emitter.send(SseEmitter.event().name(EVENT_HELLO).data(hello));
        } catch (IOException e) {
            emitters.remove(emitter);
            emitter.completeWithError(e);
        }
        log.info("SSE subscriber registered ({} total)", emitters.size());
        return emitter;
    }

    /** Number of live subscribers — exposed for tests and status endpoints. */
    public int subscriberCount() {
        return emitters.size();
    }

    /**
     * Called at the end of every cycle. Builds a {@link GoalStateEvent} from the post-cycle
     * context and broadcasts to all live subscribers.
     */
    public void publishCycleEnd(CycleContextData context) {
        if (context == null) return;
        GoalStateEvent event = snapshotEvent(GoalStateEvent.KIND_CYCLE_END, context);
        broadcast(EVENT_CYCLE_END, event);
    }

    /**
     * Called after a write action that mutated the goal tree (inject, force-given-up, pin, etc).
     * Re-reads the current planner state so the frontend can reconcile without waiting for the
     * next cycle boundary.
     */
    public void publishTreeChanged(CycleContextData context) {
        GoalStateEvent event = snapshotEvent(GoalStateEvent.KIND_TREE_CHANGED, context);
        broadcast(EVENT_TREE_CHANGED, event);
    }

    // -----------------------------------------------------------------------------------
    // internals
    // -----------------------------------------------------------------------------------

    private void broadcast(String eventName, GoalStateEvent payload) {
        if (emitters.isEmpty()) return;
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (Exception e) {
                log.debug("SSE broadcast failed for one subscriber: {}", e.getMessage());
                dead.add(emitter);
            }
        }
        if (!dead.isEmpty()) {
            emitters.removeAll(dead);
            for (SseEmitter d : dead) {
                try { d.completeWithError(new RuntimeException("broadcast failed")); } catch (Exception ignored) {}
            }
        }
    }

    private GoalStateEvent snapshotEvent(String kind, CycleContextData context) {
        List<GoalStateSnapshotDto> goals = buildFlatGoalList();
        Map<String, Double> signalMap = extractSignalStrengths(context);
        String cycleName = context != null ? context.cycleName() : null;
        String resultState = (context != null && context.cycleResultState() != null)
                ? context.cycleResultState().name() : null;
        boolean merged = context != null && context.merged();
        String currentGoalId = context != null ? context.currentGoalId() : null;
        String currentSubGoalId = resolveCurrentSubGoalId(context);
        return new GoalStateEvent(
                kind,
                Instant.now(),
                cycleName,
                resultState,
                merged,
                currentGoalId,
                currentSubGoalId,
                signalMap,
                goals
        );
    }

    private String resolveCurrentSubGoalId(CycleContextData context) {
        if (context != null && context.currentSubGoalId() != null) return context.currentSubGoalId();
        // T1-FD: peek, never getCurrentSubGoal — snapshot building must not run recovery
        // or T1-CN closure (with LLM calls) on SSE/observer threads.
        GoalEntity cur = goalPlannerService.peekCurrentSubGoal();
        return cur != null ? cur.getId() : null;
    }

    private List<GoalStateSnapshotDto> buildFlatGoalList() {
        GoalEntity current = goalPlannerService.peekCurrentSubGoal();
        String currentId = current != null ? current.getId() : null;

        List<GoalEntity> all = goalPlannerService.getAllGoals();
        List<GoalStateSnapshotDto> out = new ArrayList<>(all.size());
        for (GoalEntity g : all) {
            out.add(toSnapshot(g, currentId));
        }
        return out;
    }

    private GoalStateSnapshotDto toSnapshot(GoalEntity g, String currentId) {
        List<String> criteria = GoalExplorerService.asStringList(g.getMetadata().get("acceptanceCriteria"));
        Map<Integer, Boolean> passStatus = goalPlannerService.getCriteriaPassStatus(g);
        int met = 0;
        for (int i = 1; i <= criteria.size(); i++) {
            if (Boolean.TRUE.equals(passStatus.get(i))) met++;
        }
        Integer planningRunId = GoalExplorerService.readInt(g.getMetadata().get("planningRunId"));
        return new GoalStateSnapshotDto(
                g.getId(),
                g.getParentId(),
                g.getDescription(),
                g.getType() != null ? g.getType().name() : null,
                g.getStatus() != null ? g.getStatus().name() : null,
                g.getRole(),
                g.getMergeCount(),
                goalPlannerService.getGoalMetadataInt(g, "cumulativeStagnatingCycles"),
                planningRunId,
                criteria.size(),
                met,
                Objects.equals(g.getId(), currentId)
        );
    }

    private Map<String, Double> extractSignalStrengths(CycleContextData context) {
        // T4-BZ-1: the SSE signal map renders through the readout surface.
        return SignalReadout.of(context).strengthsInOrder();
    }
}
