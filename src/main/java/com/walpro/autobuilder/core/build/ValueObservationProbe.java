package com.walpro.autobuilder.core.build;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * T4-CQ: OBSERVE what an entry point returns, instead of waiting for a model to guess it.
 *
 * <p><b>The gap it closes.</b> structdiff runs 11 and 12 stalled at 8/11 and 7/11 on the same three
 * entry points: the test-source check refused closure because the tests calling {@code
 * DiffSummarizer.summarize} asserted only that a string came back, and the model, not knowing the
 * exact string, kept writing {@code contains(...)} checks -- whose failures report {@code true}/{@code
 * false}, never the value. The seed says "call it, observe the answer, and assert that"; nothing in
 * the loop could observe.
 *
 * <p><b>How.</b> For a test method that calls the entry point and stores its result in a local
 * ({@code String s = DiffSummarizer.summarize(root);}), a temporary copy of the test class gets an
 * equality assertion against a sentinel right after that statement, and only that method is run. The
 * sentinel never matches, so the run reports {@code but was: <what the code returned>}. The copy is
 * deleted whatever happens and is never part of a candidate. Sources not written against the one
 * assertion call the vocabulary names ({@link TestAssertionVocabulary#PROBE_ASSERTION}) are left alone.
 */
@Slf4j
@Component
@com.walpro.autobuilder.core.checkpoint.StateHolder(
        value = com.walpro.autobuilder.core.checkpoint.StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "stateless apart from its configuration; each observation is written to ObservedBehaviour")
public class ValueObservationProbe {

    static final String SENTINEL = "␀observed-value-probe";
    private static final String PROBE_SUFFIX = "ValueObservation";
    private static final int MAX_PER_ENTRY_POINT = 2;
    private static final int MAX_VALUE = 600;

    private final BuildExecutor buildExecutor;

    @Value("${file.base-path}")
    @Setter
    private String fileBasePath;

    @Setter
    @Autowired(required = false)
    private ObservedBehaviour observedBehaviour;

    public ValueObservationProbe(BuildExecutor buildExecutor) {
        this.buildExecutor = buildExecutor;
    }

    /** One observed value: which call, in which test, returned what. */
    public record Observation(String entryPoint, String test, String value) {
        public String describe() {
            return entryPoint + " returned " + value + " (observed in " + test + ")";
        }
    }

    /**
     * Observe the values the unasserted entry points return, through the tests that already call
     * them. Never throws; returns what it could observe.
     */
    public List<Observation> observe(Map<String, EntryPointAssertions.Finding> unasserted,
                                     Map<String, String> files, String cycleId) {
        List<Observation> out = new ArrayList<>();
        if (unasserted == null || unasserted.isEmpty() || files == null || !buildExecutor.isEnabled()) return out;
        Path project = Path.of(fileBasePath, "output");
        Map<String, Boolean> done = new LinkedHashMap<>();
        for (EntryPointAssertions.Finding f : unasserted.values()) {
            String key = f.type() + "." + f.method();
            if (done.putIfAbsent(key, true) != null) continue;
            int tried = 0;
            for (String test : f.callingTests()) {
                if (tried++ >= MAX_PER_ENTRY_POINT) break;
                try {
                    probe(f, test, files, project, cycleId).ifPresent(out::add);
                } catch (Exception e) {
                    log.info("T4-CQ: value observation of {} in {} failed: {}", key, test, e.getMessage());
                }
                if (!out.isEmpty() && out.get(out.size() - 1).entryPoint().startsWith(key)) break;
            }
        }
        if (observedBehaviour != null && !out.isEmpty()) {
            String digest = ObservedBehaviour.mainDigestOnDisk(project);
            for (Observation o : out) observedBehaviour.observeValue(o.test(), o.entryPoint() + " returned " + o.value(), digest);
        }
        return out;
    }

    /** At most this many entry points are observed for one rejected candidate: each is a test run. */
    private static final int MAX_ON_FAILURE = 3;

    /**
     * The same observation, where a candidate's OWN new tests fail: for each seed entry point those
     * failing tests call, what it actually returned there -- whole, so a record reports every field
     * at once. A failing test reports only its first failed assertion, so each rejection taught one
     * value while the test's other guesses stayed wrong (participation run 2026-09-25_11-45-07: 20
     * of 23 rejections were the model's own failing tests, one test class rewritten nine times, and
     * the probe -- reached only at root closure -- ran once, before any test called those entry
     * points). Reads the candidate as it is on disk; never throws.
     */
    public List<Observation> observeFailing(java.util.Collection<TestRunReport.Failure> failing,
                                            List<String> obligations, String cycleId) {
        if (failing == null || failing.isEmpty() || obligations == null || !buildExecutor.isEnabled()) return List.of();
        try {
            Path project = Path.of(fileBasePath, "output");
            Map<String, String> files = new LinkedHashMap<>();
            for (String root : List.of("src/main", "src/test")) {
                Path dir = project.resolve(root);
                if (!Files.isDirectory(dir)) continue;
                try (var walk = Files.walk(dir)) {
                    for (Path p : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".java"))::iterator) {
                        files.put(project.relativize(p).toString().replace('\\', '/'), Files.readString(p));
                    }
                }
            }
            java.util.Set<String> tests = new java.util.LinkedHashSet<>();
            for (TestRunReport.Failure f : failing) {
                if (f.className() == null || f.method() == null) continue;
                String cls = f.className().substring(f.className().lastIndexOf('.') + 1);
                int nested = cls.indexOf('$');
                tests.add((nested >= 0 ? cls.substring(nested + 1) : cls) + "." + f.method());
            }
            Map<String, EntryPointAssertions.Finding> called = new LinkedHashMap<>();
            for (var e : EntryPointAssertions.calledBy(obligations, files, tests).entrySet()) {
                if (called.size() == MAX_ON_FAILURE) break;
                called.put(e.getKey(), e.getValue());
            }
            return observe(called, files, cycleId);
        } catch (Exception e) {
            log.info("T4-CQ: value observation of failing tests failed: {}", e.getMessage());
            return List.of();
        }
    }

    private Optional<Observation> probe(EntryPointAssertions.Finding f, String test, Map<String, String> files,
                                        Path project, String cycleId) throws Exception {
        int dot = test.lastIndexOf('.');
        String cls = test.substring(0, dot);
        String method = test.substring(dot + 1);
        Map.Entry<String, String> source = files.entrySet().stream()
                .filter(e -> e.getKey().replace('\\', '/').endsWith("/" + cls + ".java"))
                .findFirst().orElse(null);
        if (source == null) return Optional.empty();

        Optional<String> probeSource = instrument(source.getValue(), cls, method, f.type(), f.method());
        if (probeSource.isEmpty()) return Optional.empty();
        String probeRel = source.getKey().replace('\\', '/').replaceFirst(cls + "\\.java$", cls + PROBE_SUFFIX + ".java");
        Path probeFile = project.resolve(probeRel);
        String pkg = packageOf(source.getValue());
        String fqcn = (pkg.isEmpty() ? "" : pkg + ".") + cls + PROBE_SUFFIX;
        Path reportDir = project.resolve("target/surefire-reports");
        try {
            Files.writeString(probeFile, probeSource.get());
            buildExecutor.execute(fileBasePath, cycleId + "-observe",
                    "mvn -q test -Dtest=" + fqcn + "#" + method
                            + " -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false");
            Optional<String> value = sentinelMismatch(reportDir.resolve("TEST-" + fqcn + ".xml"));
            value.ifPresent(v -> log.info("T4-CQ: observed {}.{} in {} -> {}", f.type(), f.method(), test, v));
            return value.map(v -> new Observation(f.type() + "." + f.method() + "(...)", test, v));
        } finally {
            Files.deleteIfExists(probeFile);
            Files.deleteIfExists(reportDir.resolve("TEST-" + fqcn + ".xml"));
            Files.deleteIfExists(reportDir.resolve(fqcn + ".txt"));
            Path classes = project.resolve("target/test-classes").resolve(fqcn.replace('.', '/') + ".class");
            Files.deleteIfExists(classes);
        }
    }

    /**
     * The test class renamed, with a sentinel equality assertion after the first statement in
     * {@code method} that stores the entry point's result in a local. Empty when there is no such
     * statement, or the source is not written against the probe's assertion call.
     */
    static Optional<String> instrument(String source, String cls, String method, String type, String entryMethod) {
        if (source == null || !source.contains(TestAssertionVocabulary.PROBE_ASSERTION_IMPORT)) return Optional.empty();
        Optional<CompilationUnit> parsed = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)).parse(source).getResult();
        if (parsed.isEmpty()) return Optional.empty();
        CompilationUnit cu = parsed.get();
        Optional<ClassOrInterfaceDeclaration> klass = cu.getClassByName(cls);
        if (klass.isEmpty()) return Optional.empty();
        MethodDeclaration md = klass.get().getMethodsByName(method).stream().findFirst().orElse(null);
        if (md == null || md.getBody().isEmpty()) return Optional.empty();
        BlockStmt body = md.getBody().get();
        for (int i = 0; i < body.getStatements().size(); i++) {
            Statement st = body.getStatement(i);
            if (!(st instanceof ExpressionStmt es) || !es.getExpression().isVariableDeclarationExpr()) continue;
            for (VariableDeclarator v : es.getExpression().asVariableDeclarationExpr().getVariables()) {
                if (v.getInitializer().isEmpty()) continue;
                boolean callsIt = v.getInitializer().get().findAll(MethodCallExpr.class).stream()
                        .anyMatch(c -> c.getNameAsString().equals(entryMethod)) || (v.getInitializer().get() instanceof MethodCallExpr mc
                        && mc.getNameAsString().equals(entryMethod));
                if (!callsIt) continue;
                body.addStatement(i + 1, StaticJavaParser.parseStatement(TestAssertionVocabulary.PROBE_ASSERTION
                        .replace("$SENTINEL", "\"" + SENTINEL + "\"").replace("$VALUE", "String.valueOf(" + v.getNameAsString() + ")")));
                klass.get().setName(cls + PROBE_SUFFIX);
                klass.get().getConstructors().forEach(c -> c.setName(cls + PROBE_SUFFIX));
                return Optional.of(cu.toString());
            }
        }
        return Optional.empty();
    }

    /** The {@code but was} side of the sentinel assertion's failure, whole (it may span lines). */
    static Optional<String> sentinelMismatch(Path reportXml) {
        if (!Files.isRegularFile(reportXml)) return Optional.empty();
        try {
            var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(reportXml.toFile());
            NodeList failures = doc.getElementsByTagName("failure");
            for (int i = 0; i < failures.getLength(); i++) {
                String msg = ((Element) failures.item(i)).getAttribute("message");
                int s = msg.indexOf(SENTINEL);
                int but = msg.indexOf("but was: <", s < 0 ? 0 : s);
                if (s < 0 || but < 0) continue;
                String value = msg.substring(but + "but was: <".length());
                if (value.endsWith(">")) value = value.substring(0, value.length() - 1);
                value = value.replace("\r", "");
                if (value.length() > MAX_VALUE) value = value.substring(0, MAX_VALUE) + "...";
                return Optional.of("\"" + value.replace("\n", "\\n") + "\"");
            }
        } catch (Exception e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static String packageOf(String source) {
        var m = java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(source);
        return m.find() ? m.group(1) : "";
    }
}
