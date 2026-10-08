package com.walpro.autobuilder.core.build.inject;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildErrorClassifier;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.build.ProjectClasspathService;
import com.walpro.autobuilder.core.build.SkeletonFirst;
import com.walpro.autobuilder.core.build.SkeletonStubber;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * T6-T, the hybrid's answer to an invented member: when the compiler says a type has no {@code m()}, the type's REAL
 * members that fit how the candidate used {@code m()} -- its result iterated with these methods called on each element,
 * or these methods called on the result itself -- are each tried in a copy of the tree, and the one that makes the
 * most of the tests using the changed class pass replaces the call. The test picks; no model is asked.
 *
 * <p>Why. ladder-grind field51 run 1 lost its last hour to invented members: {@code RecordDeclaration.getComponents()}
 * 21 times while the library's {@code getParameters()} was in every prompt, {@code BuildOutcome.stderr()} 45 times
 * beside the real {@code stderrTail()}. Offline: of RecordDeclaration's members, seven fit "iterated, getNameAsString()
 * on each" and getParameters() is among them; a name ranking puts the wrong ones first, so the TESTS choose.
 *
 * <p>Library types are read from the project's own classpath, project types from their source. Narrow on purpose: one
 * missing method per proposal, only a call whose candidates fit its use, at most {@link #MAX_CANDIDATES} tried, and
 * only when one of them leaves the tests better than every other (a candidate that does not compile never wins).
 * Maven projects only, as the execution trace; switch {@code autobuilder.inject.real-member.enabled}.
 */
@Slf4j
@Component
@Order(19)
public class RealMemberResolver implements CompileFixResolver {

    static final int MAX_CANDIDATES = 8;

    @Value("${autobuilder.inject.real-member.enabled:true}")
    @Setter
    private boolean enabled = true;

    /** How long one trial (compile and the tests using the class) may take. */
    @Value("${autobuilder.inject.real-member.trial-ms:180000}")
    @Setter
    private long trialMs = 180_000;

    @Autowired(required = false)
    @Setter
    private ProjectClasspathService classpath;

    private static final JavaParser PARSER = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    /** How the candidate used the missing member. */
    /** How the candidate used the missing member, and the type it said it expected back (an element type when iterated). */
    record Use(boolean iterated, Set<String> calledOnResult, int arity, String expected) {
        Use(boolean iterated, Set<String> calledOnResult, int arity) {
            this(iterated, calledOnResult, arity, null);
        }
    }

    @Override
    public Optional<FixProposal> resolve(Classification classification, String missingToken, String stderr,
                                         CycleContextData context) {
        if (!enabled || classification == null || classification.kind() != Kind.CANNOT_FIND_SYMBOL) return Optional.empty();
        if (stderr == null || context == null || context.outputPath() == null) return Optional.empty();
        Path root = Path.of(context.outputPath());
        if (!Files.isRegularFile(root.resolve("pom.xml"))) return Optional.empty();
        String plain = BuildErrorClassifier.ANSI_ESCAPE.matcher(stderr).replaceAll("");
        // every invented member, by the file it is in: they are resolved TOGETHER -- one left behind keeps the
        // file from compiling, and then no test can choose (field51's DeclaredMembers invented two)
        java.util.Map<Path, List<Site>> byFile = new java.util.LinkedHashMap<>();
        for (SkeletonStubber.CompileError e : SkeletonStubber.errors(plain)) {
            if (!"method".equals(e.symbolKind()) || e.location() == null || e.locationKind() == null
                    || !e.locationKind().startsWith("variable")) continue;
            Path file = Path.of(e.file());
            if (!file.isAbsolute()) file = root.resolve(e.file());
            if (!Files.isRegularFile(file)) continue;
            Site site = new Site(e.line(), e.symbol().substring(0, e.symbol().indexOf('(')), e.location().replaceAll("<.*$", ""));
            List<Site> sites = byFile.computeIfAbsent(file, f -> new ArrayList<>());
            if (!sites.contains(site)) sites.add(site);
        }
        for (var f : byFile.entrySet()) {
            Optional<FixProposal> p = tryFile(root, f.getKey(), f.getValue());
            if (p.isPresent()) return p;
        }
        return Optional.empty();
    }

    record Site(int line, String member, String type) {
    }

    private Optional<FixProposal> tryFile(Path root, Path file, List<Site> sites) {
        String source;
        try {
            source = Files.readString(file);
        } catch (Exception ex) {
            return Optional.empty();
        }
        String cp = "";
        try {
            if (classpath != null) cp = classpath.resolveClasspath(root, 120_000);
        } catch (RuntimeException ex) {
            log.debug("T6-T: real members -- no classpath: {}", ex.getMessage());
        }
        List<Site> open = new ArrayList<>();
        List<List<String>> options = new ArrayList<>();
        for (Site s : sites) {
            Optional<Use> use = useAt(source, s.line(), s.member());
            if (use.isEmpty()) continue;
            List<String> fit = fitting(s.type(), s.member(), use.get(), root, cp);
            if (fit.isEmpty()) continue;
            open.add(s);
            options.add(fit.subList(0, Math.min(MAX_CANDIDATES, fit.size())));
        }
        if (open.isEmpty()) return Optional.empty();
        String rel = root.relativize(file).toString().replace('\\', '/');
        String className = file.getFileName().toString().replace(".java", "");
        List<String> tests = SkeletonFirst.testsUsing(root, className);
        // coordinate search: each site's candidates tried with the others at their current best, twice round
        int[] pick = new int[open.size()];
        int best = Integer.MIN_VALUE;
        List<String> tried = new ArrayList<>();
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < open.size(); i++) {
                int keep = pick[i];
                for (int c = 0; c < options.get(i).size(); c++) {
                    if (round > 0 && c == pick[i]) continue;   // already measured with the others as they are
                    pick[i] = c;
                    String changed = applyAll(source, open, options, pick);
                    if (changed == null) continue;
                    int score = trial(root, rel, changed, tests);
                    tried.add(open.get(i).member() + "->" + options.get(i).get(c) + "=" + describe(score));
                    if (score > best) { best = score; keep = c; }
                }
                pick[i] = keep;
            }
            if (open.size() == 1) break;   // one site: one round decides
        }
        log.info("T6-T: real members in {} -- {}", rel, String.join(", ", tried));
        if (best < 0) return Optional.empty();   // nothing compiled: no test could choose
        String changed = applyAll(source, open, options, pick);
        List<String> chosen = new ArrayList<>();
        for (int i = 0; i < open.size(); i++) chosen.add(simple(open.get(i).type()) + "." + open.get(i).member() + "() -> " + options.get(i).get(pick[i]) + "()");
        String description = "The real members that fit the use: " + String.join(", ", chosen) + "; they leave " + best
                + " of the tests using " + className + " passing";
        log.info("CompileFixInjector: real-member -- {}", description);
        return Optional.of(new FixProposal(List.of(new FileMutation(rel, changed)),
                new InjectorFixRecord("real-member:" + open.get(0).member(), Kind.CANNOT_FIND_SYMBOL, open.get(0).member(), List.of(rel), description)));
    }

    private static String describe(int score) {
        return score >= 0 ? score + " passing" : "does not compile (" + (-score - 1) + " errors)";
    }

    /** Every site renamed to its picked candidate, with the variable of an iterated call declared {@code var}. */
    static String applyAll(String source, List<Site> sites, List<List<String>> options, int[] pick) {
        String out = source;
        for (int i = 0; i < sites.size(); i++) {
            out = substitute(out, sites.get(i).line(), sites.get(i).member(), options.get(i).get(pick[i]));
            if (out == null) return null;
        }
        return out;
    }

    /** How the call on {@code line} uses the result of {@code member}; empty when there is no such call. */
    static Optional<Use> useAt(String source, int line, String member) {
        CompilationUnit cu;
        try {
            cu = PARSER.parse(source).getResult().orElse(null);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (cu == null) return Optional.empty();
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals(member) || call.getBegin().map(b -> b.line != line).orElse(true)) continue;
            Node parent = call.getParentNode().orElse(null);
            Set<String> called = new LinkedHashSet<>();
            if (parent instanceof ForEachStmt each && each.getIterable() == call) {
                String var = each.getVariable().getVariables().get(0).getNameAsString();
                for (MethodCallExpr m : each.getBody().findAll(MethodCallExpr.class)) {
                    if (m.getScope().filter(s -> s instanceof NameExpr n && n.getNameAsString().equals(var)).isPresent()) called.add(m.getNameAsString());
                }
                String declared = each.getVariable().getElementType().asString();
                return Optional.of(new Use(true, called, call.getArguments().size(), "var".equals(declared) ? null : qualify(declared, cu)));
            }
            if (parent instanceof MethodCallExpr outer && outer.getScope().map(s -> s == call).orElse(false)) {
                called.add(outer.getNameAsString());
            }
            String expected = parent instanceof com.github.javaparser.ast.body.VariableDeclarator v && !"var".equals(v.getTypeAsString())
                    ? qualify(v.getTypeAsString(), cu) : null;
            return Optional.of(new Use(false, called, call.getArguments().size(), expected));
        }
        return Optional.empty();
    }

    /** The receiver's real members that fit the use, the missing one excluded. */
    static List<String> fitting(String type, String missing, Use use, Path root, String classpath) {
        List<String> out = new ArrayList<>();
        Path src = root.resolve("src/main/java/" + type.replace('.', '/') + ".java");
        if (Files.isRegularFile(src)) {
            // a project type: its declared methods of the arity, by what its source says they return
            try {
                CompilationUnit cu = PARSER.parse(Files.readString(src)).getResult().orElse(null);
                if (cu == null) return out;
                if (use.arity() == 0) {
                    for (var rec : cu.findAll(com.github.javaparser.ast.body.RecordDeclaration.class)) {
                        for (var comp : rec.getParameters()) {
                            Class<?> ret = jdk(comp.getType().asString());
                            if (comp.getNameAsString().equals(missing) || (ret != null && !fits(ret, null, use))) continue;
                            if (!out.contains(comp.getNameAsString())) out.add(comp.getNameAsString());
                        }
                    }
                }
                for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
                    if (m.getNameAsString().equals(missing) || m.getParameters().size() != use.arity() || m.getType().isVoidType()) continue;
                    Class<?> ret = jdk(m.getType().asString());
                    if (ret != null && !fits(ret, null, use)) continue;
                    if (!out.contains(m.getNameAsString())) out.add(m.getNameAsString());
                }
                return out;
            } catch (Exception e) {
                return out;
            }
        }
        // a library type: from the project's own classpath
        try (URLClassLoader loader = loader(classpath)) {
            Class<?> c = Class.forName(type, false, loader);
            Class<?> expected = load(use.expected(), loader);
            java.util.Map<String, Double> likeness = new java.util.HashMap<>();
            for (Method m : c.getMethods()) {
                if (m.getName().equals(missing) || m.getParameterCount() != use.arity() || m.getReturnType() == void.class
                        || m.getDeclaringClass() == Object.class) continue;
                if (!fits(m.getReturnType(), m.getGenericReturnType(), use)) continue;
                if (!out.contains(m.getName())) out.add(m.getName());
                Class<?> gives = use.iterated() ? elementOf(m.getGenericReturnType()) : m.getReturnType();
                likeness.merge(m.getName(), likeness(gives, expected), Math::max);
            }
            // the TESTS choose; where they tie, what the candidate said it expected back does: the real member whose
            // result is most like it comes first (field51: VariableDeclarator expected, getParameters()'s Parameter is
            // far more like it than getTypeParameters()'s TypeParameter)
            out.sort(java.util.Comparator.comparingDouble((String n) -> -likeness.getOrDefault(n, 0.0)));
        } catch (Throwable e) {
            log.debug("T6-T: real members of {} not readable: {}", type, e.getMessage());
        }
        return out;
    }

    /** How alike two types are, by the public method names they share (Jaccard); 0 when either is unknown. */
    static double likeness(Class<?> a, Class<?> b) {
        if (a == null || b == null) return 0;
        if (a == b) return 1;
        Set<String> x = new java.util.HashSet<>(), y = new java.util.HashSet<>();
        for (Method m : a.getMethods()) if (m.getDeclaringClass() != Object.class) x.add(m.getName());
        for (Method m : b.getMethods()) if (m.getDeclaringClass() != Object.class) y.add(m.getName());
        if (x.isEmpty() || y.isEmpty()) return 0;
        Set<String> both = new java.util.HashSet<>(x);
        both.retainAll(y);
        x.addAll(y);
        return (double) both.size() / x.size();
    }

    private static Class<?> load(String type, ClassLoader loader) {
        if (type == null) return null;
        try {
            return Class.forName(type.replaceAll("<.*$", ""), false, loader);
        } catch (Throwable e) {
            return null;
        }
    }

    /** A simple type name as the file's imports qualify it; as written when they do not. */
    private static String qualify(String type, CompilationUnit cu) {
        String raw = type.replaceAll("<.*$", "");
        if (raw.contains(".")) return raw;
        for (var imp : cu.getImports()) {
            if (!imp.isStatic() && !imp.isAsterisk() && imp.getNameAsString().endsWith("." + raw)) return imp.getNameAsString();
        }
        for (var imp : cu.getImports()) {
            if (imp.isAsterisk() && !imp.isStatic()) return imp.getNameAsString() + "." + raw;   // a guess the loader checks
        }
        return cu.getPackageDeclaration().map(p -> p.getNameAsString() + "." + raw).orElse(raw);
    }

    private static boolean fits(Class<?> ret, Type generic, Use use) {
        if (use.iterated()) {
            if (!Iterable.class.isAssignableFrom(ret)) return false;
            Class<?> elem = elementOf(generic);
            return elem == null || use.calledOnResult().stream().allMatch(n -> hasMethod(elem, n));
        }
        return use.calledOnResult().stream().allMatch(n -> hasMethod(ret, n));
    }

    private static boolean hasMethod(Class<?> c, String name) {
        return Arrays.stream(c.getMethods()).anyMatch(m -> m.getName().equals(name));
    }

    private static Class<?> elementOf(Type g) {
        if (g instanceof ParameterizedType p && p.getActualTypeArguments().length == 1) {
            Type e = p.getActualTypeArguments()[0];
            if (e instanceof Class<?> c) return c;
            if (e instanceof WildcardType w && w.getUpperBounds()[0] instanceof Class<?> c) return c;
            if (e instanceof TypeVariable<?> v && v.getBounds()[0] instanceof Class<?> c) return c;
        }
        return null;
    }

    private static Class<?> jdk(String typeName) {
        String raw = typeName.replaceAll("<.*$", "");
        for (String prefix : List.of("", "java.lang.", "java.util.")) {
            try {
                return Class.forName(prefix + raw);
            } catch (Throwable ignored) {
                // not a JDK type: no constraint from its return
            }
        }
        return null;
    }

    private static URLClassLoader loader(String classpath) throws Exception {
        List<URL> urls = new ArrayList<>();
        if (classpath != null) for (String p : classpath.split(File.pathSeparator)) if (!p.isBlank()) urls.add(Path.of(p).toUri().toURL());
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    /** The source with the call of {@code member} on {@code line} renamed to {@code to}; null when the line has none. */
    static String substitute(String source, int line, String member, String to) {
        String[] lines = source.split("\n", -1);
        if (line < 1 || line > lines.length) return null;
        String after = lines[line - 1].replaceFirst("\\.\\s*" + Pattern.quote(member) + "\\s*\\(", Matcher.quoteReplacement("." + to + "("));
        if (after.equals(lines[line - 1])) return null;
        // for (Declared x : r.member()) -- the elements of the real member are another type: let the compiler say it
        after = after.replaceFirst("for\\s*\\(\\s*(?:final\\s+)?[\\w.<>\\[\\], ?]+\\s+(\\w+)\\s*:", "for (var $1 :");
        lines[line - 1] = after;
        return String.join("\n", lines);
    }

    /**
     * The tests using the changed class, run in a copy of the tree with {@code changed} written: how many pass, or -1
     * when it does not compile. With no such tests, compiling is the whole check (0).
     */
    private int trial(Path root, String rel, String changed, List<String> tests) {
        Path copy = null;
        try {
            copy = Files.createTempDirectory("real-member");
            copyTree(root, copy);
            Files.writeString(copy.resolve(rel), changed);
            List<String> cmd = new ArrayList<>(List.of("mvn", "-o", "-q", "-B"));
            if (tests.isEmpty()) cmd.add("test-compile");
            else cmd.addAll(List.of("test", "-Dtest=" + String.join(",", tests), "-Dsurefire.failIfNoSpecifiedTests=false"));
            Process p = new ProcessBuilder(cmd).directory(copy.toFile()).redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (!p.waitFor(trialMs, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); return -1; }
            Path reports = copy.resolve("target/surefire-reports");
            int compileErrors = SkeletonStubber.errors(output).size();
            if (tests.isEmpty()) return p.exitValue() == 0 ? 0 : -(compileErrors + 1);
            if (!Files.isDirectory(reports)) return -(Math.max(compileErrors, 1) + 1);   // it did not compile
            int passing = 0;
            try (Stream<Path> files = Files.list(reports)) {
                for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".txt"))::iterator) {
                    Matcher m = Pattern.compile("Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+)").matcher(Files.readString(f));
                    if (m.find()) passing += Integer.parseInt(m.group(1)) - Integer.parseInt(m.group(2)) - Integer.parseInt(m.group(3));
                }
            }
            return passing;
        } catch (Exception e) {
            return -1;
        } catch (Throwable t) {
            return -1;
        } finally {
            if (copy != null) deleteQuietly(copy);
        }
    }

    private static void copyTree(Path from, Path to) throws Exception {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                Path rel = from.relativize(p);
                if (rel.toString().startsWith("target") || rel.toString().startsWith(".git")) continue;
                Path dest = to.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else Files.copy(p, dest);
            }
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // a leftover temp file is harmless
                }
            });
        } catch (Exception ignored) {
            // nothing to delete
        }
    }

    private static String simple(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }
}
