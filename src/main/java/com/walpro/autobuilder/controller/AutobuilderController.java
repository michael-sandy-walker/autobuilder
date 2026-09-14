package com.walpro.autobuilder.controller;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.core.service.AutobuilderService;
import com.walpro.autobuilder.goal.CoverageReviewResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/autobuilder")
@RequiredArgsConstructor
@Slf4j
public class AutobuilderController {

    private final AutobuilderService autobuilderService;
    private final com.walpro.autobuilder.client.LlmUsageTracker llmUsageTracker;

    /**
     * T4-DQ: optional — the loop can already back out of its own bad path, but until this there
     * was no way for a PERSON to ask. Optional so a context without checkpointing still starts.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.core.checkpoint.RewindService rewindService;

    /**
     * Reads a project the loop did not create and reports what is there. Optional so a context
     * without it still starts.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.core.coldread.ColdReadService coldReadService;

    /** T4-CQ: runs an imported project's own tests, in a copy, so a read never writes. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.core.coldread.ColdReadVerification coldReadVerification;

    @org.springframework.beans.factory.annotation.Value("${file.base-path}")
    private String fileBasePath;

    /**
     * What is in the tree this loop is pointed at, read as if it had never seen it.
     *
     * <p>Answers the question a person asks before pointing the loop at someone else's code: what
     * does it think this project even is? Deterministic and read-only, so it can be run against a
     * tree without changing anything in it.
     */
    @GetMapping(value = "/cold-read", produces = org.springframework.http.MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> coldRead(
            @RequestParam(name = "runTests", required = false, defaultValue = "false") boolean runTests) {
        if (coldReadService == null) {
            return ResponseEntity.status(503).body("Cold read is not enabled in this context.\n");
        }
        java.nio.file.Path root = java.nio.file.Path.of(fileBasePath, "output").toAbsolutePath().normalize();
        // Opt-in, because it builds: minutes of work and a reach for the network is not something
        // to do because somebody asked what a directory holds. The build runs in a COPY, so asking
        // still never writes to the project.
        com.walpro.autobuilder.core.coldread.ColdReadVerification.Result verification = null;
        if (runTests) {
            if (coldReadVerification == null) {
                verification = com.walpro.autobuilder.core.coldread.ColdReadVerification.Result
                        .notAttempted("the verification service is not available in this context");
            } else {
                verification = coldReadVerification.verify(root);
            }
        }
        return ResponseEntity.ok(com.walpro.autobuilder.core.coldread.ColdReadReport.render(
                coldReadService.read(root), verification));
    }

    /** T4-DP: what is startable, and which modes each target supports. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.core.init.TargetCatalog targetCatalog;

    /** T4-DP: how this run began, and whether the tree agreed to it. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.core.init.StartModeService startModeService;

    /**
     * The targets available to start, with what each one carries and the modes it supports.
     *
     * <p>The question that comes before choosing a start mode: what is there to start? A target
     * carrying a specification can be begun fresh, one carrying a lineage can be continued, and
     * someone else's code can only be adopted.
     */
    @GetMapping(value = "/targets", produces = org.springframework.http.MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> targets() {
        if (targetCatalog == null) {
            return ResponseEntity.status(503).body("The target catalogue is not enabled in this context.\n");
        }
        StringBuilder body = new StringBuilder(targetCatalog.render());
        if (startModeService != null) {
            var decision = startModeService.decide();
            body.append("\nTHIS RUN\n  mode          ").append(decision.mode())
                .append("\n  tree          ").append(decision.situation())
                .append("\n  ").append(decision.allowed() ? "allowed       " : "REFUSED       ")
                .append(decision.explanation()).append('\n');
        }
        return ResponseEntity.ok(body.toString());
    }

    /**
     * T4-DP slice 4: everything the chooser needs, as JSON — what is startable, what this run is
     * pointed at, and whether it is holding.
     */
    @GetMapping("/start-state")
    public ResponseEntity<Map<String, Object>> startState() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("held", autobuilderService.held());
        body.put("awaitingGoal", autobuilderService.awaitingGoal());
        if (startModeService != null) {
            var d = startModeService.decide();
            body.put("mode", d.mode().name());
            body.put("situation", d.situation().name());
            body.put("allowed", d.allowed());
            body.put("explanation", d.explanation());
        }
        java.nio.file.Path base = java.nio.file.Path.of(fileBasePath).toAbsolutePath().normalize();
        body.put("target", base.getFileName() == null ? base.toString() : base.getFileName().toString());
        body.put("targetPath", base.toString());
        if (targetCatalog != null) {
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            for (var t : targetCatalog.targets()) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("name", t.name());
                row.put("situation", t.situation().name());
                row.put("sourceFiles", t.sourceFiles());
                row.put("hasSeed", t.hasSeed());
                row.put("seedSummary", t.seedSummary());
                row.put("hasYardstick", t.hasYardstick());
                row.put("priorRunState", t.priorRunState());
                row.put("modes", t.supportedModes().stream().map(Enum::name).toList());
                row.put("current", t.name().equals(body.get("target")));
                rows.add(row);
            }
            body.put("targets", rows);
        }
        return ResponseEntity.ok(body);
    }

    /**
     * T4-DP slice 4: release a held run and start it in the chosen mode.
     *
     * <p>Only a held run can be released. A start mode is chosen once, so a run that is already
     * going says so rather than half-applying a second one.
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start(@RequestParam String mode) {
        com.walpro.autobuilder.core.init.StartMode requested;
        try {
            requested = com.walpro.autobuilder.core.init.StartMode
                    .valueOf(mode.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false,
                    "message", "mode must be one of "
                            + java.util.Arrays.toString(com.walpro.autobuilder.core.init.StartMode.values())));
        }
        try {
            var decision = autobuilderService.startNow(requested);
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("ok", decision.allowed());
            body.put("mode", decision.mode().name());
            body.put("situation", decision.situation().name());
            body.put("message", decision.explanation());
            return decision.allowed() ? ResponseEntity.ok(body) : ResponseEntity.badRequest().body(body);
        } catch (java.io.IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("ok", false,
                    "message", "the start failed part-way: " + e.getMessage()));
        }
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.goal.GoalPlannerService goalPlannerServiceForGoal;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.core.service.FileService fileServiceForGoal;

    /**
     * T4-CQ: give an adopted project a goal.
     *
     * <p>An adopted codebase deliberately gets no goal at startup — inventing one is the
     * circularity the import ticket warns about, where the loop writes a specification and then
     * grades itself against it. But that left no way to supply one either, so an adopted project
     * could be read and never worked on. This is the way.
     *
     * <p>Written to {@code bootstrap/seed.txt} as well as set live, so the goal is durable, is
     * visible in the same place every other target keeps its specification, and a restart
     * reproduces this run rather than starting goalless again.
     *
     * <p>Refuses when a root goal already exists. Replacing the purpose of a run that is already
     * pursuing one is not an edit, it is a different run.
     */
    @PostMapping(value = "/goal", consumes = org.springframework.http.MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<Map<String, Object>> setGoal(@RequestBody String goalText) {
        if (goalPlannerServiceForGoal == null || fileServiceForGoal == null) {
            return ResponseEntity.status(503).body(Map.of("ok", false,
                    "message", "Goal setting is not available in this context."));
        }
        String text = goalText == null ? "" : goalText.strip();
        if (text.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false,
                    "message", "The goal text is empty."));
        }
        var existing = goalPlannerServiceForGoal.getRootGoal();
        if (existing != null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false,
                    "message", "This run already has a root goal (" + existing.getId()
                            + "). Replacing what a run is pursuing is a different run, not an edit."));
        }
        String goalId = "goal-" + java.util.UUID.randomUUID();
        try {
            fileServiceForGoal.ensureFolderExists("bootstrap");
            fileServiceForGoal.writeFile("bootstrap/seed.txt", text);
        } catch (java.io.IOException e) {
            // The live goal still gets set: losing the durable copy costs a restart, not the run.
            log.warn("T4-CQ: could not persist the goal to bootstrap/seed.txt: {}", e.getMessage());
        }
        goalPlannerServiceForGoal.addInitialGoal(goalId, text);
        autobuilderService.goalProvided();
        log.warn("T4-CQ: root goal set by request on an adopted project — {}", goalId);
        return ResponseEntity.ok(Map.of("ok", true, "goalId", goalId,
                "message", "Root goal set and written to bootstrap/seed.txt. The loop picks it up on "
                        + "its next cycle."));
    }

    /** T4-DQ: where the loop's state stands, as distinct from which point is newest on disk. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.core.checkpoint.CheckpointService checkpointService;

    private final com.walpro.autobuilder.core.init.RunIdentityService runIdentityService;

    /**
     * T5-R: session LLM token usage — cumulative totals plus a per-role breakdown. Tokens, not
     * dollars: the target runs a local model (project_local_first_constraint), so tokens are the
     * honest cost unit. {@code callsWithUsage} vs {@code calls} shows how many responses actually
     * carried a usage block (a provider that omits it lowers the ratio).
     */
    @GetMapping("/usage")
    public ResponseEntity<com.walpro.autobuilder.client.LlmUsageTracker.Snapshot> usage() {
        return ResponseEntity.ok(llmUsageTracker.snapshot());
    }

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
     * T4-DQ: the points this run can be returned to, newest first.
     *
     * <p>Only points that can actually be restored are listed — a checkpoint whose tree snapshot
     * has been cleared, or that was incomplete when taken, is not offered, because offering it
     * would be offering a restore that silently restores less than it claims.
     */
    @GetMapping("/checkpoints")
    public ResponseEntity<Map<String, Object>> checkpoints() {
        if (rewindService == null) {
            return ResponseEntity.ok(Map.of("ok", false, "message", "Checkpointing is not enabled."));
        }
        // The loop's position: after a rewind it is NOT the newest point, and this is the only
        // place that says so. Empty on a fresh JVM until its first checkpoint; the UI then treats
        // the newest point as the position, which is right in that case.
        var position = checkpointService != null
                ? checkpointService.position() : java.util.Optional.<com.walpro.autobuilder.core.checkpoint.CheckpointService.Position>empty();
        String hereCycle = position.map(com.walpro.autobuilder.core.checkpoint.CheckpointService.Position::cycleId).orElse(null);
        List<Map<String, Object>> points = new java.util.ArrayList<>();
        for (var record : rewindService.available()) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("cycleId", record.cycleId());
            row.put("at", String.valueOf(record.at()));
            row.put("merged", record.merged());
            row.put("currentSubGoalId", record.currentSubGoalId());
            row.put("goalTransitionSeq", record.goalTransitionSeq());
            row.put("holders", record.holders().size());
            // T4-DQ: which run and lineage the point belongs to. Checkpoints are run-scoped and
            // cleared on reset and on resume, so today this is one run — but the grouping is a
            // fact about the data rather than a guess, and it stops the UI implying otherwise.
            row.put("runId", record.runId());
            row.put("lineageId", record.lineageId());
            row.put("verified", record.fingerprint() != null);
            // Whether the point belongs to the run now in progress, so the UI can mark the ones
            // that will restore from an archived tree rather than from this run's own snapshot.
            row.put("current", java.util.Objects.equals(record.runId(), runIdentityService.runId()));
            // Whether it predates a reset. Returning to such a point adopts work from a lineage the
            // loop no longer continues, which is a bigger step than crossing a run boundary and so
            // is named separately rather than folded into "earlier run".
            row.put("sameLineage",
                    java.util.Objects.equals(record.lineageId(), runIdentityService.lineageId()));
            // The point the loop's state currently corresponds to — where "continue" continues from.
            row.put("here", record.cycleId().equals(hereCycle));
            // Restorable and continuable are different facts. A point whose root goal was already
            // settled restores perfectly and then halts on the next cycle, so it is shown with what
            // it will do rather than hidden — a person may deliberately want the finished state.
            if (checkpointService != null) {
                row.put("continuable", checkpointService.canContinueFrom(record));
                row.put("rootGoalStatus", checkpointService.rootGoalStatusAt(record)
                        .map(Enum::name).orElse(null));
                // Whether the point carries the cycle context it was taken with. Without it the
                // rewind settles from the current context, so streaks and relayed observations
                // describe the path being backed out of rather than the one being returned to.
                row.put("carriedContext", checkpointService.hasCarriedContext(record));
            }
            points.add(row);
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("ok", true);
        body.put("count", points.size());
        body.put("checkpoints", points);
        position.ifPresent(pos -> {
            Map<String, Object> at = new java.util.LinkedHashMap<>();
            at.put("cycleId", pos.cycleId());
            at.put("since", String.valueOf(pos.since()));
            at.put("rewound", pos.rewound());
            at.put("fromCycleId", pos.fromCycleId());
            body.put("position", at);
        });
        return ResponseEntity.ok(body);
    }

    /**
     * T4-DQ: return the loop to an earlier point — the operator's hand on the same capability the
     * rewind policy uses on its own.
     *
     * <p>{@code toCycle} names a checkpoint; {@code back} counts backwards from the newest instead
     * ({@code back=2} is two points before the latest). {@code cacheMode} is FAITHFUL (the
     * resolution cache comes back with the state) or FRESH (it is cleared) — running the same
     * rewind both ways is how we find out whether that cache is decision-bearing at all.
     *
     * <p><b>Serialised against the control loop.</b> A rewind replaces {@code output/} and rewrites
     * goal state, so a cycle running underneath it would be working on a tree changing beneath it.
     * It runs under the same lock a cycle takes rather than asking the operator to pause first:
     * "paused" and "not currently cycling" are different states, and with the scheduler disabled
     * the loop is the second without ever being the first.
     */
    @PostMapping("/rewind")
    public ResponseEntity<Map<String, Object>> rewind(
            @RequestParam(required = false) String toCycle,
            @RequestParam(required = false, defaultValue = "0") int back,
            @RequestParam(required = false, defaultValue = "FAITHFUL") String cacheMode) {
        if (rewindService == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "message", "Checkpointing is not enabled."));
        }
        var available = rewindService.available();
        if (available.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "message", "No restorable checkpoint on disk."));
        }
        String target = toCycle;
        if (target == null || target.isBlank()) {
            int index = Math.max(0, back);
            if (index >= available.size()) {
                return ResponseEntity.badRequest().body(Map.of("ok", false, "message",
                        "Only " + available.size() + " restorable point(s); cannot go back " + index + "."));
            }
            target = available.get(index).cycleId();
        }
        com.walpro.autobuilder.core.checkpoint.CacheMode mode;
        try {
            mode = com.walpro.autobuilder.core.checkpoint.CacheMode.valueOf(cacheMode.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "message", "cacheMode must be FAITHFUL or FRESH."));
        }

        // Through the service, not straight to the RewindService: the restore is only half of a
        // rewind. The carried cycle context has to be settled too, or the next tick re-processes
        // the result of the cycle that was just backed out of and pauses the loop again.
        var outcome = autobuilderService.rewindAsOperator(target, mode);
        if (outcome.isEmpty()) {
            return ResponseEntity.status(409).body(Map.of("ok", false, "message",
                    "A cycle is running — could not take the control-loop lock. Retry, or "
                            + "POST /autobuilder/pause first."));
        }
        var result = outcome.get();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("ok", result.performed());
        body.put("toCycleId", result.toCycleId());
        body.put("cacheMode", cacheMode.toUpperCase(java.util.Locale.ROOT));
        body.put("carriedContext", result.carriedContextSource());
        if (!result.performed()) {
            body.put("refusedBecause", result.refusedBecause());
            return ResponseEntity.badRequest().body(body);
        }
        body.put("holdersRestored", result.holdersRestored());
        body.put("holdersReset", result.holdersReset());
        body.put("goalStatusesRestored", result.goalStatusesRestored());
        body.put("goalsRewoundOut", result.goalsRewoundOut());
        body.put("goalTransitionsUndone", result.goalTransitionsUndone());
        // Never hidden: a rewind that restored less than it claims must say so here.
        body.put("clean", result.clean());
        body.put("failures", result.failures());
        // Which state the loop is left in, so the caller does not have to guess whether to resume.
        boolean paused = autobuilderService.userPaused();
        body.put("paused", paused);
        body.put("message", "Returned to " + result.toCycleId() + (paused
                ? ". The loop stays paused because you paused it; POST /autobuilder/resume to continue from there."
                : ". The loop continues from there on its next tick."));
        return ResponseEntity.ok(body);
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
        strength = Math.max(0.0, Math.min(com.walpro.autobuilder.core.lifecycle.ChannelRequest.MAX_URGENCY, strength));
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
            // T4-BZ-5 (Q-d): a computed fact cannot be injected — the operator pokes the
            // underlying state instead (build outcome, scope-guard result, goal tree, ...).
            return ResponseEntity.badRequest().body(Map.of("ok", false,
                    "message", "Signal " + type.name() + " is a DERIVED fact — computed from context state, not injectable."));
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
