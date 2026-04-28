package com.walpro.autobuilder.controller;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.AutobuilderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
     */
    @PostMapping("/coverage-review")
    public ResponseEntity<Map<String, Object>> coverageReview() {
        List<String> injected = autobuilderService.triggerCoverageReview();
        return ResponseEntity.ok(Map.of(
                "gapsFound", !injected.isEmpty(),
                "injectedCount", injected.size(),
                "injectedFeatures", injected
        ));
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
