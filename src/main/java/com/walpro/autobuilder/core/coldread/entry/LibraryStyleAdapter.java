package com.walpro.autobuilder.core.coldread.entry;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.walpro.autobuilder.core.build.BuildExecutor;
import com.walpro.autobuilder.core.coldread.ClaimProposer.Claim;
import com.walpro.autobuilder.core.coldread.ColdRead;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Observation;
import com.walpro.autobuilder.goal.DerivedTests;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * T4-EK, library-style: code exercised inside the same process, by a test that calls it. Its entry points are the public
 * methods (with a body) of its public types; a claim is the body of a test method that passes when the claim holds,
 * run by the project's own build with the configured test command -- the throwaway-tree runner the derived tests use.
 *
 * <p>The test file around a claim is data ({@code template}, {@code marker}): which test framework, how a test is
 * declared and how it asserts. A build that does not declare the framework the template uses gets no claims run, and
 * its account says so -- no framework is guessed.
 */
@Slf4j
@Component
public class LibraryStyleAdapter implements EntryAdapter {

    private static final String MAIN = "src/main/java/";
    static final String CLASS_PREFIX = "RevClaim";

    private final DerivedTests.BuildBench bench;
    private final String marker;
    private final String template;
    private final String assertions;

    /** The test class around a claim -- package, imports, class name, body, in that order -- for the framework below. */
    static final String TEMPLATE = """
            package %s;

            %s
            import java.util.*;
            import static org.junit.jupiter.api.Assertions.*;

            class %s {

                @org.junit.jupiter.api.Test
                @org.junit.jupiter.api.Timeout(10)
                void claim() throws Throwable {
            %s
                }
            }
            """;

    /** How a claim asserts under {@link #TEMPLATE}, for the model. */
    static final String ASSERTIONS = "the static methods of org.junit.jupiter.api.Assertions (assertEquals, assertTrue, "
            + "assertFalse, assertThrows, ...), already imported";

    public LibraryStyleAdapter(BuildExecutor buildExecutor,
                               // The command that runs the project's tests (the test gate's own).
                               @Value("${autobuilder.build.test-gate.command:mvn test}") String testCommand,
                               // What the build file must declare for the claim template to run.
                               @Value("${autobuilder.reverse-engineering.library.marker:junit-jupiter}") String marker) {
        this.bench = new DerivedTests.BuildBench(buildExecutor, testCommand);
        this.marker = marker;
        this.template = TEMPLATE;
        this.assertions = ASSERTIONS;
    }

    @Override
    public List<EntryPoint> find(Map<String, String> files, ColdRead coldRead) {
        List<EntryPoint> out = new ArrayList<>();
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
        files.forEach((path, text) -> {
            if (!path.endsWith(".java") || !path.contains(MAIN)) return;
            CompilationUnit cu = parser.parse(text).getResult().orElse(null);
            if (cu == null) return;
            for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
                if (!publiclyReachable(t)) continue;
                for (MethodDeclaration m : t.getMethods()) {
                    if (!m.isPublic() || m.getBody().isEmpty()) continue;
                    if (m.isStatic() && m.getNameAsString().equals("main")) continue;   // a program's way in, not a call
                    out.add(new EntryPoint(EntryKind.LIBRARY_STYLE, nested(t) + "." + m.getNameAsString() + "("
                            + String.join(", ", m.getParameters().stream().map(p -> p.getType().asString()).toList()) + ")",
                            nested(t), qualified(t), m.getNameAsString()));
                }
            }
        });
        return out;
    }

    @Override
    public String exerciseForm(EntryKind kind) {
        return """
                TEST:
                <the statements of ONE test method, in Java: set up what is needed, call the code, and assert with \
                %s. The test passes exactly when the claim holds. Use the project's types by their simple names.>
                END
                """.formatted(assertions);
    }

    @Override
    public Ran exercise(Path dir, String variant, List<Claim> claims, Map<String, Map<String, Observation>> seen) {
        if (claims.isEmpty()) return new Ran(true, true, null);
        try {
            if (!declaresFramework(dir)) {
                return new Ran(false, false, "the build declares no '" + marker + "' to run claims with");
            }
            Path tree = dir.resolveSibling(dir.getFileName() + "-claims");
            copyWithoutTests(dir, tree);
            String broken = bench.compiles(tree);
            if (broken != null) {
                log.warn("T4-EK: variant {} does not build for its claims -- {}", variant, Observation.tail(broken));
                return new Ran(false, false, Observation.tail(broken));
            }
            List<EntryPoint> points = find(com.walpro.autobuilder.core.merge.ProjectMerge.files(tree), null);
            Map<String, String> tests = new LinkedHashMap<>();
            Map<String, String> pathOf = new LinkedHashMap<>();
            String pkg = homePackage(points);
            for (Claim k : claims) {
                String cls = CLASS_PREFIX + Character.toUpperCase(k.id().charAt(0)) + k.id().substring(1) + "Test";
                String path = "src/test/java/" + (pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/") + cls + ".java";
                tests.put(path, source(pkg, imports(points, pkg), cls, k.test()));
                pathOf.put(k.id(), path);
            }
            Map<String, Boolean> passed = bench.run(tree, tests);
            for (Claim k : claims) {
                boolean held = Boolean.TRUE.equals(passed.get(pathOf.get(k.id())));
                seen.get(k.id()).put(variant, new Observation(variant, held ? 0 : 1, null, null, held));
            }
            return new Ran(true, true, null);
        } catch (IOException e) {
            return new Ran(false, false, e.getMessage());
        }
    }

    /** The package most entry points are in: claims are written there, the other packages' types imported. */
    public static String homePackage(List<EntryPoint> points) {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (EntryPoint p : points) count.merge(packageOf(p.qualifiedType(), p.type()), 1, Integer::sum);
        return count.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
    }

    public String source(String pkg, String imports, String cls, String body) {
        String indented = String.join("\n", body.lines().map(l -> "        " + l).toList());
        String text = template.formatted(pkg, imports, cls, indented);
        return pkg.isEmpty() ? text.replaceFirst("(?m)^package ;\\s*\\n", "") : text;
    }

    /** The project's public types from other packages, one per simple name, so a claim can name them simply. */
    public static String imports(List<EntryPoint> points, String pkg) {
        Map<String, String> bySimple = new LinkedHashMap<>();
        for (EntryPoint p : points) {
            String outer = p.type().contains(".") ? p.type().substring(0, p.type().indexOf('.')) : p.type();
            String q = packageOf(p.qualifiedType(), p.type());
            if (q.equals(pkg)) continue;
            bySimple.putIfAbsent(outer, (q.isEmpty() ? "" : q + ".") + outer);
        }
        StringBuilder sb = new StringBuilder();
        bySimple.values().forEach(q -> sb.append("import ").append(q).append(";\n"));
        return sb.toString();
    }

    static String packageOf(String qualifiedType, String nestedType) {
        if (qualifiedType.equals(nestedType)) return "";
        return qualifiedType.substring(0, qualifiedType.length() - nestedType.length() - 1);
    }

    private boolean declaresFramework(Path dir) throws IOException {
        for (String build : List.of("pom.xml", "build.gradle", "build.gradle.kts")) {
            Path f = dir.resolve(build);
            if (Files.isRegularFile(f) && Files.readString(f).contains(marker)) return true;
        }
        return false;
    }

    /** The tree without its own tests -- they are the project's evidence, not run beside the claims -- or its build output. */
    private static void copyWithoutTests(Path from, Path to) throws IOException {
        if (Files.exists(to)) {
            try (Stream<Path> w = Files.walk(to)) {
                for (Path p : w.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                String rel = from.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("target") || rel.startsWith("build/") || rel.equals("build") || rel.startsWith("src/test")) continue;
                Path target = to.resolve(rel);
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target);
            }
        }
    }

    private static boolean publiclyReachable(TypeDeclaration<?> t) {
        Node n = t;
        while (n instanceof TypeDeclaration<?> td) {
            boolean isPublic = td.hasModifier(Modifier.Keyword.PUBLIC)
                    || td.getParentNode().map(p -> p instanceof TypeDeclaration<?> o && o.isClassOrInterfaceDeclaration()
                    && o.asClassOrInterfaceDeclaration().isInterface()).orElse(false);
            if (!isPublic) return false;
            n = td.getParentNode().orElse(null);
        }
        return true;
    }

    private static String nested(TypeDeclaration<?> t) {
        String name = t.getNameAsString();
        Node n = t.getParentNode().orElse(null);
        while (n instanceof TypeDeclaration<?> outer) {
            name = outer.getNameAsString() + "." + name;
            n = outer.getParentNode().orElse(null);
        }
        return name;
    }

    private static String qualified(TypeDeclaration<?> t) {
        String pkg = t.findCompilationUnit().flatMap(CompilationUnit::getPackageDeclaration).map(p -> p.getNameAsString()).orElse("");
        return pkg.isEmpty() ? nested(t) : pkg + "." + nested(t);
    }
}
