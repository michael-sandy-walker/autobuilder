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
        SYMBOL,
        /**
         * A class was looked for in a named package and is not there: where IS it declared, and if nowhere,
         * which classes does that package declare that end like it? parameterized-rows stage 1 (2026-09-30):
         * the model imported invented JavaParser types ({@code FloatLiteralExpr}, {@code IntegerValueExpr})
         * and a real one from the wrong package ({@code ArrayCreationLevel}); the question that asks where a
         * name is declared read only the sources and the JDK, and its pattern did not accept "in package".
         * {@code type} is the package, {@code member} the class, {@code declared} where it is declared or,
         * when nowhere, the package's classes that end like it, and {@code meant} null when it was found.
         */
        PACKAGE_CLASS
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
    /**
     * javac's wording for a method called on a receiver whose type it names: "in record component x of
     * type T" or "in variable x of type T". T is kept as javac wrote it -- package and all -- because a
     * written package leaves nothing to guess (port review 2026-09-27: dropping it made java.util.Map a bare
     * "Map" to be looked up again).
     */
    private static final Pattern RECEIVER_METHOD_PATTERN = Pattern.compile(
            "cannot find symbol method (\\w+)\\([^)]*\\) in (?:record component|variable) \\w+ of type ([\\w.$]+)");
    /**
     * javac's own wording ("constructor X in record a.b.X"), or the grind detector's for "no suitable
     * constructor found" ("X has no constructor X(args)") -- the second was not recognised until slice B
     * put the router in the loop, where the detector's wording is what it is asked.
     */
    private static final Pattern CONSTRUCTOR_PATTERN = Pattern.compile(
            "constructor (\\w+) in (?:record|class|interface|enum) ([\\w.$]+)|has no constructor (\\w+)\\(");
    /** javac names the declaring type with its package, and with its kind -- a class, record, interface or enum. */
    private static final Pattern OVERLOADS_PATTERN = Pattern.compile("method (\\w+) in (?:record|class|interface|enum) ([\\w.$]+)");
    private static final Pattern PACKAGE_CLASS_PATTERN = Pattern.compile("cannot find symbol class (\\w+) in package ([\\w.]+)");
    private static final Pattern SYMBOL_PATTERN = Pattern.compile(
            "cannot find symbol (class|variable|method) (\\w+)(?:\\([^)]*\\))? in (?:class|interface|record|enum|variable \\w+ of type) ([\\w.$]+)");

    /**
     * The answer the project's sources give to the question {@code signature} poses, or empty.
     *
     * @param signature the recorded failure signature
     * @param sources   the project's source files, path relative to the project root -> content
     */
    public static Optional<Answer> answer(String signature, Map<String, String> sources) {
        return answer(signature, sources, TypeAuthority.platform());
    }

    /**
     * As {@link #answer(String, Map)}, with the authority that says which class a type name in the
     * signature denotes when the sources do not declare it -- the running JDK by default; in the loop, the
     * project's own classpath as well ({@code ProjectTypeAuthority}).
     */
    public static Optional<Answer> answer(String signature, Map<String, String> sources, TypeAuthority authority) {
        if (signature == null || sources == null || sources.isEmpty()) {
            return Optional.empty();
        }

        // A method called on a receiver whose type javac names (T4-DY, the receiver question): answered only
        // when that type is not the project's own -- the project's answers come from its sources below.
        Matcher receiverMatcher = RECEIVER_METHOD_PATTERN.matcher(signature);
        if (receiverMatcher.find()) {
            Optional<Answer> receiver = receiverAnswer(receiverMatcher.group(2), receiverMatcher.group(1), sources, authority);
            if (receiver.isPresent()) return receiver;
        }

        // Try MEMBERS pattern (after the receiver question)
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
                Optional<Answer> receiver = receiverAnswer(type, memberName, sources, authority);
                if (receiver.isPresent()) return receiver;
            }
        }

        // Try CONSTRUCTOR pattern
        Matcher constructorMatcher = CONSTRUCTOR_PATTERN.matcher(signature);
        if (constructorMatcher.find()) {
            String fqcn = constructorMatcher.group(2) != null ? constructorMatcher.group(2) : constructorMatcher.group(3);
            Optional<TypeDeclaration<?>> type = declaringType(fqcn, sources);
            if (type.isPresent()) {
                return Optional.of(new Answer(Kind.CONSTRUCTOR, type.get().getNameAsString(), null, constructorsOf(type.get()), null));
            }
        }

        // Try OVERLOADS pattern
        Matcher overloadsMatcher = OVERLOADS_PATTERN.matcher(signature);
        if (overloadsMatcher.find()) {
            String methodName = overloadsMatcher.group(1);
            Optional<TypeDeclaration<?>> type = declaringType(overloadsMatcher.group(2), sources);
            if (type.isPresent()) {
                return Optional.of(new Answer(Kind.OVERLOADS, type.get().getNameAsString(), methodName,
                        overloadsOf(type.get(), methodName), null));
            }
        }

        // A class looked for in a named package: where is it, or what does that package hold that ends like it?
        Matcher packageMatcher = PACKAGE_CLASS_PATTERN.matcher(signature);
        if (packageMatcher.find()) {
            String name = packageMatcher.group(1), pkg = packageMatcher.group(2);
            List<String> where = new ArrayList<>(declarationsInSources(name, sources));
            for (String fqn : new java.util.TreeSet<>(authority.classesNamed(name))) {
                if (!fqn.equals(pkg + "." + name)) where.add(fqn);
            }
            if (!where.isEmpty()) return Optional.of(new Answer(Kind.PACKAGE_CLASS, pkg, name, where, null));
            List<String> alike = endingLike(name, authority.classesIn(pkg));
            return alike.isEmpty() ? Optional.empty()
                    : Optional.of(new Answer(Kind.PACKAGE_CLASS, pkg, name, alike, "nowhere"));
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

    /**
     * Which class a type name in a failure denotes -- one authority, asked by every question that needs it,
     * so the router cannot answer with a class the project does not mean. Empty is "unknown or ambiguous",
     * never a guess.
     */
    @FunctionalInterface
    public interface TypeAuthority {

        /** The one class {@code name} (as the failure wrote it: simple or qualified) denotes, or empty. */
        Optional<Class<?>> resolve(String name, Map<String, String> sources);

        /** Every class named {@code simpleName} this authority knows, fully qualified: the JDK's by default. */
        default java.util.Set<String> classesNamed(String simpleName) {
            return JdkPlatformTypeIndex.exportedTypes(simpleName);
        }

        /** The top-level classes {@code packageName} declares, fully qualified: the JDK's by default. */
        default java.util.Set<String> classesIn(String packageName) {
            return JdkPlatformTypeIndex.typesInPackage(packageName);
        }

        /** The running JDK's platform classes, by {@link #resolveAgainst}. */
        static TypeAuthority platform() {
            return (name, sources) -> resolveAgainst(name, sources, ClassLoader.getPlatformClassLoader(),
                    JdkPlatformTypeIndex::exportedTypes);
        }
    }

    /**
     * The rule every {@link TypeAuthority} applies -- Java's own order for resolving a simple type name
     * (JLS 6.4.1 / 7.5), so the router means the class the compiler would (port review 2026-09-27: the
     * loop's and the first port's versions answered the project's own {@code Base64} with
     * {@code java.util.Base64}'s methods; the independent review of this rule's first draft found that an
     * explicit import must also outrank {@code java.lang} and a unique platform name):
     * <ol>
     *   <li>a package the failure WROTE is exact: that class, or nothing;</li>
     *   <li>a type the project's sources declare is the project's -- never a platform or dependency class;</li>
     *   <li>a single-type import in the sources ({@code import a.b.Name;}) names it: that class, or nothing
     *       when it cannot be loaded -- and nothing when the sources import two different ones;</li>
     *   <li>{@code java.lang} -- visible everywhere without an import;</li>
     *   <li>on-demand imports ({@code import a.b.*;}) choose among the candidates {@code bySimpleName} lists;
     *       without one, a single candidate is the one meant -- otherwise no answer.</li>
     * </ol>
     */
    public static Optional<Class<?>> resolveAgainst(String name, Map<String, String> sources, ClassLoader loader,
                                                   java.util.function.Function<String, java.util.Set<String>> bySimpleName) {
        if (name == null || name.isBlank()) return Optional.empty();
        String bare = name.contains("<") ? name.substring(0, name.indexOf('<')) : name;
        if (bare.contains(".")) return load(bare, loader);
        if (declaresType(bare, sources)) return Optional.empty();
        java.util.Set<String> singleImports = singleTypeImports(bare, sources);
        if (singleImports.size() > 1) return Optional.empty();
        if (singleImports.size() == 1) return load(singleImports.iterator().next(), loader);
        Optional<Class<?>> lang = load("java.lang." + bare, loader);
        if (lang.isPresent()) return lang;
        List<String> candidates = new ArrayList<>(bySimpleName.apply(bare));
        List<String> onDemand = candidates.stream().filter(fqn -> importedOnDemand(fqn, sources)).toList();
        if (!onDemand.isEmpty()) candidates = onDemand;
        return candidates.size() == 1 ? load(candidates.get(0), loader) : Optional.empty();
    }

    private static Optional<Class<?>> load(String fqn, ClassLoader loader) {
        try {
            Class<?> c = Class.forName(fqn, false, loader);
            return java.lang.reflect.Modifier.isPublic(c.getModifiers()) ? Optional.of(c) : Optional.empty();
        } catch (ClassNotFoundException | LinkageError e) {
            return Optional.empty();
        }
    }

    /** Every {@code import a.b.Name;} of that simple name in the sources. */
    private static java.util.Set<String> singleTypeImports(String simpleName, Map<String, String> sources) {
        Pattern single = Pattern.compile("^\\s*import\\s+([\\w.]+\\." + Pattern.quote(simpleName) + ")\\s*;", Pattern.MULTILINE);
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (String content : sources.values()) {
            if (content == null) continue;
            Matcher m = single.matcher(content);
            while (m.find()) out.add(m.group(1));
        }
        return out;
    }

    private static boolean importedOnDemand(String fqn, Map<String, String> sources) {
        String pkg = fqn.substring(0, fqn.lastIndexOf('.'));
        Pattern onDemand = Pattern.compile("^\\s*import\\s+" + Pattern.quote(pkg) + "\\.\\*\\s*;", Pattern.MULTILINE);
        for (String content : sources.values()) {
            if (content != null && onDemand.matcher(content).find()) return true;
        }
        return false;
    }

    /**
     * The receiver question: {@code type} is not the project's, and the authority names the one class it
     * is. The answer is what that class declares -- its public methods, own and inherited, as
     * {@code name(ParameterSimpleType, ...)}, sorted, compiler-generated bridge and synthetic methods left
     * out -- and, as {@code meant}, where the sources declare the called method
     * ({@code Type.name(ParameterTypes)}): a receiver typed Object usually lost its element type.
     */
    private static Optional<Answer> receiverAnswer(String type, String memberName, Map<String, String> sources,
                                                   TypeAuthority authority) {
        Optional<Class<?>> resolved = authority.resolve(type, sources);
        if (resolved.isEmpty()) return Optional.empty();
        java.util.TreeSet<String> declared = new java.util.TreeSet<>();
        try {
            for (java.lang.reflect.Method m : resolved.get().getMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                declared.add(m.getName() + "(" + java.util.Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName)
                        .collect(Collectors.joining(", ")) + ")");
            }
        } catch (LinkageError | TypeNotPresentException e) {
            // a dependency class whose own dependencies are missing: no answer rather than a broken prompt
            return Optional.empty();
        }
        String meant = null;
        for (String d : declarationsInSources(memberName, sources)) {
            if (d.endsWith("method)")) {
                meant = d.substring(0, d.lastIndexOf(" ("));
                break;
            }
        }
        return Optional.of(new Answer(Kind.MEMBERS, resolved.get().getSimpleName(), memberName, new ArrayList<>(declared), meant));
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
     * Public classes of that simple name in the running JDK, from the platform index discovery also reads
     * ({@link JdkPlatformTypeIndex}) -- one scan of the runtime image, not two.
     */
    private static List<String> jdkClasses(String simpleName) {
        java.util.TreeSet<String> found = new java.util.TreeSet<>();
        for (String fqn : JdkPlatformTypeIndex.exportedTypes(simpleName)) {
            if (load(fqn, ClassLoader.getPlatformClassLoader()).isPresent()) found.add(fqn + " (JDK class)");
        }
        return new ArrayList<>(found);
    }

    /** Most a package-class answer lists: enough to choose from, few enough to read. */
    static final int ALIKE_LIMIT = 12;

    /**
     * The classes of {@code inPackage} that look like {@code name}: first those that begin with its first
     * CamelCase word and end with its last (for an invented {@code DoubleValueExpr}, {@code DoubleLiteralExpr};
     * for {@code ArrayReferenceExpr}, the {@code Array*Expr} classes), then those ending in the most trailing
     * words it has (for {@code FloatLiteralExpr}, the {@code *LiteralExpr} classes). The ending alone, replayed
     * on the stage-1 signatures, offered {@code LiteralStringValueExpr} for {@code DoubleValueExpr}. Empty when
     * none shares even the last word.
     */
    static List<String> endingLike(String name, java.util.Collection<String> inPackage) {
        String[] words = name.split("(?<=[a-z0-9])(?=[A-Z])");
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (words.length >= 2) {
            String first = words[0], last = words[words.length - 1];
            inPackage.stream().sorted().filter(fqn -> {
                String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
                return !simple.equals(name) && simple.startsWith(first) && simple.endsWith(last);
            }).forEach(out::add);
        }
        for (int k = words.length - 1; k >= 1; k--) {
            String ending = String.join("", java.util.Arrays.copyOfRange(words, words.length - k, words.length));
            List<String> hits = inPackage.stream().sorted()
                    .filter(fqn -> fqn.substring(fqn.lastIndexOf('.') + 1).endsWith(ending)).toList();
            if (!hits.isEmpty()) {
                out.addAll(hits);
                break;
            }
        }
        return out.stream().limit(ALIKE_LIMIT).toList();
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
     * The source that DECLARES {@code simpleType}: of every file with a real declaration of that name, the one
     * named after it; when several remain, the one outside {@code src/test}; when that still leaves more than
     * one, none -- the signature does not say which (port review 2026-09-27: a test double {@code Foo} beside
     * the real one was answered from whichever came first in the map). Port review 2026-09-26: the loop's first
     * version matched {@code "class " + simpleType} as a substring, so {@code ParsedElement} also matched
     * {@code class ParsedElementBuilder} and a name in a comment.
     */
    private static Optional<String> findSourceForType(String simpleType, Map<String, String> sources) {
        List<Map.Entry<String, String>> declaring = declaringSources(simpleType, sources);
        if (declaring.size() > 1) {
            List<Map.Entry<String, String>> named = declaring.stream()
                    .filter(e -> e.getKey().replace('\\', '/').endsWith("/" + simpleType + ".java")
                            || e.getKey().equals(simpleType + ".java")).toList();
            if (!named.isEmpty()) declaring = named;
        }
        if (declaring.size() > 1) {
            List<Map.Entry<String, String>> main = declaring.stream()
                    .filter(e -> !e.getKey().replace('\\', '/').contains("src/test/")).toList();
            if (!main.isEmpty()) declaring = main;
        }
        return declaring.size() == 1 ? Optional.of(declaring.get(0).getValue()) : Optional.empty();
    }

    /** True when any source declares a type of that simple name -- the project's own name, however many times. */
    private static boolean declaresType(String simpleType, Map<String, String> sources) {
        return !declaringSources(simpleType, sources).isEmpty();
    }

    private static List<Map.Entry<String, String>> declaringSources(String simpleType, Map<String, String> sources) {
        Pattern declaration = Pattern.compile("\\b(?:class|record|interface|enum)\\s+" + Pattern.quote(simpleType) + "\\b");
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : new java.util.TreeMap<>(sources).entrySet()) {
            String content = entry.getValue();
            if (content != null && entry.getKey().endsWith(".java") && declaration.matcher(withoutComments(content)).find()) {
                out.add(entry);
            }
        }
        return out;
    }

    /**
     * The type javac names: a qualified name is matched exactly against each declaration's own qualified name
     * (package and enclosing types), so {@code b.y.Foo} is never answered with {@code a.x.Foo}; a simple name
     * falls back to {@link #findSourceForType}. Type arguments are ignored. Empty when the project declares no
     * such type.
     */
    private static Optional<TypeDeclaration<?>> declaringType(String name, Map<String, String> sources) {
        String bare = name.contains("<") ? name.substring(0, name.indexOf('<')) : name;
        String simple = bare.substring(bare.lastIndexOf('.') + 1);
        if (!bare.contains(".")) {
            return findSourceForType(simple, sources).flatMap(src -> typeIn(src, simple, null));
        }
        for (Map.Entry<String, String> e : declaringSources(simple, sources)) {
            Optional<TypeDeclaration<?>> t = typeIn(e.getValue(), simple, bare);
            if (t.isPresent()) return t;
        }
        return Optional.empty();
    }

    private static Optional<TypeDeclaration<?>> typeIn(String source, String simple, String qualified) {
        CompilationUnit cu;
        try {
            cu = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17))
                    .parse(source).getResult().orElse(null);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (cu == null) return Optional.empty();
        for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
            if (!t.getNameAsString().equals(simple)) continue;
            if (qualified == null || qualified.equals(t.getFullyQualifiedName().orElse(null))) return Optional.of(t);
        }
        return Optional.empty();
    }

    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    /**
     * The constructors {@code source} declares for {@code simpleType} (a nested type included), as
     * {@code Type(ParamType name, ...)}, a record's canonical one first; empty when it declares no such type.
     */
    public static List<String> constructorsIn(String source, String simpleType) {
        return extractConstructors(source, simpleType);
    }

    private static List<String> extractConstructors(String source, String simpleType) {
        return typeIn(source == null ? "" : source, simpleType, null).map(StuckQuestions::constructorsOf).orElse(List.of());
    }

    /**
     * A type's constructors as {@code Type(ParamType name, ...)}: a record's canonical one first, and an explicit
     * constructor with the canonical's parameter types not again; every visibility, a private one marked
     * {@code (private)} so it is not mistaken for one the caller can use; varargs as {@code Type... name}.
     */
    static List<String> constructorsOf(TypeDeclaration<?> found) {
        String simpleType = found.getNameAsString();
        List<String> constructors = new ArrayList<>();
        List<String> canonicalTypes = null;
        if (found instanceof RecordDeclaration r) {
            canonicalTypes = r.getParameters().stream().map(p -> p.getType().asString()).toList();
            constructors.add(simpleType + "(" + params(r.getParameters()) + ")");
        }
        for (ConstructorDeclaration c : found.getConstructors()) {
            List<String> types = c.getParameters().stream().map(p -> p.getType().asString()).toList();
            if (types.equals(canonicalTypes)) continue;
            constructors.add(simpleType + "(" + params(c.getParameters()) + ")" + (c.isPrivate() ? " (private)" : ""));
        }
        return constructors;
    }

    /** Every method of that name the type declares, every visibility, a private one marked {@code (private)}. */
    static List<String> overloadsOf(TypeDeclaration<?> found, String methodName) {
        List<String> overloads = new ArrayList<>();
        for (MethodDeclaration m : found.getMethods()) {
            if (!m.getNameAsString().equals(methodName)) continue;
            overloads.add(methodName + "(" + params(m.getParameters()) + ")" + (m.isPrivate() ? " (private)" : ""));
        }
        return overloads;
    }

    private static String params(com.github.javaparser.ast.NodeList<com.github.javaparser.ast.body.Parameter> ps) {
        return ps.stream().map(p -> p.getType().asString() + (p.isVarArgs() ? "..." : "") + " " + p.getNameAsString())
                .collect(Collectors.joining(", "));
    }
}
