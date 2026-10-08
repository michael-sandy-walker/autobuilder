package com.walpro.autobuilder.protocol.evolution;

import com.walpro.autobuilder.core.build.DeclaredMembers;
import com.walpro.autobuilder.core.build.SkeletonStubber;
import com.walpro.autobuilder.protocol.PromptTokenBudget;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * T6-T, the hybrid's focus for a skeleton work item: the bodies still to write in ITS class -- read live, so a body
 * written drops out -- and the members of the project types its tests use, in short, so a body is written against
 * what exists and not against what a name suggests. The class and its tests are already shown whole by the source
 * block; this names the work inside them and what it may call.
 *
 * <p>Why. ladder-grind field51: with the skeleton items current, the model still wrote multi-class answers around the
 * GrindDetector item and invented {@code BuildOutcome.stderr()} 45 times beside the real {@code stderrTail()} -- the
 * members of the types the tests hand the class were nowhere in front of it as a list.
 *
 * <p>An answer may be the members it writes alone: they are applied member by member ({@link MemberMerge}).
 */
public final class SkeletonItemPromptBlock {

    private SkeletonItemPromptBlock() {
    }

    static final int MEMBERS_PER_TYPE = 20;
    private static final Pattern WORD = Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b");

    /**
     * @param type  the stubbed class the item is about (qualified)
     * @param tests the item's test classes (simple names)
     */
    public static String render(Path project, String type, List<String> tests, int maxTokens) {
        if (project == null || type == null || tests == null) return null;
        Path main = project.resolve("src/main/java");
        String simple = type.substring(type.lastIndexOf('.') + 1);
        Path file = main.resolve(type.replace('.', '/') + ".java");
        String source;
        try {
            source = Files.readString(file);
        } catch (Exception e) {
            return null;
        }
        List<String> stubs = new ArrayList<>();
        for (String line : source.split("\\R")) {
            if (line.contains("UnsupportedOperationException(\"" + SkeletonStubber.STUB_MESSAGE + "\")")) {
                stubs.add(line.strip().replaceFirst("\\s*\\{\\s*throw new UnsupportedOperationException\\(\"" + SkeletonStubber.STUB_MESSAGE + "\"\\);\\s*}\\s*$", ""));
            }
        }
        if (stubs.isEmpty()) return null;   // every body written: the item is the gate's to close
        // the project types the item's tests name, other than the class itself
        Set<String> used = new LinkedHashSet<>();
        StringBuilder mentioned = new StringBuilder(source);
        for (String t : tests) {
            try (Stream<Path> w = Files.walk(project.resolve("src/test/java"))) {
                Path test = w.filter(p -> p.getFileName().toString().equals(t + ".java")).findFirst().orElse(null);
                if (test == null) continue;
                String text = Files.readString(test);
                mentioned.append('\n').append(text);
                Matcher m = WORD.matcher(text);
                while (m.find()) used.add(m.group());
            } catch (Exception ignored) {
                // a test that cannot be read names nothing
            }
        }
        // and the types the class already refers to (its fields, parameters)
        Matcher own = WORD.matcher(source);
        while (own.find()) used.add(own.group());
        used.remove(simple);
        StringBuilder sb = new StringBuilder("--- THE BODIES TO WRITE IN ").append(simple).append(" ---\n")
                .append("These members of ").append(simple).append(" are still skeleton stubs; write their bodies (the tests ")
                .append(String.join(", ", tests)).append(" say what each must do). You may answer with just the members you"
                        + " write -- they replace the stubs of the same name, and everything else in the file stays as it is.\n");
        stubs.forEach(s -> sb.append("  - ").append(s).append("\n"));
        StringBuilder types = new StringBuilder();
        for (String name : used) {
            Path p = findType(main, name);
            if (p == null) continue;
            try {
                List<String> members = new ArrayList<>(DeclaredMembers.of(Files.readString(p), name));
                if (members.isEmpty()) continue;
                // the members the class or its tests already name come first: what the work is about, before the rest
                String all = mentioned.toString();
                members.sort(java.util.Comparator.comparingInt(m -> all.contains("." + m.substring(0, m.indexOf('(')) + "(") ? 0 : 1));
                List<String> shown = members.subList(0, Math.min(MEMBERS_PER_TYPE, members.size()));
                types.append("  ").append(name).append(": ").append(String.join(", ", shown))
                        .append(members.size() > shown.size() ? " and " + (members.size() - shown.size()) + " more" : "").append("\n");
            } catch (Exception ignored) {
                // an unreadable type offers nothing
            }
        }
        if (!types.isEmpty()) {
            sb.append("What the project's types the class and its tests use offer -- call these, not names they do not have:\n").append(types);
        }
        String out = sb.toString().stripTrailing();
        return PromptTokenBudget.estimateTokens(out) > Math.max(0, maxTokens) ? null : out;
    }

    private static Path findType(Path main, String simple) {
        if (!Files.isDirectory(main)) return null;
        try (Stream<Path> w = Files.walk(main)) {
            return w.filter(p -> p.getFileName().toString().equals(simple + ".java")).findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
