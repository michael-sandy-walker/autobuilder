package com.walpro.autobuilder.core.coldread;

import java.util.List;

/**
 * Renders a {@link ColdRead} for a person to check.
 *
 * <p>The reader's first question about a machine-written account of their own project is always
 * how much of it to believe, so what could not be established is printed as prominently as what
 * could, and never last where it reads as a footnote.
 */
public final class ColdReadReport {

    private ColdReadReport() {}

    public static String render(ColdRead r) {
        return render(r, null);
    }

    public static String render(ColdRead r, ColdReadVerification.Result v) {
        StringBuilder b = new StringBuilder();
        b.append("COLD READ of ").append(r.root()).append('\n');
        b.append("Everything below was read from the project. Nothing is inferred about what it ought to do.\n");

        if (!r.uncertainties().isEmpty()) {
            b.append("\nWHAT THIS READING COULD NOT ESTABLISH (").append(r.uncertainties().size()).append(")\n");
            for (String u : r.uncertainties()) b.append("  ! ").append(u).append('\n');
        }

        b.append("\nBUILD\n");
        ColdRead.Build build = r.build();
        if (build == null || build.tool() == null) {
            b.append("  (no build file found)\n");
        } else {
            line(b, "tool", build.tool());
            line(b, "artifact", build.artifact());
            line(b, "language level", build.javaVersion());
            line(b, "framework", build.framework());
            if (!build.dependencies().isEmpty()) {
                b.append("  dependencies   ").append(build.dependencies().size()).append('\n');
                for (String d : build.dependencies()) b.append("      ").append(d).append('\n');
            }
        }

        ColdRead.Runtime runtime = r.runtime();
        if (runtime != null && !runtime.settings().isEmpty()) {
            b.append("\nHOW IT IS SERVED\n");
            line(b, "context path", runtime.contextPath());
            line(b, "port", runtime.port());
            for (String s : runtime.settings()) b.append("      ").append(s).append('\n');
        }

        b.append("\nWHAT IT SAYS ABOUT ITSELF");
        if (r.prose().isEmpty()) {
            b.append("\n  (nothing: no README found)\n");
        } else {
            b.append(" (quoted from its own README)\n");
            for (String l : r.prose()) b.append("  | ").append(l).append('\n');
        }

        b.append("\nREQUEST SURFACE the code declares");
        b.append(r.surfaceComplete() ? "\n" : "  (INCOMPLETE — a mapping path is not a literal)\n");
        if (r.surface().isEmpty()) b.append("  (none found)\n");
        String prefix = r.runtime() == null ? null : r.runtime().contextPath();
        for (String s : r.surface()) {
            // Printed as it is actually reachable. The context path lives in configuration and the
            // controllers never mention it, so a surface listed off the annotations alone names
            // routes that answer 404 to anyone who tries them.
            if (prefix == null) { b.append("  ").append(s).append('\n'); continue; }
            int sp = s.indexOf(' ');
            b.append("  ").append(s, 0, sp + 1).append(prefix).append(s.substring(sp + 1)).append('\n');
        }
        if (prefix != null) {
            b.append("  (the ").append(prefix)
             .append(" prefix comes from configuration, not from the controllers)\n");
        }

        section(b, "TYPES", r.types());
        b.append("\nTESTS ALREADY PRESENT (").append(r.tests().size())
         .append(") — someone else's statement of intended behaviour\n");
        if (r.tests().isEmpty()) b.append("  (none)\n");
        for (String t : r.tests()) b.append("  ").append(t).append('\n');

        if (!r.skipped().isEmpty()) {
            b.append("\nNOT READ (").append(r.skipped().size()).append(")\n");
            for (String s : r.skipped()) b.append("  - ").append(s).append('\n');
        }

        if (v != null && !v.attempted()) {
            b.append("\nITS OWN TESTS: not run\n");
            for (String n : v.notes()) b.append("  - ").append(n).append('\n');
        }
        if (v != null && v.attempted()) {
            b.append("\nITS OWN TESTS, RUN (").append(v.durationMs() / 1000).append("s)\n");
            b.append("  builds        ").append(v.builds() ? "yes" : "NO").append('\n');
            b.append("  tests         ").append(v.testsRun()).append(" run, ")
             .append(v.testsFailed()).append(" not passing\n");
            for (String n : v.notes()) b.append("  - ").append(n).append('\n');
            b.append("  baseline      ").append(v.greenBaseline()
                    ? "GREEN — someone else's suite passes, so a change here has something to break"
                    : "not green — there is no regression baseline to work against yet").append('\n');
        }

        b.append("\nACTIONABLE: ").append(r.actionable()
                ? "yes — it builds and has a surface or tests to work against"
                : "no — too little was established to work from").append('\n');
        return b.toString();
    }

    private static void section(StringBuilder b, String title, List<String> lines) {
        b.append('\n').append(title).append(" (").append(lines.size()).append(")\n");
        if (lines.isEmpty()) b.append("  (none)\n");
        for (String l : lines) b.append("  ").append(l).append('\n');
    }

    private static void line(StringBuilder b, String label, String value) {
        if (value == null || value.isBlank()) return;
        b.append("  ").append(String.format("%-14s", label)).append(' ').append(value).append('\n');
    }
}
