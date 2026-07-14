package com.walpro.autobuilder.protocol.evolution;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.merge.PackageFragmentationReport;

/**
 * T4-BA: formatter for the {@code --- PACKAGE FRAGMENTATION REJECTED ---} evolution prompt
 * block. Rendered when the previous cycle's mutation tried to declare an already-existing type
 * in a second package and the gate rejected it. Returns {@code null} when no fragmentation
 * observation is carried — single-cycle TTL enforced by {@code ContextLineageTracker}.
 *
 * <p>This is per-cycle <i>placement</i> guidance for the evolution agent, not a standing
 * prohibition: it tells the LLM where the type already lives so it can place this cycle's code
 * in the existing package instead of re-proposing the identical type-in-two-packages split (the
 * LLM runs at temperature 0.0, so without the reminder it repeats the split). Whether the
 * underlying subgoal should be abandoned is decided separately by the capability-gap path
 * (T2-DZ), not here.
 *
 * <p>Mirrors {@link com.walpro.autobuilder.protocol.build.RolledBackPromptBlock}.
 */
public final class PackageFragmentationPromptBlock {

    private PackageFragmentationPromptBlock() {}

    public static String render(CycleContextData ctx) {
        if (ctx == null) return null;
        PackageFragmentationReport ev = ctx.packageFragmentation();
        if (ev == null || ev.conflicts() == null || ev.conflicts().isEmpty()) return null;

        StringBuilder sb = new StringBuilder("--- PACKAGE FRAGMENTATION REJECTED ---\n");
        sb.append("Previous cycle: ").append(ev.cycleId()).append("\n");
        sb.append("Your previous mutation was REJECTED and NOT merged. It declared one or more ")
          .append("types that already exist under a different package. A simple type name must ")
          .append("map to exactly one package — duplicating it produces two distinct types that ")
          .append("compile cleanly but break runtime dispatch (instanceof checks, exception → ")
          .append("HTTP status mapping).\n");
        sb.append("Conflicts (").append(ev.conflicts().size()).append("):\n");
        for (PackageFragmentationReport.Conflict c : ev.conflicts()) {
            sb.append("  - ").append(c.typeName())
              .append(": already defined at ").append(c.existingPath())
              .append("; rejected duplicate at ").append(c.attemptedPath()).append("\n");
        }
        sb.append("This cycle: place code that needs each type in the existing package, or ")
          .append("import the existing type from where it already lives. Do NOT create a second ")
          .append("copy of any of these types in another package.");
        return sb.toString();
    }
}
