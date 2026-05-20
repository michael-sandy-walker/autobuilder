package com.walpro.autobuilder.protocol.abandoned;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.AbandonedGoalCluster;

/**
 * T2-DL: shared renderer for the {@code --- RELATED ABANDONED WORK ---} prompt block.
 * Used by analysis (diagnostic framing) and planner (action framing) so agents see
 * prior attempts — live islands, merge-bearing descendants, orphaned merged files —
 * before proposing new work in an area that has already been abandoned once.
 */
public final class RelatedAbandonedWorkBlock {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public enum Audience { ANALYSIS, PLANNER }

    private RelatedAbandonedWorkBlock() {}

    public static String render(CycleContextData ctx, Audience audience) {
        if (ctx == null) return null;
        List<AbandonedGoalCluster> clusters = ctx.relatedAbandonedGoals();
        if (clusters == null || clusters.isEmpty()) return null;

        StringBuilder sb = new StringBuilder("--- RELATED ABANDONED WORK ---\n");
        sb.append(switch (audience) {
            case ANALYSIS -> "Prior GIVEN_UP subgoal clusters textually related to current work. "
                    + "Use this to diagnose whether the current ask is a continuation, a repeat, or a genuine gap.\n";
            case PLANNER -> "Prior GIVEN_UP clusters near the current planning surface. Before appending a "
                    + "new subgoal here, consider whether one of these clusters should be reactivated, refined, "
                    + "or removed to make room for a replacement.\n";
        });

        for (int i = 0; i < clusters.size(); i++) {
            AbandonedGoalCluster cluster = clusters.get(i);
            GoalEntity root = cluster.root();
            sb.append("\n[Cluster ").append(i + 1).append("] ");
            sb.append(root.getId()).append(" — ").append(root.getDescription());
            sb.append("\n  GIVEN_UP");
            Instant when = cluster.givenUpAt();
            if (when != null) sb.append(" at ").append(TS.format(when));
            if (cluster.resolution() != null && !cluster.resolution().isBlank()) {
                sb.append(" — ").append(cluster.resolution());
            }
            sb.append("\n");

            List<GoalEntity> active = cluster.activeDescendants();
            if (active != null && !active.isEmpty()) {
                sb.append("  Live islands (still ACTIVE within cluster):\n");
                for (GoalEntity g : active) {
                    sb.append("    - ").append(g.getId()).append(": ").append(g.getDescription()).append("\n");
                }
            }

            List<GoalEntity> mergeBearing = cluster.mergeBearingDescendants();
            if (mergeBearing != null && !mergeBearing.isEmpty()) {
                sb.append("  Merge-bearing descendants (have produced merges before):\n");
                for (GoalEntity g : mergeBearing) {
                    sb.append("    - ").append(g.getId())
                      .append(" [").append(g.getStatus()).append(", ")
                      .append(g.getMergeCount())
                      .append(g.getMergeCount() == 1 ? " merge" : " merges")
                      .append("]: ").append(g.getDescription()).append("\n");
                }
            }

            List<String> files = cluster.mergedFiles();
            if (files != null && !files.isEmpty()) {
                sb.append("  Merged files still on disk under output/ (may be orphaned — no live goal owns them):\n");
                sb.append("    ").append(String.join(", ", files)).append("\n");
            }

            Map<Integer, Boolean> criteriaStatus = cluster.criteriaPassStatus();
            if (criteriaStatus != null && !criteriaStatus.isEmpty()) {
                long passed = criteriaStatus.values().stream().filter(Boolean.TRUE::equals).count();
                sb.append("  Criteria progress before give-up: ").append(passed)
                  .append(" / ").append(criteriaStatus.size()).append(" passed\n");
            }
        }

        sb.append(switch (audience) {
            case ANALYSIS -> "\nIf the current ask overlaps a cluster above, your Findings should name the overlap "
                    + "explicitly (which files exist, which criteria were satisfied) rather than re-describing the gap from scratch.\n";
            case PLANNER -> "\nWhen these clusters overlap the area you're planning in, prefer extending the merged files "
                    + "above over re-proposing new ones, and remove or reactivate stale subgoals rather than appending duplicates.\n";
        });

        return sb.toString();
    }
}
