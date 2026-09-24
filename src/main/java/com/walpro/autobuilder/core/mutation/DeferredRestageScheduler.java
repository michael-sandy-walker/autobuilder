package com.walpro.autobuilder.core.mutation;

import com.walpro.autobuilder.core.checkpoint.CheckpointableState;
import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * T4-BE: cycle_start hook that schedules a {@link DeferredMutationRestager} attempt for the
 * highest-ranked READY candidate when the scope/score/cooldown/suppression criteria pass.
 *
 * <p>Three tiers (per the ticket):
 * <ol>
 *   <li><b>In-scope ready candidate</b> — candidate's intent subgoal == active subgoal →
 *       schedule directly.</li>
 *   <li><b>Out-of-scope ready candidate, high quality</b> — engine actively re-focuses
 *       (completion-bias) and schedules, gated by combined-score threshold, refocus cooldown,
 *       and drift pre-check.</li>
 *   <li><b>Otherwise</b> — candidate stays READY for the snapshot to surface; meta-agent can
 *       handle explicitly or wait for scope to drift back.</li>
 * </ol>
 *
 * <p>Tick semantics: every {@code scheduleIfReady} call bumps the ledger's monotonic cycle
 * clock. That clock drives both the ranking recency factor and the refocus cooldown.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@StateHolder(value = StateBucket.CAPTURE,
        note = "the last scope-promotion index is the restage cooldown gate")
public class DeferredRestageScheduler implements CheckpointableState {

    private final DeferredMutationLedger ledger;
    private final DeferredMutationRestager restager;
    private final GoalPlannerService goalPlannerService;
    private final FileService fileService;

    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    @Value("${autobuilder.mutation.deferred.refocusThreshold:0.6}")
    private double refocusThreshold;

    @Value("${autobuilder.mutation.deferred.refocusCooldownCycles:3}")
    private int refocusCooldownCycles;

    @Value("${autobuilder.mutation.deferred.ttlCycles:50}")
    private int ttlCycles;

    @Value("${autobuilder.mutation.deferred.maxOpen:20}")
    private int maxOpen;

    /** Last cycle index (per ledger clock) at which we performed a scope promotion. */
    private long lastScopePromotionCycleIndex = -1;

    /**
     * Inspect the ready queue and stage the highest-ranked candidate whose preconditions pass.
     * Returns the (possibly modified) context: if a candidate was staged, the context carries
     * the deferred mutation's files as {@code evolutionOutcome}, the probe rollback point, and
     * a fresh {@code ROLE_EVALUATION} signal; otherwise the input context is returned unchanged.
     */
    public CycleContextData scheduleIfReady(CycleContextData context) {
        if (context == null) return null;

        // Bump cycle clock so ranking recency + cooldown are comparable.
        ledger.tick();

        // TTL + intent-closure + cap enforcement runs every cycle, regardless of suppression
        // and regardless of whether there are ready candidates — long-stop hygiene.
        enforceLifecycleLimits();

        // Suppression — meta-agent's opt-out.
        if (context.restageSuppressed()) {
            log.debug("T4-BE scheduler: restage suppressed by RESTAGE_SUPPRESS signal");
            return context;
        }

        List<DeferredMutation> ready = ledger.findReadyRanked();
        if (ready.isEmpty()) return context;

        String activeSubGoalId = goalPlannerService.getCurrentSubGoalId();

        for (DeferredMutation candidate : ready) {
            // Intent-status branch (T4-BF expansion): the original T4-BE sweep collapsed
            // COMPLETED + GIVEN_UP + missing into a single "drop intent_closed". That threw
            // away recovered work whenever the merge that cleared a candidate's blockers also
            // happened to complete the candidate's intent subgoal — observed three times in
            // run 2026-05-24_16-35-50 (one with clearedFingerprintCount=9). Split the branch:
            //   GIVEN_UP / missing → drop (re-staging an abandoned intent is wasted work)
            //   COMPLETED → attempt restage in the *current* active scope (cannot re-focus
            //               on a non-ACTIVE goal); restager's drift + re-probe + downstream
            //               eval catches redundancy
            //   ACTIVE → today's in-scope / completion-bias-refocus logic
            String intentSub = candidate.intentSubgoalId();
            GoalStatus intentStatus = intentStatusOf(intentSub);

            if (intentStatus == GoalStatus.GIVEN_UP || intentStatus == null) {
                log.info("T4-BE scheduler: dropping deferred {} — intent subgoal {} status={}",
                        candidate.id(), intentSub, intentStatus);
                ledger.markDroppedIntentClosed(candidate.id());
                continue;
            }

            boolean intentCompleted = intentStatus == GoalStatus.COMPLETED;
            boolean inScope = !intentCompleted && intentSub != null && intentSub.equals(activeSubGoalId);

            if (!inScope && !intentCompleted) {
                // Completion-bias re-focus path — only legal when the intent is still ACTIVE.
                double combined = candidate.combinedScore();
                long sincePromotion = ledger.currentTick() - lastScopePromotionCycleIndex;
                boolean withinCooldown = lastScopePromotionCycleIndex >= 0
                        && sincePromotion < refocusCooldownCycles;

                if (combined < refocusThreshold || withinCooldown) {
                    log.debug("T4-BE scheduler: candidate {} out of scope; not promoting (score={} threshold={} cooldown={})",
                            candidate.id(), combined, refocusThreshold, withinCooldown);
                    continue;
                }

                if (preFlightDrift(candidate, context)) {
                    // T1-EY: drift does not self-heal — the on-disk file has permanently moved
                    // past the deferred snapshot. Leaving the candidate READY re-selects it every
                    // cycle forever (observed 06-09: ~35 consecutive skip cycles over 19 minutes).
                    // Drop it through the same path the restager's own drift check uses.
                    log.info("T4-BE scheduler: pre-flight drift on candidate {} — dropping (T1-EY)",
                            candidate.id());
                    ledger.markDroppedDrift(candidate.id());
                    continue;
                }

                // Shift the active subgoal pointer to the candidate's intent.
                try {
                    String prior = goalPlannerService.focusOnSubGoal(intentSub);
                    lastScopePromotionCycleIndex = ledger.currentTick();
                    if (behaviourLogger != null) {
                        behaviourLogger.deferredRestageScopePromoted(
                                context.cycleName(), prior, intentSub, candidate.id(), "completion-bias");
                    }
                    context = context.withCurrentSubGoalId(intentSub);
                    log.info("T4-BE scheduler: scope promoted {} → {} (completion-bias, candidate {})",
                            prior, intentSub, candidate.id());
                } catch (Exception e) {
                    log.warn("T4-BE scheduler: focusOnSubGoal failed for {}: {}", intentSub, e.getMessage());
                    continue;
                }
            } else if (intentCompleted) {
                log.info("T4-BE scheduler: attempting restage of {} despite intent {} COMPLETED — scoped to current subgoal {}",
                        candidate.id(), intentSub, activeSubGoalId);
            }

            // Attempt the restage. Restager handles drift / reprobe / state transitions.
            DeferredMutationRestager.RestageResult result = restager.attemptRestage(candidate, context);
            if (result instanceof DeferredMutationRestager.RestageResult.Staged staged) {
                return staged.context();
            }
            // Dropped or Skipped — try the next candidate.
        }

        return context;
    }

    /** GoalStatus of the candidate's intent subgoal — {@code null} if the goal is unknown
     *  to the planner (treated as GIVEN_UP for drop semantics). */
    private GoalStatus intentStatusOf(String intentSub) {
        if (intentSub == null) return null;
        GoalEntity g = goalPlannerService.getGoalById(intentSub);
        return g != null ? g.getStatus() : null;
    }

    /** Hash-compare each TOUCHES file against current disk. Returns true on any mismatch. */
    private boolean preFlightDrift(DeferredMutation candidate, CycleContextData context) {
        String outputPath = context.outputPath();
        for (Map.Entry<String, String> touched : candidate.touchedFiles().entrySet()) {
            try {
                String relPath = outputPath + "/" + touched.getKey();
                if (!fileService.exists(relPath)) continue;
                String current = sha256(fileService.readString(relPath));
                if (!current.equals(touched.getValue())) return true;
            } catch (Exception e) {
                log.warn("T4-BE scheduler: drift pre-check failed for {}: {}", touched.getKey(), e.getMessage());
            }
        }
        return false;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** Test seam. */
    // T4-DQ: the cooldown index, read relative to the ledger's cycle clock (also captured).
    public record Snapshot(long lastScopePromotionCycleIndex) {}

    @Override
    public String stateKey() {
        return "deferredRestageCooldown";
    }

    @Override
    public Object captureState() {
        return new Snapshot(lastScopePromotionCycleIndex);
    }

    @Override
    public void restoreState(com.fasterxml.jackson.databind.JsonNode state,
                             com.fasterxml.jackson.databind.ObjectMapper mapper) {
        lastScopePromotionCycleIndex = state == null || state.isNull()
                ? -1L : mapper.convertValue(state, Snapshot.class).lastScopePromotionCycleIndex();
    }

    /**
     * Long-stop hygiene that runs every cycle: drop records whose intent goal closed, evict
     * records older than {@code ttlCycles}, and enforce the {@code maxOpen} cap via LRU
     * eviction by deferredAtCycleIndex. Best-effort — exceptions don't bubble.
     */
    private void enforceLifecycleLimits() {
        try {
            long now = ledger.currentTick();
            List<DeferredMutation> open = ledger.findOpen();

            // 1. intent-closure eviction — only drop when the intent is GIVEN_UP or missing.
            //    COMPLETED intents stay in the open queue so the per-candidate logic in
            //    scheduleIfReady can attempt a restage scoped to the current active subgoal
            //    (the recovered-work path — see the corresponding comment there).
            for (DeferredMutation d : open) {
                String intent = d.intentSubgoalId() != null ? d.intentSubgoalId() : d.intentGoalId();
                if (intent == null) continue;
                GoalStatus status = intentStatusOf(intent);
                if (status == GoalStatus.GIVEN_UP || status == null) {
                    ledger.markDroppedIntentClosed(d.id());
                }
            }

            // Re-read after intent-closure passes — entries we just dropped are no longer open.
            open = ledger.findOpen();

            // 2. TTL eviction (deferredAtCycleIndex + ttlCycles < now)
            for (DeferredMutation d : open) {
                if (ttlCycles > 0 && now - d.deferredAtCycleIndex() > ttlCycles) {
                    ledger.markEvicted(d.id(), "ttl");
                }
            }

            // 3. LRU cap — keep at most maxOpen, evict the oldest by deferredAtCycleIndex.
            open = ledger.findOpen();
            if (open.size() > maxOpen) {
                int evictCount = open.size() - maxOpen;
                // findOpen returns sorted by deferredAtCycleIndex ASC; oldest first.
                for (int i = 0; i < evictCount; i++) {
                    ledger.markEvicted(open.get(i).id(), "cap");
                }
            }
        } catch (Exception e) {
            log.warn("T4-BE scheduler: lifecycle limit enforcement failed: {}", e.getMessage());
        }
    }
}
