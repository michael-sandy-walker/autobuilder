package com.walpro.autobuilder.core.build.inject;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.analysis.structure.JavaSources;
import com.walpro.autobuilder.core.build.BuildErrorClassifier;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T6-T: a method declared twice in one type -- the second copy written by the change -- is declared once: the copy the
 * compiler reports (the later one) stays, the earlier ones go.
 *
 * <p><b>The gap, from the field.</b> Ladder-grind run 2026-10-14_00-22-30: 15 of the 17 candidates after minute 72
 * failed to compile because they added a second copy of a helper the 1,121-line file already had -- the file was shown
 * in parts and the helper was not among them ("method isInAnonymousClass(MethodDeclaration) is already defined").
 * Duplicate definitions were 28% of that run's compiler errors and appeared in all six runs of that case that week.
 * Each such candidate carried a version of the helper the model had just written for the failing test, and lost it.
 *
 * <p><b>Which copy stays.</b> The one the compiler names: javac reports a duplicate at the later declaration, which
 * is the one the change wrote when it appended its version; that copy is kept and the earlier same-signature ones
 * are removed with their comments. Only a METHOD declared twice in one type is repaired here -- a variable or a case
 * label declared twice is the code's own logic and is left to the model.
 */
@Slf4j
@Component
@Order(16)
public class DuplicateMethodResolver implements CompileFixResolver {

    private static final Pattern DUPLICATE_METHOD = Pattern.compile(
            "(?m)^\\s*(?:\\[ERROR\\]\\s*)?(\\S+\\.java):(?:\\[(\\d+),\\d+\\]|(\\d+):\\s*error:)\\s*method\\s+(\\w+)\\(([^)]*)\\)"
                    + "\\s+is already defined in (?:class|interface|enum|record)\\s+([\\w.$]+)");

    @Override
    public Optional<FixProposal> resolve(Classification classification, String missingToken, String stderr,
                                         CycleContextData context) {
        if (stderr == null || context == null || context.outputPath() == null) return Optional.empty();
        Path root = Path.of(context.outputPath());
        String plain = BuildErrorClassifier.ANSI_ESCAPE.matcher(stderr).replaceAll("");

        Map<String, String> edited = new LinkedHashMap<>();
        List<String> summaries = new ArrayList<>();
        String first = null;
        // One method may be reported several times -- the change wrote it more than once (field run 2026-10-14_00-22-30:
        // the same helper at lines 1021, 1035 and 1049). The last copy is the one kept.
        Map<String, Object[]> reported = new LinkedHashMap<>();
        Matcher m = DUPLICATE_METHOD.matcher(plain);
        while (m.find()) {
            String rel = DeclaredAccessorResolver.relative(m.group(1), root);
            int line = Integer.parseInt(m.group(2) != null ? m.group(2) : m.group(3));
            String key = rel + "|" + m.group(6) + "|" + m.group(4) + "|" + m.group(5);
            Object[] prior = reported.get(key);
            if (prior == null || (int) prior[1] < line) {
                reported.put(key, new Object[]{rel, line, m.group(4), simpleTypes(m.group(5)),
                        m.group(6).substring(m.group(6).lastIndexOf('.') + 1).replace('$', '.')});
            }
        }
        for (Object[] r : reported.values()) {
            String rel = (String) r[0];
            int line = (int) r[1];
            String method = (String) r[2];
            @SuppressWarnings("unchecked")
            List<String> params = (List<String>) r[3];
            String owner = (String) r[4];
            String source = edited.containsKey(rel) ? edited.get(rel) : read(root.resolve(rel));
            if (source == null) continue;
            String once = declaredOnce(source, owner, method, params, line);
            if (once == null || once.equals(source)) continue;
            edited.put(rel, once);
            if (first == null) first = method;
            summaries.add(owner + "." + method + "(" + String.join(", ", params) + ") in " + rel);
        }
        if (first == null) return Optional.empty();

        List<FileMutation> mutations = new ArrayList<>();
        edited.forEach((path, content) -> mutations.add(new FileMutation(path, content)));
        String description = "Kept the copy the change wrote of a method declared twice: " + String.join("; ", summaries);
        log.info("CompileFixInjector: duplicate-method -- {}", description);
        return Optional.of(new FixProposal(mutations, new InjectorFixRecord("duplicate-method:" + first,
                classification == null ? Kind.UNKNOWN : classification.kind(), first, List.copyOf(edited.keySet()), description)));
    }

    /**
     * {@code source} with every declaration of {@code method(params)} in the type {@code owner} removed but the one
     * starting at or containing {@code keepLine} (the last one when none does); null when there are not two.
     */
    static String declaredOnce(String source, String owner, String method, List<String> params, int keepLine) {
        CompilationUnit unit = JavaSources.parser().parse(source).getResult().orElse(null);
        if (unit == null) return null;
        List<MethodDeclaration> copies = new ArrayList<>();
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            if (!type.getNameAsString().equals(owner.substring(owner.lastIndexOf('.') + 1))) continue;
            for (MethodDeclaration md : type.getMethodsByName(method)) {
                List<String> types = md.getParameters().stream().map(p -> simple(p.getTypeAsString())).toList();
                if (types.equals(params)) copies.add(md);
            }
        }
        if (copies.size() < 2) return null;
        MethodDeclaration keep = copies.stream().filter(md -> md.getBegin().map(b -> b.line).orElse(0) <= keepLine
                        && md.getEnd().map(e -> e.line).orElse(0) >= keepLine).findFirst()
                .orElse(copies.get(copies.size() - 1));
        String[] lines = source.split("\n", -1);
        boolean[] drop = new boolean[lines.length + 1];
        for (MethodDeclaration md : copies) {
            if (md == keep) continue;
            int from = md.getComment().flatMap(c -> c.getBegin()).map(b -> b.line).orElse(md.getBegin().map(b -> b.line).orElse(0));
            int to = md.getEnd().map(e -> e.line).orElse(0);
            if (from < 1 || to < from || to > lines.length) return null;
            for (int i = from; i <= to; i++) drop[i] = true;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 1; i <= lines.length; i++) {
            if (drop[i]) continue;
            out.append(lines[i - 1]);
            if (i < lines.length) out.append('\n');
        }
        return out.toString();
    }

    /** The simple names of a javac parameter list: {@code com.x.Node, java.util.List<java.lang.String>} -> {@code Node, List}. */
    static List<String> simpleTypes(String params) {
        if (params == null || params.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char ch : params.toCharArray()) {
            if (ch == '<') depth++;
            if (ch == '>') depth--;
            if (ch == ',' && depth == 0) {
                out.add(simple(cur.toString()));
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(simple(cur.toString()));
        return out;
    }

    private static String simple(String type) {
        String t = type.strip();
        int generic = t.indexOf('<');
        String suffix = t.endsWith("...") ? "..." : t.endsWith("[]") ? "[]" : "";
        if (generic >= 0) t = t.substring(0, generic);
        t = t.replace("...", "").replace("[]", "");
        String[] parts = t.split("\\.");
        return Arrays.stream(parts).reduce((a, b) -> b).orElse(t) + suffix;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            return null;
        }
    }
}
