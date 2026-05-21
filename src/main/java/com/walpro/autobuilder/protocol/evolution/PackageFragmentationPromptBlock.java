package com.walpro.autobuilder.protocol.evolution;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.merge.PackageFragmentationEvidence;

/**
 * T4-BA: formatter for the {@code --- PACKAGE FRAGMENTATION REJECTED ---} evolution prompt
 * block. Rendered when the previous cycle's MergeAgent guard rejected a mutation that would
 * have declared an already-existing type in a second package. Returns {@code null} when no
 * fragmentation evidence is carried — single-cycle TTL enforced by {@code ContextLineageTracker}.
 *
 * <p>Mirrors {@link com.walpro.autobuilder.protocol.build.RolledBackPromptBlock}: the LLM
 * runs at temperature 0.0, so without an explicit record of the rejection it re-proposes the
 * identical type-in-two-packages split every cycle.
 */
public final class PackageFragmentationPromptBlock {

    private PackageFragmentationPromptBlock() {}

    public static String render(CycleContextData ctx) {
        if (ctx == null) return null;
        PackageFragmentationEvidence ev = ctx.packageFragmentation();
        if (ev == null || ev.conflicts() == null || ev.conflicts().isEmpty()) return null;

        StringBuilder sb = new StringBuilder("--- PACKAGE FRAGMENTATION REJECTED ---\n");
        sb.append("Previous cycle: ").append(ev.cycleId()).append("\n");
        sb.append("Your previous mutation was REJECTED and NOT merged. It declared one or more ")
          .append("types that already exist under a different package. A simple type name must ")
          .append("map to exactly one package — duplicating it produces two distinct types that ")
          .append("compile cleanly but break runtime dispatch (instanceof checks, exception → ")
          .append("HTTP status mapping).\n");
        sb.append("Conflicts (").append(ev.conflicts().size()).append("):\n");
        for (PackageFragmentationEvidence.Conflict c : ev.conflicts()) {
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
