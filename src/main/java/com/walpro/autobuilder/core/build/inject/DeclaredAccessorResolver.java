package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildErrorClassifier;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.build.DeclaredMembers;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-CQ: rewrites a call to an accessor the receiver does not declare into the one it does --
 * {@code parsed.getType()} into {@code parsed.type()} when {@code parsed} is a project record whose
 * source declares {@code type()}.
 *
 * <p><b>Why deterministic.</b> structdiff run 2026-09-25_03-03-00 re-proposed a test calling
 * {@code getType()/getId()/getChildren()} on a record in 12 of 17 cycles, while its prompt listed the
 * record's members and paired each failed call with its replacement. Every fact the repair needs has
 * an authority the loop already holds: the compiler names the file, the line, the variable and its
 * type; the type's own source says what it declares. The model is not needed to copy one name over
 * another.
 *
 * <p><b>Narrow on purpose.</b> Only a no-argument method, only a receiver the compiler names as a
 * variable, only a type whose source is in the project, only when exactly one declared member is the
 * accessor's name (see {@link DeclaredMembers#meantBy}), and only on the reported line. Anything else
 * is left to the model and the prompt that already explains it.
 */
@Slf4j
@Component
@Order(18)
public class DeclaredAccessorResolver implements CompileFixResolver {

    /** Maven's {@code [ERROR] path:[line,col]} and javac's {@code path:line: error:} forms. */
    private static final Pattern MISSING_ACCESSOR = Pattern.compile(
            "(?m)^\\s*(?:\\[ERROR\\]\\s*)?(\\S+\\.java):(?:\\[(\\d+),\\d+\\]|(\\d+):\\s*error:)\\s*cannot find symbol\\s*\\R"
                    + "\\s*(?:\\[ERROR\\])?\\s*symbol:\\s*method\\s+(\\w+)\\(\\)\\s*\\R"
                    + "\\s*(?:\\[ERROR\\])?\\s*location:\\s*variable\\s+(\\w+)\\s+of\\s+type\\s+([\\w.$]+)");

    @Override
    public Optional<FixProposal> resolve(Classification classification, String missingToken, String stderr,
                                         CycleContextData context) {
        if (classification == null || classification.kind() != Kind.CANNOT_FIND_SYMBOL) return Optional.empty();
        if (stderr == null || context == null || context.outputPath() == null) return Optional.empty();
        Map<String, String> sources = context.previousOutput() != null ? context.previousOutput() : Map.of();
        Path root = Path.of(context.outputPath());
        String plain = BuildErrorClassifier.ANSI_ESCAPE.matcher(stderr).replaceAll("");

        Map<String, List<String>> edited = new LinkedHashMap<>();   // rel path -> current lines
        List<String> summaries = new ArrayList<>();
        String first = null;
        Matcher m = MISSING_ACCESSOR.matcher(plain);
        while (m.find()) {
            String rel = relative(m.group(1), root);
            int line = Integer.parseInt(m.group(2) != null ? m.group(2) : m.group(3));
            String member = m.group(4);
            String variable = m.group(5);
            String type = m.group(6);
            String simple = type.substring(type.lastIndexOf('.') + 1);

            String typeSource = sourceOf(simple, type, sources);
            if (typeSource == null) continue;                      // not the project's type: no authority here
            String meant = DeclaredMembers.meantBy(member, DeclaredMembers.of(typeSource, simple));
            if (meant == null || !meant.endsWith("()")) continue;   // only a no-argument accessor

            List<String> lines = edited.computeIfAbsent(rel, r -> read(root.resolve(r)));
            if (lines == null || line < 1 || line > lines.size()) continue;
            String before = lines.get(line - 1);
            String after = before.replaceAll("\\b" + Pattern.quote(variable) + "\\s*\\.\\s*" + Pattern.quote(member)
                    + "\\s*\\(\\s*\\)", Matcher.quoteReplacement(variable + "." + meant));
            if (after.equals(before)) continue;
            lines.set(line - 1, after);
            if (first == null) first = member;
            summaries.add(simple + "." + member + "() -> " + meant + " (" + rel + ":" + line + ")");
        }
        if (first == null) return Optional.empty();

        List<FileMutation> mutations = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : edited.entrySet()) {
            if (e.getValue() == null) continue;
            mutations.add(new FileMutation(e.getKey(), String.join("\n", e.getValue())));
            paths.add(e.getKey());
        }
        String description = "Called the declared accessor: " + String.join(", ", summaries);
        log.info("CompileFixInjector: declared-accessor -- {}", description);
        return Optional.of(new FixProposal(mutations,
                new InjectorFixRecord("declared-accessor:" + first, Kind.CANNOT_FIND_SYMBOL, first, paths, description)));
    }

    /** The error's path relative to the project root the probe writes into. */
    static String relative(String reported, Path root) {
        String p = reported.replace('\\', '/');
        String r = root.toString().replace('\\', '/');
        if (p.startsWith(r + "/")) return p.substring(r.length() + 1);
        int src = p.indexOf("/src/");
        return src >= 0 ? p.substring(src + 1) : p;
    }

    private static String sourceOf(String simple, String fqn, Map<String, String> sources) {
        String byPackage = "/" + fqn.replace('.', '/') + ".java";
        String bySimple = "/" + simple + ".java";
        String fallback = null;
        for (Map.Entry<String, String> e : sources.entrySet()) {
            String k = "/" + e.getKey().replace('\\', '/');
            if (k.endsWith(byPackage)) return e.getValue();
            if (k.endsWith(bySimple) && fallback == null) fallback = e.getValue();
        }
        return fallback;
    }

    private static List<String> read(Path file) {
        try {
            return new ArrayList<>(List.of(Files.readString(file).split("\n", -1)));
        } catch (Exception e) {
            return null;
        }
    }
}
