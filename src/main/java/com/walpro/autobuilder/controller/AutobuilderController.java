package com.walpro.autobuilder.controller;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.core.service.AutobuilderService;
import com.walpro.autobuilder.goal.CoverageReviewResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/autobuilder")
@RequiredArgsConstructor
public class AutobuilderController {

    private final AutobuilderService autobuilderService;

    /**
     * Triggers a bootstrap pass (respecting existing reset / bootstrap logic)
     * and returns the resulting status.
     */
    @PostMapping("/bootstrap")
    public ResponseEntity<AutobuilderStatusResponse> bootstrap() {
        autobuilderService.bootstrapOnce();
        return status();
    }

    /**
     * Runs a single Autobuilder cycle on demand and returns the new status.
     */
    @PostMapping("/cycle")
    public ResponseEntity<AutobuilderStatusResponse> runSingleCycle() {
        autobuilderService.runAutobuilderCycle();
        return status();
    }

    /**
     * Returns the last known Autobuilder state (bootstrap or cycle).
     */
    @GetMapping("/status")
    public ResponseEntity<AutobuilderStatusResponse> status() {
        Optional<CycleContextData> maybeContext = autobuilderService.getContext();
        AutobuilderStatusResponse body = maybeContext
                .map(AutobuilderStatusResponse::from)
                .orElseGet(AutobuilderStatusResponse::empty);
        return ResponseEntity.ok(body);
    }
    
    @PostMapping("/evolve")
    public ResponseEntity<String> evolveProjectManually() {
        autobuilderService.runAutobuilderCycle();
        return ResponseEntity.ok("Evolution cycle triggered.");
    }

    /**
     * Wipes the goal graph + mutation/output/snapshot folders, re-seeds the root goal from
     * {@code bootstrap/seed.txt}, and runs a fresh cycle. Same effect as restarting the JVM
     * with {@code autobuilder.service.reset-on-start=true} but without the restart.
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset() {
        autobuilderService.forceReset();
        return ResponseEntity.ok(Map.of("ok", true, "message", "System reset and cycle started."));
    }

    /**
     * Runs a coverage retrospective against the root goal and injects any missing features
     * as new first-wave subgoals.  If gaps are found the system is unpaused automatically.
     * Safe to call when the system is idle, paused, or running.
     * <p>
     * T1-DE: proposals that paraphrase already-GIVEN_UP goals are filtered before injection.
     * droppedCount indicates how many LLM proposals were suppressed by the dedup filter.
     */
    @PostMapping("/coverage-review")
    public ResponseEntity<Map<String, Object>> coverageReview() {
        CoverageReviewResult result = autobuilderService.triggerCoverageReview();
        return ResponseEntity.ok(Map.of(
                "gapsFound", !result.injected().isEmpty(),
                "injectedCount", result.injected().size(),
                "droppedCount", result.dropped(),
                "injectedFeatures", result.injected()
        ));
    }

    /**
     * Injects a permanent SYSTEM_PAUSED signal so the scheduler halts after the current cycle
     * (or immediately if no cycle is running). Use {@code /resume} to clear it.
     */
    @PostMapping("/pause")
    public ResponseEntity<Map<String, Object>> pause() {
        autobuilderService.pause("user-pause");
        return ResponseEntity.ok(Map.of("ok", true, "message", "SYSTEM_PAUSED injected — loop will halt after the current cycle."));
    }

    /**
     * Clears SYSTEM_PAUSED + GOAL_EXHAUSTED so the scheduler resumes on the next tick.
     * Use after manually reactivating goals (or adding new ACTIVE work) when the loop has
     * halted because the previous run ran out of active subgoals. Safe to call when the
     * system is already running.
     */
    @PostMapping("/resume")
    public ResponseEntity<Map<String, Object>> resume() {
        autobuilderService.clearHaltSignals();
        return ResponseEntity.ok(Map.of("ok", true, "message", "Halt signals cleared."));
    }

    /**
     * Injects or overwrites a named signal with the given strength and decayPerCycle.
     * Accepts a JSON body: {@code { "strength": 1.0, "decayPerCycle": 0.1 }}.
     * {@code decayPerCycle} is optional and defaults to 0.1.
     */
    @PutMapping("/signals/{name}")
    public ResponseEntity<Map<String, Object>> upsertSignal(
            @PathVariable String name,
            @RequestBody Map<String, Object> body) {
        SignalType type;
        try {
            type = SignalType.of(name);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "Invalid signal name: " + name));
        }
        double strength = body.containsKey("strength")
                ? ((Number) body.get("strength")).doubleValue()
                : 1.0;
        double decayPerCycle = body.containsKey("decayPerCycle")
                ? ((Number) body.get("decayPerCycle")).doubleValue()
                : 0.1;
        strength = Math.max(0.0, Math.min(Signal.MAX_STRENGTH, strength));
        // T4-BZ-3 (Q-d): the name resolves by family — REQUEST kinds (and unknown names, which
        // read as requests: operator poke = ask) enqueue on the channel with strength as
        // urgency and the decay-implied lifetime as the validity horizon; the other families
        // still write the stored map until their migration slice.
        com.walpro.autobuilder.core.lifecycle.SignalReadout.Family family =
                com.walpro.autobuilder.core.lifecycle.SignalReadout.familyOf(type);
        if (family == com.walpro.autobuilder.core.lifecycle.SignalReadout.Family.REQUEST) {
            autobuilderService.injectRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(
                    type, strength, "user-edit",
                    com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(strength, decayPerCycle)));
        } else if (family == com.walpro.autobuilder.core.lifecycle.SignalReadout.Family.WINDOW) {
            // T4-BZ-4 (Q-d): ROLE_* names grant/refresh the participation window; strength 0
            // is an explicit revocation (pause-and-poke keeps its full vocabulary).
            String role = com.walpro.autobuilder.core.lifecycle.BasicSignals.roleNameFor(type);
            if (strength <= 0.0) {
                autobuilderService.removeSignal(type);
            } else {
                autobuilderService.injectWindow(com.walpro.autobuilder.core.lifecycle.ParticipationWindow.of(
                        role, strength, decayPerCycle, "user-edit"));
            }
        } else if (family == com.walpro.autobuilder.core.lifecycle.SignalReadout.Family.TOGGLE) {
            // T4-BZ-5 (Q-d): toggles are set/cleared, not stored — SYSTEM_PAUSED records a
            // reason, the operator switches flip their booleans; strength 0 clears.
            if (com.walpro.autobuilder.core.lifecycle.BasicSignals.SYSTEM_PAUSED.equals(type)) {
                if (strength <= 0.0) autobuilderService.removeSignal(type);
                else autobuilderService.pause("user-edit");
            } else {
                autobuilderService.setOperatorSwitch(type, strength > 0.0);
            }
        } else {
            autobuilderService.injectSignal(Signal.of(type, strength, decayPerCycle, "user-edit"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "message", "Signal " + type.name() + " set to " + strength));
    }

    /**
     * Removes a named signal from the current cycle context.
     */
    @DeleteMapping("/signals/{name}")
    public ResponseEntity<Map<String, Object>> deleteSignal(@PathVariable String name) {
        SignalType type;
        try {
            type = SignalType.of(name);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "Invalid signal name: " + name));
        }
        autobuilderService.removeSignal(type);
        return ResponseEntity.ok(Map.of("ok", true, "message", "Signal " + type.name() + " removed."));
    }
}
