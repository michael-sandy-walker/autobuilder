package com.walpro.autobuilder.core.build;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * T6-T, the situational skeleton: what the tests need, DEDUCED from their syntax tree in one pass -- before any compile
 * -- with the types each use implies, and the CHAIN the tests demand (which class must call which). What deduction
 * cannot settle (a type two kinds of assert both accept, an argument the solver cannot type) is left as {@code Object}
 * for the compile check ({@link SkeletonStubber}) to settle; the two together are the skeleton.
 *
 * <p>Why deduction first. The compile loop sees one error at a time and names a new method's return type only once a
 * test fails to convert it, so it guesses {@code Object} and corrects; and it cannot say which class must CALL which --
 * the link the band-3 runs never made (ladder-grind: GrindDetector must call BehaviourLogger.grindDetected, read here
 * from {@code verify(logger).grindDetected(..)} and from where the mock is handed in).
 *
 * <p>Evidence for a return type, strongest first: a variable or assertion argument of a declared type (exact); an
 * assertion method only one kind of value has (discovered on the classpath, {@link SkeletonStubber.AssertionTypes});
 * a discarded value ({@code void}). The strongest across every use of the member wins; an assertion two unrelated
 * kinds accept is no evidence.
 */
@Slf4j
public final class SkeletonDeduction {

    private SkeletonDeduction() {
    }

    /** A declaration the tests need. {@code returns} is null for a class or constructor, "Object" when undecided. */
    public record Need(String kind, String type, String name, List<String> params, String returns, boolean isStatic,
                       int evidence, String where, List<String> alternatives) {
        Need(String kind, String type, String name, List<String> params, String returns, boolean isStatic, int evidence, String where) {
            this(kind, type, name, params, returns, isStatic, evidence, where, List.of());
        }

        public String describe() {
            String simple = type.substring(type.lastIndexOf('.') + 1);
            return switch (kind) {
                case "class" -> "class " + simple;
                case "constructor" -> simple + "(" + String.join(", ", params) + ")";
                default -> (isStatic ? "static " : "") + simple + "." + name + "(" + String.join(", ", params) + ")"
                        + (returns == null ? "" : ": " + returns);
            };
        }
    }

    /** A call the tests demand: {@code caller} must call {@code callee.member}. */
    public record Link(String caller, String callee, String member, String why) {
        public String describe() {
            return simple(caller) + " must call " + simple(callee) + "." + member + " (" + why + ")";
        }
    }

    public record Result(List<Need> needs, List<Link> links) {
    }

    private static final int EXACT = 3, ASSERTED = 2, DISCARDED = 1, NONE = 0;

    /**
     * How a test names a stand-in and a check that a call was made on one -- the mocking library's own words, as data.
     * Debt by the discovery rule: these two names should come from the test classpath (the library's static factory
     * returning its argument's type, and its call check); they are the one vocabulary this class carries.
     */
    static final Set<String> STAND_INS = Set.of("mock", "spy"), CALL_CHECKS = Set.of("verify");

    public static Result deduce(Path project, String classpath) {
        Path main = project.resolve("src/main/java"), test = project.resolve("src/test/java");
        if (!Files.isDirectory(test)) return new Result(List.of(), List.of());
        CombinedTypeSolver ts = new CombinedTypeSolver(new ReflectionTypeSolver(true));
        if (Files.isDirectory(main)) ts.add(new JavaParserTypeSolver(main));
        ts.add(new JavaParserTypeSolver(test));
        if (classpath != null) {
            for (String jar : classpath.split(File.pathSeparator)) {
                if (!jar.endsWith(".jar")) continue;
                try {
                    ts.add(new JarTypeSolver(jar));
                } catch (Exception ignored) {
                    // an unreadable jar resolves nothing
                }
            }
        }
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17).setSymbolResolver(new JavaSymbolSolver(ts)));
        SkeletonStubber.AssertionTypes assertions = new SkeletonStubber.AssertionTypes(classpath);
        Map<String, Need> needs = new LinkedHashMap<>();
        Set<Link> links = new LinkedHashSet<>();
        Set<String> declared = declaredTypeNames(project);
        List<Path> files;
        try (Stream<Path> w = Files.walk(test)) {
            files = w.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (Exception e) {
            return new Result(List.of(), List.of());
        }
        for (Path f : files) {
            CompilationUnit cu;
            try {
                cu = parser.parse(f).getResult().orElse(null);
            } catch (Exception e) {
                continue;
            }
            if (cu == null) continue;
            String file = f.getFileName().toString();
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            Map<String, String> mocks = new HashMap<>();   // variable -> qualified type
            Map<String, String> handedTo = new HashMap<>(); // mock type -> the project class it was put into
            for (VariableDeclarator v : cu.findAll(VariableDeclarator.class)) {
                if (v.getInitializer().map(i -> i.isMethodCallExpr() && STAND_INS.contains(i.asMethodCallExpr().getNameAsString())).orElse(false)) {
                    mocks.put(v.getNameAsString(), qualified(v.getType().toString(), pkg, cu));
                }
            }
            for (ObjectCreationExpr oc : cu.findAll(ObjectCreationExpr.class)) {
                String created = qualified(oc.getType().getNameAsString(), pkg, cu);
                for (Expression arg : oc.getArguments()) {
                    String mock = arg.isNameExpr() ? mocks.get(arg.asNameExpr().getNameAsString()) : null;
                    if (mock != null) handedTo.put(mock, created);
                }
                if (exists(main, created) || resolves(oc) || declared.contains(simple(created))) continue;
                needs.putIfAbsent(created, new Need("class", created, null, List.of(), null, false, EXACT, file));
                List<String> params = argTypes(oc.getArguments(), cu, pkg);
                needs.putIfAbsent(created + "#<init>" + params, new Need("constructor", created, created, params, null, false,
                        EXACT, file + ":" + line(oc)));
            }
            for (MethodCallExpr mc : cu.findAll(MethodCallExpr.class)) {
                // a mock handed INTO an object, by a setter or a helper (setField(target, "name", mock))
                for (Expression arg : mc.getArguments()) {
                    String mock = arg.isNameExpr() ? mocks.get(arg.asNameExpr().getNameAsString()) : null;
                    if (mock == null) continue;
                    String target = null;
                    Expression first = mc.getArguments().get(0);
                    if (first != arg && first.isNameExpr()) target = typeOrDeclared(first, cu, pkg);
                    else if (mc.getScope().isPresent()) target = typeOrDeclared(mc.getScope().get(), cu, pkg);
                    if (target != null && isProject(target, main, needs)) handedTo.put(mock, target);
                }
                if (mc.getScope().isEmpty() || resolves(mc)) continue;
                Expression scope = mc.getScope().get();
                String owner = typeOrDeclared(scope, cu, pkg);
                // verify(mock).call(..) where the check itself cannot be typed: the call is on the mock's type
                if (owner == null && scope.isMethodCallExpr() && CALL_CHECKS.contains(scope.asMethodCallExpr().getNameAsString())
                        && scope.asMethodCallExpr().getArguments().size() == 1 && scope.asMethodCallExpr().getArgument(0).isNameExpr()) {
                    owner = mocks.get(scope.asMethodCallExpr().getArgument(0).asNameExpr().getNameAsString());
                }
                boolean isStatic = false;
                if (owner == null && scope.isNameExpr() && looksLikeType(scope.asNameExpr().getNameAsString())) {
                    owner = qualified(scope.asNameExpr().getNameAsString(), pkg, cu);
                    isStatic = true;
                    if (declared.contains(simple(owner)) && !exists(main, owner)) continue;
                    if (!exists(main, owner)) needs.putIfAbsent(owner, new Need("class", owner, null, List.of(), null, false, EXACT, file));
                }
                if (owner == null || !isProject(owner, main, needs)) continue;   // a library's own API is not ours to stub
                if (exists(main, owner) && declares(main, owner, mc.getNameAsString())) continue;
                List<String> params = argTypes(mc.getArguments(), cu, pkg);
                Evidence ret = returnFromUse(mc, cu, pkg, assertions);
                String key = owner + "#" + mc.getNameAsString() + "/" + params.size();
                Need prior = needs.get(key);
                if (prior == null || ret.rank() > prior.evidence()) {
                    List<String> merged = prior == null ? params : mergeParams(prior.params(), params);
                    needs.put(key, new Need("method", owner, mc.getNameAsString(), merged, ret.type(), isStatic || (prior != null && prior.isStatic()),
                            ret.rank(), file + ":" + line(mc), !ret.alternatives().isEmpty() ? ret.alternatives()
                            : prior == null ? List.of() : prior.alternatives()));
                } else {
                    needs.put(key, new Need(prior.kind(), prior.type(), prior.name(), mergeParams(prior.params(), params),
                            prior.returns(), prior.isStatic(), prior.evidence(), prior.where(),
                            prior.alternatives().isEmpty() ? ret.alternatives() : prior.alternatives()));
                }
                // verify(mock).call(..): the class the mock was put into must make this call
                if (scope.isMethodCallExpr() && CALL_CHECKS.contains(scope.asMethodCallExpr().getNameAsString())) {
                    String caller = handedTo.get(owner);
                    if (caller != null) links.add(new Link(caller, owner, mc.getNameAsString() + "(" + String.join(", ", params) + ")",
                            "verify in " + file));
                }
            }
        }
        return new Result(settleByNamesakes(new ArrayList<>(needs.values()), main), List.copyOf(links));
    }

    /**
     * An ambiguous return type settled by a NAMESAKE: another member of the same name in the project -- a need settled
     * by stronger evidence, or a declaration in the sources -- whose type is one of the candidates. ladder-grind: the
     * test only asserts contains(..) on GrindDetector.promptBlock(), which a String and a List both allow; the
     * project's ProtectedPaths.promptBlock() is a String. With no namesake it stays Object for the compile check.
     */
    static List<Need> settleByNamesakes(List<Need> needs, Path main) {
        List<Need> out = new ArrayList<>();
        for (Need n : needs) {
            if (n.alternatives().isEmpty() || n.evidence() > NONE) { out.add(n); continue; }
            String pick = null;
            for (Need other : needs) {
                if (other != n && n.name().equals(other.name()) && other.evidence() > NONE && other.returns() != null
                        && n.alternatives().stream().anyMatch(a -> sameKind(a, other.returns()))) { pick = other.returns(); break; }
            }
            if (pick == null) pick = namesakeInSources(main, n.name(), n.alternatives());
            out.add(pick == null ? n : new Need(n.kind(), n.type(), n.name(), n.params(), pick, n.isStatic(), ASSERTED, n.where(), n.alternatives()));
        }
        return out;
    }

    private static boolean sameKind(String candidate, String type) {
        String a = candidate.replaceAll("<.*$", ""), b = type.replaceAll("<.*$", "");
        return a.equals(b) || a.endsWith("." + b) || b.endsWith("." + a);
    }

    private static String namesakeInSources(Path main, String name, List<String> candidates) {
        if (!Files.isDirectory(main)) return null;
        Pattern p = Pattern.compile("\\b([\\w.<>, ]+?)\\s+" + Pattern.quote(name) + "\\s*\\(");
        try (Stream<Path> w = Files.walk(main)) {
            for (Path f : (Iterable<Path>) w.filter(x -> x.toString().endsWith(".java"))::iterator) {
                var m = p.matcher(Files.readString(f));
                while (m.find()) {
                    String t = m.group(1).trim().replaceAll("^.*\\s", "");
                    for (String c : candidates) if (sameKind(c, t)) return c;
                }
            }
        } catch (Exception ignored) {
            // no namesake
        }
        return null;
    }

    /** Every type name declared in the project's sources, main and test, nested ones included. */
    static Set<String> declaredTypeNames(Path project) {
        Set<String> out = new java.util.HashSet<>();
        Pattern decl = Pattern.compile("\\b(?:class|record|interface|enum)\\s+([A-Z]\\w*)");
        for (String root : List.of("src/main/java", "src/test/java")) {
            Path r = project.resolve(root);
            if (!Files.isDirectory(r)) continue;
            try (Stream<Path> w = Files.walk(r)) {
                for (Path f : (Iterable<Path>) w.filter(x -> x.toString().endsWith(".java"))::iterator) {
                    var m = decl.matcher(Files.readString(f));
                    while (m.find()) out.add(m.group(1));
                }
            } catch (Exception ignored) {
                // what cannot be read declares nothing
            }
        }
        return out;
    }

    record Evidence(String type, int rank, List<String> alternatives) {
        Evidence(String type, int rank) {
            this(type, rank, List.of());
        }
    }

    /** The return type a call's use implies, with how strong that evidence is. */
    static Evidence returnFromUse(MethodCallExpr mc, CompilationUnit cu, String pkg, SkeletonStubber.AssertionTypes assertions) {
        Node p = mc.getParentNode().orElse(null);
        if (p instanceof ExpressionStmt) return new Evidence("void", DISCARDED);
        if (p instanceof VariableDeclarator v) {
            String t = resolvedName(v.getType()).orElse(qualified(v.getType().toString(), pkg, cu));
            return "var".equals(v.getTypeAsString()) ? new Evidence("Object", NONE) : new Evidence(t, EXACT);
        }
        if (p instanceof MethodCallExpr outer && outer.getArguments().contains(mc)) {
            // assertEquals(expected, x.m()) and the like: the other argument's type
            List<Expression> others = outer.getArguments().stream().filter(x -> x != mc).toList();
            if (outer.getNameAsString().startsWith("assert") && others.size() == 1) {
                String t = typeOrDeclared(others.get(0), cu, pkg);
                if (t != null) return new Evidence(t, EXACT);
            }
            // assertThat(x.m()).check(..): the kind of value whose assert has check(..) -- when only one kind has it
            if (outer.getParentNode().orElse(null) instanceof MethodCallExpr check && check.getScope().map(s -> s == outer).orElse(false)) {
                String owner = staticImportOwner(cu, outer.getNameAsString());
                if (owner != null) {
                    List<String> args = check.getArguments().stream().map(x -> Optional.ofNullable(typeOrDeclared(x, cu, pkg)).orElse("Object")).toList();
                    List<String> kinds = assertions.valueTypes(owner, outer.getNameAsString(), check.getNameAsString(), args);
                    if (kinds.size() == 1 || (kinds.size() > 1 && allRelated(kinds))) return new Evidence(kinds.get(0), ASSERTED);
                    // ambiguous: a namesake settles it (below); failing that the most specific kind, weakly
                    if (kinds.size() > 1) return new Evidence(kinds.get(0), NONE, kinds);
                }
            }
        }
        return new Evidence("Object", NONE);
    }

    /** Candidate kinds that are one family (a List, a Collection, an Iterable) are not ambiguous; a String and a List are. */
    private static boolean allRelated(List<String> kinds) {
        String first = kinds.get(0).replaceAll("<.*$", "");
        try {
            Class<?> a = Class.forName(first);
            for (String k : kinds.subList(1, kinds.size())) {
                Class<?> b = Class.forName(k.replaceAll("<.*$", ""));
                if (!b.isAssignableFrom(a) && !a.isAssignableFrom(b)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static List<String> mergeParams(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            String x = i < a.size() ? a.get(i) : "Object", y = i < b.size() ? b.get(i) : "Object";
            out.add("Object".equals(x) ? y : x);
        }
        return out;
    }

    private static List<String> argTypes(NodeList<Expression> args, CompilationUnit cu, String pkg) {
        List<String> out = new ArrayList<>();
        for (Expression x : args) {
            if (x.isNullLiteralExpr()) { out.add("Object"); continue; }
            String t = typeOrDeclared(x, cu, pkg);
            out.add(t == null ? "Object" : t.replace("java.lang.", ""));
        }
        return out;
    }

    /** An expression's type: resolved, else as its declaration or creation names it. */
    static String typeOrDeclared(Expression e, CompilationUnit cu, String pkg) {
        try {
            ResolvedType t = e.calculateResolvedType();
            if (t.isPrimitive()) return t.describe();
            if (t.isReferenceType()) return t.asReferenceType().getQualifiedName()
                    + (t.asReferenceType().typeParametersValues().isEmpty() ? "" : "<" + String.join(", ",
                    t.asReferenceType().typeParametersValues().stream().map(v -> v.describe().replace("java.lang.", "")).toList()) + ">");
            return t.describe();
        } catch (Throwable ignored) {
            // resolved below from what the source says
        }
        if (e.isObjectCreationExpr()) return qualified(e.asObjectCreationExpr().getType().getNameAsString(), pkg, cu);
        if (e.isNameExpr()) {
            String n = e.asNameExpr().getNameAsString();
            for (VariableDeclarator v : cu.findAll(VariableDeclarator.class)) if (v.getNameAsString().equals(n)) return qualified(v.getType().toString(), pkg, cu);
            for (Parameter p : cu.findAll(Parameter.class)) if (p.getNameAsString().equals(n)) return qualified(p.getType().toString(), pkg, cu);
        }
        return null;
    }

    private static Optional<String> resolvedName(com.github.javaparser.ast.type.Type t) {
        try {
            return Optional.of(t.resolve().describe().replace("java.lang.", ""));
        } catch (Throwable e) {
            return Optional.empty();
        }
    }

    /** A simple type name as the file's imports or package qualify it. */
    static String qualified(String type, String pkg, CompilationUnit cu) {
        String raw = type.replaceAll("<.*$", "");
        String generics = type.length() > raw.length() ? type.substring(raw.length()) : "";
        if (raw.contains(".") || Character.isLowerCase(raw.isEmpty() ? 'a' : raw.charAt(0)) || raw.equals("String")) return type;
        for (var imp : cu.getImports()) {
            if (!imp.isStatic() && !imp.isAsterisk() && imp.getNameAsString().endsWith("." + raw)) return imp.getNameAsString() + generics;
        }
        return (pkg.isEmpty() ? raw : pkg + "." + raw) + generics;
    }

    static String staticImportOwner(CompilationUnit cu, String method) {
        for (var imp : cu.getImports()) {
            if (!imp.isStatic()) continue;
            String n = imp.getNameAsString();
            if (imp.isAsterisk()) return n;
            if (n.endsWith("." + method)) return n.substring(0, n.lastIndexOf('.'));
        }
        return null;
    }

    private static boolean looksLikeType(String name) {
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0)) && !name.equals(name.toUpperCase());
    }

    private static boolean isProject(String type, Path main, Map<String, Need> needs) {
        String raw = type.replaceAll("<.*$", "");
        return exists(main, raw) || needs.containsKey(raw);
    }

    static boolean exists(Path main, String type) {
        return Files.isRegularFile(main.resolve(type.replaceAll("<.*$", "").replace('.', '/') + ".java"));
    }

    /** Whether the project's source of {@code type} already has a member of this name (any arity). */
    private static boolean declares(Path main, String type, String member) {
        try {
            String src = Files.readString(main.resolve(type.replaceAll("<.*$", "").replace('.', '/') + ".java"));
            return Pattern.compile("\\b" + Pattern.quote(member) + "\\s*\\(").matcher(src).find();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean resolves(Expression e) {
        try {
            if (e instanceof MethodCallExpr m) m.resolve();
            else if (e instanceof ObjectCreationExpr o) o.resolve();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int line(Node n) {
        return n.getBegin().map(b -> b.line).orElse(0);
    }

    static String simple(String fqn) {
        String raw = fqn.replaceAll("<.*$", "");
        return raw.substring(raw.lastIndexOf('.') + 1);
    }
}
