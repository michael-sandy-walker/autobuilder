package com.walpro.autobuilder.core.build;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * T4-DY slice A: a stuck loop's failure signature read as a QUESTION about the code, answered from
 * the code itself.
 *
 * <p>A signature is the text the grind detector records for a failure that keeps recurring, e.g.
 * {@code "the build fails: Foo has no getBar()"}. When the signature names something the project's
 * own sources can answer for, {@link #answer} returns what those sources declare; otherwise empty.
 * Empty is "the signature alone does not say what to look up", never "nothing is wrong".
 */
public final class StuckQuestions {

    /** What was asked of the sources. */
    public enum Kind {
        /** A type was called with a member it may not declare: what does it declare? */
        MEMBERS,
        /** A record was constructed with arguments its constructor may not take: what does it take? */
        CONSTRUCTOR,
        /** A method was called with arguments it may not take: which overloads does its class declare? */
        OVERLOADS,
        /**
         * A name could not be resolved: where IS it declared? In the project's sources, and for a class
         * name also in the running JDK. Added 2026-09-26: three of the seven grinds that outlived every
         * reaction were "cannot find symbol" signatures, answerable once the detector kept the symbol.
         */
        SYMBOL
    }

    /**
     * The answer, read from the type's own source.
     *
     * @param kind     the question
     * @param type     the simple name of the type the question is about
     * @param member   the member the signature names (for MEMBERS and OVERLOADS; null for CONSTRUCTOR)
     * @param declared what the type declares: for MEMBERS, {@link DeclaredMembers#of}'s list; for
     *                 CONSTRUCTOR, each constructor as {@code Type(ParamType name, ...)} starting with
     *                 a record's canonical one; for OVERLOADS, each method of that name as
     *                 {@code name(ParamType name, ...)}; for SYMBOL, every declaration of the name --
     *                 {@code Owner.Name (private enum)}, {@code Type.name (record component)},
     *                 {@code Type.name (field)}, {@code Type.name(ParamType, ...) (method)} from the sources,
     *                 then {@code java.util.Name (JDK class)} for a class name the JDK declares
     * @param meant    for MEMBERS, the declared member the missing one names ({@link DeclaredMembers#meantBy}),
     *                 or null when none; null for the other kinds
     */
    public record Answer(Kind kind, String type, String member, List<String> declared, String meant) {
    }

    private StuckQuestions() {
    }

    private static final Pattern MEMBERS_PATTERN = Pattern.compile("has no (\\w+)\\(");
    private static final Pattern CONSTRUCTOR_PATTERN = Pattern.compile("constructor (\\w+) in (?:record|class) ([\\w.]+)");
    private static final Pattern OVERLOADS_PATTERN = Pattern.compile("method (\\w+) in (?:record|class) ([\\w.]+)");
    private static final Pattern SYMBOL_PATTERN = Pattern.compile(
            "cannot find symbol (class|variable|method) (\\w+)(?:\\([^)]*\\))? in (?:class|interface|record|enum|variable \\w+ of type) ([\\w.$]+)");

    /**
     * The answer the project's sources give to the question {@code signature} poses, or empty.
     *
     * @param signature the recorded failure signature
     * @param sources   the project's source files, path relative to the project root -> content
     */
    public static Optional<Answer> answer(String signature, Map<String, String> sources) {
        if (signature == null || sources == null || sources.isEmpty()) {
            return Optional.empty();
        }

        // Try MEMBERS pattern first
        Matcher membersMatcher = MEMBERS_PATTERN.matcher(signature);
        if (membersMatcher.find()) {
            String memberName = membersMatcher.group(1);
            // Extract type name from signature: "Type has no member"
            String type = extractTypeFromMembersSignature(signature);
            if (type != null) {
                Optional<String> source = findSourceForType(type, sources);
                if (source.isPresent()) {
                    List<String> declared = DeclaredMembers.of(source.get(), type);
                    String meant = DeclaredMembers.meantBy(memberName, declared);
                    return Optional.of(new Answer(Kind.MEMBERS, type, memberName, declared, meant));
                }
            }
        }

        // Try CONSTRUCTOR pattern
        Matcher constructorMatcher = CONSTRUCTOR_PATTERN.matcher(signature);
        if (constructorMatcher.find()) {
            String fqcn = constructorMatcher.group(2);
            String simpleType = fqcn.substring(fqcn.lastIndexOf('.') + 1);
            Optional<String> source = findSourceForType(simpleType, sources);
            if (source.isPresent()) {
                List<String> constructors = extractConstructors(source.get(), simpleType);
                return Optional.of(new Answer(Kind.CONSTRUCTOR, simpleType, null, constructors, null));
            }
        }

        // Try OVERLOADS pattern
        Matcher overloadsMatcher = OVERLOADS_PATTERN.matcher(signature);
        if (overloadsMatcher.find()) {
            String methodName = overloadsMatcher.group(1);
            String fqcn = overloadsMatcher.group(2);
            String simpleType = fqcn.substring(fqcn.lastIndexOf('.') + 1);
            Optional<String> source = findSourceForType(simpleType, sources);
            if (source.isPresent()) {
                List<String> overloads = extractOverloads(source.get(), simpleType, methodName);
                return Optional.of(new Answer(Kind.OVERLOADS, simpleType, methodName, overloads, null));
            }
        }

        // A name that could not be resolved: where is it declared?
        Matcher symbolMatcher = SYMBOL_PATTERN.matcher(signature);
        if (symbolMatcher.find()) {
            String kind = symbolMatcher.group(1), name = symbolMatcher.group(2), location = symbolMatcher.group(3);
            String locationType = location.substring(location.lastIndexOf('.') + 1);
            List<String> declared = new ArrayList<>(declarationsInSources(name, sources));
            if (kind.equals("class") || declared.isEmpty() && Character.isUpperCase(name.charAt(0))) {
                declared.addAll(jdkClasses(name));
            }
            return declared.isEmpty() ? Optional.empty()
                    : Optional.of(new Answer(Kind.SYMBOL, locationType, name, declared, null));
        }

        return Optional.empty();
    }

    /** Every declaration of {@code name} in the sources: types (nested ones as Outer.Name), record components, fields, methods. */
    private static List<String> declarationsInSources(String name, Map<String, String> sources) {
        List<String> out = new ArrayList<>();
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
        for (Map.Entry<String, String> entry : new java.util.TreeMap<>(sources).entrySet()) {
            if (!entry.getKey().endsWith(".java") || entry.getValue() == null || !entry.getValue().contains(name)) continue;
            CompilationUnit cu;
            try {
                cu = parser.parse(entry.getValue()).getResult().orElse(null);
            } catch (RuntimeException e) {
                continue;
            }
            if (cu == null) continue;
            for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
                String owner = qualifiedName(t);
                if (t.getNameAsString().equals(name)) {
                    out.add(owner + " (" + visibility(t.getModifiers()) + kindOf(t) + ")");
                }
                if (t instanceof RecordDeclaration r) {
                    r.getParameters().stream().filter(p -> p.getNameAsString().equals(name))
                            .forEach(p -> out.add(owner + "." + name + " (record component)"));
                }
                for (var f : t.getFields()) {
                    f.getVariables().stream().filter(v -> v.getNameAsString().equals(name))
                            .forEach(v -> out.add(owner + "." + name + " (" + visibility(f.getModifiers()) + "field)"));
                }
                for (MethodDeclaration m : t.getMethods()) {
                    if (!m.getNameAsString().equals(name)) continue;
                    out.add(owner + "." + name + "(" + m.getParameters().stream().map(p -> p.getType().asString())
                            .collect(Collectors.joining(", ")) + ") (" + visibility(m.getModifiers()) + "method)");
                }
            }
        }
        return out;
    }

    /** The type's name with its enclosing types, e.g. DiffSummarizer.ChangeKind. */
    private static String qualifiedName(TypeDeclaration<?> t) {
        StringBuilder sb = new StringBuilder(t.getNameAsString());
        var parent = t.getParentNode().orElse(null);
        while (parent instanceof TypeDeclaration<?> p) {
            sb.insert(0, p.getNameAsString() + ".");
            parent = p.getParentNode().orElse(null);
        }
        return sb.toString();
    }

    private static String visibility(com.github.javaparser.ast.NodeList<Modifier> modifiers) {
        for (Modifier m : modifiers) {
            if (m.getKeyword() == Modifier.Keyword.PRIVATE) return "private ";
            if (m.getKeyword() == Modifier.Keyword.PROTECTED) return "protected ";
        }
        return "";
    }

    private static String kindOf(TypeDeclaration<?> t) {
        if (t.isEnumDeclaration()) return "enum";
        if (t.isRecordDeclaration()) return "record";
        if (t.isClassOrInterfaceDeclaration() && t.asClassOrInterfaceDeclaration().isInterface()) return "interface";
        return "class";
    }

    /**
     * Public classes of that simple name in the running JDK's own modules, discovered rather than
     * listed: every package a {@code java.*} module exports, asked whether it holds the name.
     */
    private static List<String> jdkClasses(String simpleName) {
        java.util.TreeSet<String> found = new java.util.TreeSet<>();
        for (Module module : ModuleLayer.boot().modules()) {
            if (!module.getName().startsWith("java.")) continue;
            for (String pkg : module.getPackages()) {
                if (!module.isExported(pkg)) continue;
                try {
                    Class<?> c = Class.forName(pkg + "." + simpleName, false, ClassLoader.getPlatformClassLoader());
                    if (java.lang.reflect.Modifier.isPublic(c.getModifiers())) found.add(c.getName() + " (JDK class)");
                } catch (ClassNotFoundException | LinkageError ignored) {
                    // not in this package
                }
            }
        }
        return new ArrayList<>(found);
    }

    private static String extractTypeFromMembersSignature(String signature) {
        // Pattern: "Type has no member"
        int hasNoIndex = signature.indexOf("has no ");
        if (hasNoIndex < 0) return null;
        String before = signature.substring(0, hasNoIndex).trim();
        // The type is the last word before "has no"
        String[] parts = before.split("\\s+");
        if (parts.length == 0) return null;
        return parts[parts.length - 1];
    }

    /**
     * The source that DECLARES {@code simpleType}: the file named after it first, then any file with a
     * real declaration of that name. Port review (2026-09-26): the loop's version matched
     * {@code "class " + simpleType} as a substring, so {@code ParsedElement} also matched
     * {@code class ParsedElementBuilder} and a name in a comment -- right on the archive only because
     * the file map happened to list the named file first.
     */
    private static Optional<String> findSourceForType(String simpleType, Map<String, String> sources) {
        Pattern declaration = Pattern.compile("\\b(?:class|record|interface|enum)\\s+" + Pattern.quote(simpleType) + "\\b");
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String path = entry.getKey().replace('\\', '/');
            if ((path.endsWith("/" + simpleType + ".java") || path.equals(simpleType + ".java"))
                    && entry.getValue() != null && declaration.matcher(withoutComments(entry.getValue())).find()) {
                return Optional.of(entry.getValue());
            }
        }
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String content = entry.getValue();
            if (content != null && entry.getKey().endsWith(".java") && declaration.matcher(withoutComments(content)).find()) {
                return Optional.of(content);
            }
        }
        return Optional.empty();
    }

    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    private static List<String> extractConstructors(String source, String simpleType) {
        if (source == null || simpleType == null) return List.of();
        CompilationUnit cu;
        try {
            cu = new JavaParser(new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17))
                    .parse(source).getResult().orElse(null);
        } catch (RuntimeException e) {
            return List.of();
        }
        if (cu == null) return List.of();
        
        TypeDeclaration<?> found = null;
        for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
            if (t.getNameAsString().equals(simpleType)) {
                found = t;
                break;
            }
        }
        if (found == null) return List.of();
        
        List<String> constructors = new ArrayList<>();
        // For records, the canonical constructor comes first
        if (found instanceof RecordDeclaration r) {
            String params = r.getParameters().stream()
                    .map(p -> p.getType().asString() + " " + p.getNameAsString())
                    .collect(Collectors.joining(", "));
            constructors.add(simpleType + "(" + params + ")");
        }
        
        // Add explicit constructors
        for (ConstructorDeclaration c : found.getConstructors()) {
            if (c.isPrivate()) continue;
            String params = c.getParameters().stream()
                    .map(p -> p.getType().asString() + " " + p.getNameAsString())
                    .collect(Collectors.joining(", "));
            constructors.add(simpleType + "(" + params + ")");
        }
        
        return constructors;
    }

    private static List<String> extractOverloads(String source, String simpleType, String methodName) {
        if (source == null || simpleType == null || methodName == null) return List.of();
        CompilationUnit cu;
        try {
            cu = new JavaParser(new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17))
                    .parse(source).getResult().orElse(null);
        } catch (RuntimeException e) {
            return List.of();
        }
        if (cu == null) return List.of();
        
        TypeDeclaration<?> found = null;
        for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
            if (t.getNameAsString().equals(simpleType)) {
                found = t;
                break;
            }
        }
        if (found == null) return List.of();
        
        List<String> overloads = new ArrayList<>();
        boolean isInterface = found.isClassOrInterfaceDeclaration()
                && found.asClassOrInterfaceDeclaration().isInterface();
        
        for (MethodDeclaration m : found.getMethods()) {
            if (!m.getNameAsString().equals(methodName)) continue;
            if (!(isInterface || m.hasModifier(Modifier.Keyword.PUBLIC))) continue;
            String params = m.getParameters().stream()
                    .map(p -> p.getType().asString() + " " + p.getNameAsString())
                    .collect(Collectors.joining(", "));
            overloads.add(methodName + "(" + params + ")");
        }
        
        return overloads;
    }
}
