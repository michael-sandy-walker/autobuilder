package com.walpro.autobuilder.controller.goal;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * T4-AK: read-only REST layer for the goal graph explorer.
 *
 * <p>Read-only MVP — the T4-AK note calls out that a second pass could add write actions
 * (manually mark criterion met, pin a subgoal, inject a custom subgoal). Those are out of
 * scope for this endpoint group and should land in T4-AM.
 */
@RestController
@RequestMapping("/autobuilder/goals")
@RequiredArgsConstructor
public class GoalExplorerController {

    private final GoalExplorerService goalExplorerService;

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
}
