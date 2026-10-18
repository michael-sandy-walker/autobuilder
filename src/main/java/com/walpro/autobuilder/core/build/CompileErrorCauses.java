package com.walpro.autobuilder.core.build;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A failed compile, told cause first: the errors in the files the failed change wrote are its causes; errors in files
 * it did not write follow from them and are set aside -- unless the change's own files have none, and then every error
 * is what the change broke elsewhere (it removed something others call), and all of them stay.
 *
 * <p><b>Why one home.</b> The rule first lived in {@link GrindDetector} alone (ladder-grind run 2026-10-10_19-22-18:
 * one real error stopped annotation processing, and every class that reads a generated member reported it missing).
 * The prompts kept the raw list. Rule-extraction run 2026-10-18_18-30-42: a candidate had one real error -- a method
 * declared twice -- and about twenty "cannot find symbol: variable log" in eight classes it never touched; the build
 * block showed the last twenty error lines, the planner made a work item of adding an annotation those classes already
 * had, and the model answered that nothing needed to change thirteen times until the run was stopped.
 */
public final class CompileErrorCauses {

    private static final Pattern ERROR_AT = Pattern.compile("([A-Za-z0-9_$]+\\.java):\\[(\\d+),\\d+]");
    private static final Pattern ORPHAN_DETAIL = Pattern.compile("^(?:\\[ERROR])?\\s{2,}(?:symbol|location):");

    /**
     * The compile output without the consequences' lines, how many distinct consequence errors were set aside, and the
     * files they were in.
     */
    public record Split(String causes, int consequences, List<String> consequenceFiles) {

        /** One line for a reader of {@link #causes}: what was set aside and why; null when nothing was. */
        public String note() {
            if (consequences == 0) return null;
            return consequences + " more error(s) in " + consequenceFiles.size() + " file(s) the change did not write ("
                    + String.join(", ", consequenceFiles) + ") follow from the errors above -- they go away with them;"
                    + " those files need no change of their own.";
        }
    }

    private CompileErrorCauses() {
    }

    /** {@code stderr} split by {@code changed} (paths of the files the failed change wrote); colour codes removed. */
    public static Split of(String stderr, Collection<String> changed) {
        if (stderr == null || stderr.isBlank()) return new Split(stderr, 0, List.of());
        String plain = BuildErrorClassifier.ANSI_ESCAPE.matcher(stderr).replaceAll("");
        if (changed == null || changed.isEmpty()) return new Split(plain, 0, List.of());
        Set<String> changedNames = new HashSet<>();
        for (String path : changed) {
            if (path != null) changedNames.add(path.substring(path.replace('\\', '/').lastIndexOf('/') + 1));
        }
        String[] lines = plain.split("\\R", -1);
        String[] fileOf = new String[lines.length];     // the file of the error a line belongs to, or null
        String current = null;
        boolean anyInChanged = false;
        for (int i = 0; i < lines.length; i++) {
            Matcher m = ERROR_AT.matcher(lines[i]);
            if (m.find()) {
                current = m.group(1);
                anyInChanged |= changedNames.contains(current);
            } else if (current != null && !continues(lines[i])) {
                current = null;
            }
            fileOf[i] = current;
        }
        if (!anyInChanged) return new Split(plain, 0, List.of());
        StringBuilder kept = new StringBuilder();
        Set<String> dropped = new LinkedHashSet<>();
        Set<String> files = new LinkedHashSet<>();
        for (int i = 0; i < lines.length; i++) {
            if (fileOf[i] != null && !changedNames.contains(fileOf[i])) {
                Matcher m = ERROR_AT.matcher(lines[i]);
                if (m.find()) {
                    dropped.add(m.group(1) + ":" + m.group(2));
                    files.add(m.group(1).replace(".java", ""));
                }
                continue;
            }
            // A symbol/location line whose error line was cut from the output (the build's own window omits a middle
            // part) belongs to no error that is kept: a cause's own lines always follow its error line.
            if (fileOf[i] == null && ORPHAN_DETAIL.matcher(lines[i]).find()) continue;
            kept.append(lines[i]);
            if (i < lines.length - 1) kept.append('\n');
        }
        return new Split(kept.toString(), dropped.size(), new ArrayList<>(files));
    }

    /** A line javac prints under an error -- its symbol, location, source excerpt or caret. */
    private static boolean continues(String line) {
        String body = line.startsWith("[ERROR]") ? line.substring("[ERROR]".length()) : line;
        return body.startsWith("  ") || body.isBlank() && !line.startsWith("[");
    }
}
