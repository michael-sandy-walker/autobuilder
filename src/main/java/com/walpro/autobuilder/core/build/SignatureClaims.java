package com.walpro.autobuilder.core.build;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.walpro.autobuilder.analysis.structure.JavaSources;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-EC: whether a criterion's WHOLE claim is that a class's existing public methods kept their signatures -- and
 * whether the class's text as adopted ({@link ProtectedPaths#BASELINE_DIR}) and its text now prove it.
 *
 * <p>The participation pair's guard subgoals ask "No existing public method in ParticipationWindows.java has been
 * renamed, removed, or had its signature changed" -- a question about declarations, which parsing answers and a
 * reading only predicts ([[project-invented-facts-have-authorities]]). Before the merge of the pair (2026-10-20)
 * nothing structural answered it.
 *
 * <p>Answers both ways: TRUE when every public method and constructor the class declared at adoption is declared
 * now, public, with the same parameter types; FALSE when one is missing; null when the claim is not this shape
 * (a claim about behaviour as well -- "in signature and behavior" -- is not), names no class, or the class has no
 * adopted text or does not parse.
 */
public final class SignatureClaims {

    private static final String CLASS = "`?([A-Z][A-Za-z0-9_]*)(?:\\.java)?`?";

    private static final Pattern NONE_CHANGED = Pattern.compile(
            "no existing public (?:method|member)s? (?:in|of) " + CLASS
                    + " (?:has|have) been renamed, removed,? or (?:had (?:its|their) signatures? changed|changed (?:its|their) signatures?)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern KEEP = Pattern.compile(
            "(?:the )?existing public (?:method|member)s? (?:in|of) " + CLASS
                    + " (?:remain unchanged in signature|keep (?:their|its) signatures?|retain (?:their|its) signatures?)",
            Pattern.CASE_INSENSITIVE);

    private SignatureClaims() {
    }

    /** The class {@code criterion} is, as a whole, a signature claim about; empty when it is not one. Shape only. */
    public static Optional<String> claimedClass(String criterion) {
        if (criterion == null || criterion.isBlank()) return Optional.empty();
        String claim = criterion.strip().replaceAll("[.\\s]+$", "");
        for (Pattern p : new Pattern[]{NONE_CHANGED, KEEP}) {
            Matcher m = p.matcher(claim);
            if (m.matches()) return Optional.of(m.group(1));
        }
        return Optional.empty();
    }

    /**
     * @param adopted the adopted text of the tree's files, path relative to the project root; may be empty
     * @param files   the tree now, path relative to the project root
     */
    public static Boolean answer(String criterion, Map<String, String> adopted, Map<String, String> files) {
        Optional<String> name = claimedClass(criterion);
        if (name.isEmpty() || adopted == null || files == null) return null;
        Optional<Set<String>> before = publicCallables(textOf(name.get(), adopted), name.get());
        if (before.isEmpty()) return null;
        Optional<Set<String>> now = publicCallables(textOf(name.get(), files), name.get());
        if (now.isEmpty()) return textOf(name.get(), files) == null ? Boolean.FALSE : null;
        return now.get().containsAll(before.get());
    }

    private static String textOf(String simple, Map<String, String> files) {
        for (Map.Entry<String, String> e : files.entrySet()) {
            String p = e.getKey().replace('\\', '/');
            if (p.endsWith(".java") && (p.endsWith("/" + simple + ".java") || p.equals(simple + ".java"))
                    && !p.contains("src/test/")) {
                return e.getValue();
            }
        }
        return null;
    }

    /** The public methods and constructors of type {@code simple}, keyed as {@link PreservedMembers#key}; empty when unreadable. */
    private static Optional<Set<String>> publicCallables(String source, String simple) {
        if (source == null) return Optional.empty();
        try {
            var parsed = JavaSources.parser().parse(source);
            if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) return Optional.empty();
            CompilationUnit cu = parsed.getResult().get();
            Optional<TypeDeclaration<?>> type = cu.getTypes().stream().filter(t -> t.getNameAsString().equals(simple)).findFirst();
            if (type.isEmpty()) return Optional.empty();
            boolean iface = type.get() instanceof ClassOrInterfaceDeclaration c && c.isInterface();
            Set<String> out = new LinkedHashSet<>();
            for (BodyDeclaration<?> m : type.get().getMembers()) {
                if (m instanceof CallableDeclaration<?> cd && (cd.isPublic() || iface && !cd.isPrivate())) {
                    PreservedMembers.key(m).ifPresent(out::add);
                }
            }
            return Optional.of(out);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
