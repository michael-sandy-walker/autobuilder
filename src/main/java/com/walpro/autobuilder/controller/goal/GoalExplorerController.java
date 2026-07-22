package com.walpro.autobuilder.controller.goal;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * T4-AK: read-only goal graph explorer REST layer.
 *
 * <p>T4-AM extension: adds {@code GET /stream} for the live SSE feed plus a set of write
 * actions (pin, inject, force-given-up, update-description, mark criterion met) so the
 * 3D graph browser can steer the loop in real time.
 */
@RestController
@RequestMapping("/autobuilder/goals")
@RequiredArgsConstructor
public class GoalExplorerController {

    private final GoalExplorerService goalExplorerService;
    private final com.walpro.autobuilder.goal.ConnectionCompatibilityService connectionCompatibilityService;

    /**
     * T4-AM: publisher is optional so unit tests that instantiate this controller directly
     * (and that don't exercise the SSE endpoint) can skip wiring it.
     */
    @Autowired(required = false)
    private GoalStateEventPublisher goalStateEventPublisher;

    /** Wire or override for tests. */
    public void setGoalStateEventPublisher(GoalStateEventPublisher publisher) {
        this.goalStateEventPublisher = publisher;
    }

    // =======================================================================================
    // T4-AK: read-only endpoints
    // =======================================================================================

    /**
     * T4-CN slice 1: answers whether the two goals can connect as a goal↔goal merge — the
     * kind of connection, whether it is allowed, why, and the fold's dry-run consequences.
     * A refusal is a feedback payload, not an error: always 200 with the check's answer.
     */
    @GetMapping("/connection-check")
    public ResponseEntity<com.walpro.autobuilder.goal.ConnectionCompatibilityService.ConnectionCheckResult> connectionCheck(
            @RequestParam("a") String nodeAId,
            @RequestParam("b") String nodeBId) {
        return ResponseEntity.ok(connectionCompatibilityService.checkConnection(nodeAId, nodeBId));
    }

    /** Full goal tree (recursive) rooted at the current root goal. */
    @GetMapping("/tree")
    public ResponseEntity<GoalTreeNodeDto> tree() {
        GoalTreeNodeDto tree = goalExplorerService.getGoalTree();
        return tree != null ? ResponseEntity.ok(tree) : ResponseEntity.noContent().build();
    }

    /** Per-node detail: metadata, criteria, merged files, ancestry, plan rationale. */
    @GetMapping("/{goalId}")
    public ResponseEntity<GoalNodeDetailDto> detail(@PathVariable String goalId) {
        GoalNodeDetailDto detail = goalExplorerService.getGoalDetail(goalId);
        return detail != null ? ResponseEntity.ok(detail) : ResponseEntity.notFound().build();
    }

    /** Per-criterion status for a subgoal: cumulative pass, fail streak, and graph history. */
    @GetMapping("/{goalId}/criteria")
    public ResponseEntity<List<CriterionStatusDto>> criteria(@PathVariable String goalId) {
        List<CriterionStatusDto> criteria = goalExplorerService.getCriteria(goalId);
        return ResponseEntity.ok(criteria);
    }

    /**
     * Mutation cycles that targeted this goal. Each entry lists the per-cycle relative file
     * paths so the in-browser editor can open them directly.
     */
    @GetMapping("/{goalId}/cycles")
    public ResponseEntity<List<GoalCycleDto>> cyclesForGoal(@PathVariable String goalId) {
        return ResponseEntity.ok(goalExplorerService.getCyclesForGoal(goalId));
    }

    /** Cross-goal synergy candidates — criteria on sibling or cousin subgoals that overlap. */
    @GetMapping("/{goalId}/synergies")
    public ResponseEntity<List<SynergyCandidateDto>> synergies(@PathVariable String goalId) {
        List<SynergyCandidateDto> candidates = goalExplorerService.findSynergies(goalId);
        return ResponseEntity.ok(candidates);
    }

    /** Cross-goal file overlay — files touched by two or more subgoals' accepted mutations. */
    @GetMapping("/file-overlay")
    public ResponseEntity<List<FileOverlayDto>> fileOverlay() {
        return ResponseEntity.ok(goalExplorerService.getFileOverlay());
    }

    /**
     * Absolute paths for the evolving project, its {@code mutations/} and {@code meta/}
     * subfolders, and the list of per-cycle mutation directory names. Consumed by the 3D
     * browser's Locations panel to render {@code idea://open?file=…} shortcuts.
     */
    @GetMapping("/locations")
    public ResponseEntity<LocationsDto> locations() {
        return ResponseEntity.ok(goalExplorerService.getLocations());
    }

    // =======================================================================================
    // T4-AM: live SSE stream
    // =======================================================================================

    /**
     * T4-AM: opens a Server-Sent-Events stream that pushes {@code cycle-end} and
     * {@code tree-changed} events. The first message (named {@code hello}) carries the
     * current full snapshot so the client can seed its view without an extra round trip.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream() {
        if (goalStateEventPublisher == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok(goalStateEventPublisher.register());
    }

    // =======================================================================================
    // T4-AM: write actions
    // =======================================================================================

    /** Marks the specified acceptance criterion as cumulatively met. */
    @PostMapping("/{goalId}/criteria/{index}/mark-met")
    public ResponseEntity<ActionResultDto> markCriterionMet(
            @PathVariable String goalId,
            @PathVariable int index,
            @RequestParam(value = "reason", required = false) String reason
    ) {
        ActionResultDto r = goalExplorerService.markCriterionMet(goalId, index, reason);
        return ok(r);
    }

    /** Replaces the text of an existing criterion (1-based index). */
    @PatchMapping("/{goalId}/criteria/{index}")
    public ResponseEntity<ActionResultDto> updateCriterionText(
            @PathVariable String goalId,
            @PathVariable int index,
            @RequestBody java.util.Map<String, String> body
    ) {
        ActionResultDto r = goalExplorerService.updateCriterionText(goalId, index, body.get("text"));
        return ok(r);
    }

    /** Removes a criterion by 1-based index; resets pass/fail history. */
    @DeleteMapping("/{goalId}/criteria/{index}")
    public ResponseEntity<ActionResultDto> deleteCriterion(
            @PathVariable String goalId,
            @PathVariable int index
    ) {
        ActionResultDto r = goalExplorerService.deleteCriterion(goalId, index);
        return ok(r);
    }

    /** Appends a new criterion to the goal's acceptance list. */
    @PostMapping("/{goalId}/criteria")
    public ResponseEntity<ActionResultDto> addCriterion(
            @PathVariable String goalId,
            @RequestBody java.util.Map<String, String> body
    ) {
        ActionResultDto r = goalExplorerService.addCriterion(goalId, body.get("text"));
        return ok(r);
    }

    /** Pins the subgoal as the current work target and emits REPLAN_REQUESTED. */
    @PostMapping("/{goalId}/pin")
    public ResponseEntity<ActionResultDto> pin(
            @PathVariable String goalId,
            @RequestParam(value = "reason", required = false) String reason
    ) {
        ActionResultDto r = goalExplorerService.pinAsPriority(goalId, reason);
        return ok(r);
    }

    /** Injects a new subgoal under the given parent. */
    @PostMapping("/{parentId}/inject-subgoal")
    public ResponseEntity<ActionResultDto> injectSubgoal(
            @PathVariable String parentId,
            @RequestBody GoalInjectSubgoalRequest request
    ) {
        ActionResultDto r = goalExplorerService.injectSubgoal(parentId, request);
        return ok(r);
    }

    /** Forces the goal into the GIVEN_UP terminal state. */
    @PostMapping("/{goalId}/force-given-up")
    public ResponseEntity<ActionResultDto> forceGivenUp(
            @PathVariable String goalId,
            @RequestBody(required = false) GoalForceGivenUpRequest request
    ) {
        ActionResultDto r = goalExplorerService.forceGivenUp(
                goalId, request != null ? request.resolution() : null
        );
        return ok(r);
    }

    /**
     * Flips a COMPLETED / GIVEN_UP goal back to ACTIVE, cascading up to terminal ancestors
     * so the node is actually reachable. Clears SYSTEM_PAUSED + GOAL_EXHAUSTED and emits
     * REPLAN_REQUESTED so the loop resumes. {@code setAsCurrent} (default false) pins the
     * reactivated node as the planner's current subgoal when it is a SUBGOAL.
     */
    @PostMapping("/{goalId}/reactivate")
    public ResponseEntity<ActionResultDto> reactivate(
            @PathVariable String goalId,
            @RequestBody(required = false) GoalReactivateRequest request
    ) {
        ActionResultDto r = goalExplorerService.reactivate(
                goalId, request != null && request.setAsCurrent()
        );
        return ok(r);
    }

    /** Updates the goal's description in-place. */
    @PatchMapping("/{goalId}")
    public ResponseEntity<ActionResultDto> updateDescription(
            @PathVariable String goalId,
            @RequestBody GoalUpdateDescriptionRequest request
    ) {
        ActionResultDto r = goalExplorerService.updateDescription(
                goalId, request != null ? request.description() : null
        );
        return ok(r);
    }

    /**
     * Convenience alias for {@link #forceGivenUp} — lets the 3D UI offer a DELETE-style button
     * that maps to the same state transition. Always forces GIVEN_UP rather than removing the
     * node from the graph; history is preserved.
     */
    @DeleteMapping("/{goalId}")
    public ResponseEntity<ActionResultDto> abandon(@PathVariable String goalId) {
        ActionResultDto r = goalExplorerService.forceGivenUp(goalId, "abandoned via DELETE");
        return ok(r);
    }

    private static ResponseEntity<ActionResultDto> ok(ActionResultDto r) {
        if (r == null) return ResponseEntity.internalServerError().build();
        if (r.ok()) return ResponseEntity.ok(r);
        // T1-FD: refused because a cycle holds the goal-state lock — retryable, not a bad request.
        return ResponseEntity.status(r.isBusy() ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST).body(r);
    }
}
