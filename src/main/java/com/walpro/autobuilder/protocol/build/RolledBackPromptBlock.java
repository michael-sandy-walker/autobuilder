package com.walpro.autobuilder.protocol.build;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.merge.RolledBackReport;

/**
 * T1-DX: shared formatter for the {@code --- LAST CYCLE ROLLED BACK ---} prompt block.
 * Rendered in both the planner and evolution prompts when the previous cycle's mutation
 * was rolled back by T1-DW. Returns {@code null} when no rollback observation is carried —
 * the rollback observation has a single-cycle TTL enforced by {@code ContextLineageTracker}.
 */
public final class RolledBackPromptBlock {

    private RolledBackPromptBlock() {}

    public static String render(CycleContextData ctx) {
        if (ctx == null) return null;
        RolledBackReport ev = ctx.lastRollback();
        if (ev == null) return null;

        StringBuilder sb = new StringBuilder("--- LAST CYCLE ROLLED BACK ---\n");
        sb.append("Previous cycle: ").append(ev.cycleId()).append("\n");
        sb.append("Reason: post-merge build failed (").append(ev.errorKind()).append(")\n");
        sb.append("Compile error: ").append(ev.stderrSummary()).append("\n");
        sb.append("Restored paths (")
                .append(ev.restoredPaths() == null ? 0 : ev.restoredPaths().size())
                .append("):\n");
        if (ev.restoredPaths() != null) {
            for (String p : ev.restoredPaths()) {
                sb.append("  - ").append(p).append("\n");
            }
        }
        sb.append("Your previous mutation was rolled back because it broke the build with the diagnostic above. ")
          .append("Choose a structurally different approach this cycle — do not re-propose the same files in the same shape. ")
          .append("If a missing build artefact (e.g. pom.xml) is the cause, address that first.");
        return sb.toString();
    }
}
