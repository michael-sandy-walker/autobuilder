package com.walpro.autobuilder.controller;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
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
        autobuilderService.injectSignal(Signal.of(BasicSignals.SYSTEM_PAUSED, 1.0, 0.0, "user-pause"));
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
}
