package com.walpro.autobuilder.core.coldread;

import com.walpro.autobuilder.analysis.structure.Endpoint;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads a project the loop has never seen and reports what is there.
 *
 * <p>Deterministic on purpose: no model call anywhere in this class. Everything it reports is
 * parsed, matched or listed, so the same directory always produces the same account and a reader
 * can check any line of it against the tree. The model's turn comes after there is something
 * grounded for it to reason about.
 *
 * <p><b>The tree is not the loop's own, and that changes the reading rules.</b> A tree the loop
 * built contains only what the loop wrote. An imported one contains build-tool scaffolding, wrapper
 * binaries, editor files and vendored code, and the existing whole-tree reader treats every file as
 * project source: pointed at the first imported project it tried to read a wrapper jar as text on
 * every cycle and pulled a Windows batch script into its prompts. So this walks the tree itself,
 * skips what is not source, and REPORTS what it skipped rather than passing it on in silence.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ColdReadService {

    /** Directories that belong to the tooling rather than to the project. */
    private static final Set<String> NOT_PROJECT_DIRS =
            Set.of("target", "build", "out", ".git", ".idea", ".gradle", ".mvn", "node_modules", ".settings");

    /** Extensions worth reading as text. Anything else is listed as skipped, never parsed. */
    private static final Set<String> TEXT_SUFFIXES =
            Set.of(".java", ".xml", ".properties", ".yml", ".yaml", ".md", ".txt", ".json", ".sql");

    private static final long MAX_TEXT_BYTES = 512_000;
    private static final int MAX_PROSE_LINES = 40;

    private static final Pattern TAG = Pattern.compile("<(\\w[\\w.-]*)>([^<]+)</\\1>");
    private static final Pattern DEPENDENCY =
            Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);

    private final EndpointSurfaceQuery endpointSurfaceQuery;

    /** Reads {@code root} and reports what is there. Never throws: an unreadable tree is an account too. */
    public ColdRead read(Path root) {
        List<String> skipped = new ArrayList<>();
        List<String> uncertainties = new ArrayList<>();
        Map<String, String> text = readTextFiles(root, skipped);

        Map<String, String> java = new LinkedHashMap<>();
        text.forEach((p, c) -> { if (p.endsWith(".java")) java.put(p, c); });

        ColdRead.Build build = readBuild(text, uncertainties);
        ColdRead.Runtime runtime = readRuntime(text);
        List<String> prose = readProse(text);

        Optional<Set<Endpoint>> served =
                endpointSurfaceQuery.servedByCode(mainSources(java));
        List<String> surface = served.map(s -> s.stream()
                        .map(e -> e.verb() + " " + e.path()).sorted().toList())
                .orElse(List.of());
        if (served.isEmpty()) {
            uncertainties.add("the request surface could not be listed in full: at least one mapping "
                    + "path is not a literal in the source, so what is served cannot be read off the code alone");
        }

        List<String> types = readTypes(java);
        List<String> tests = readTests(java);

        if (prose.isEmpty()) {
            uncertainties.add(hasReadme(text)
                    ? "the project says nothing about itself in words: its README holds only markup (images, links, tags)"
                    : "the project says nothing about itself in words: no README or equivalent was found");
        }
        if (tests.isEmpty()) {
            uncertainties.add("no tests were found, so nothing here states the intended behaviour except the code itself");
        }

        ColdRead reading = new ColdRead(root.toString(), build, runtime, prose, surface,
                served.isPresent(), types, tests, skipped, uncertainties);
        log.info("cold read of {}: {} type(s), {} route(s), {} test(s), {} file(s) skipped, {} uncertainty(ies)",
                root, types.size(), surface.size(), tests.size(), skipped.size(), uncertainties.size());
        return reading;
    }

    /** Only main sources declare the served surface; a test's annotations would inflate it. */
    private static Map<String, String> mainSources(Map<String, String> java) {
        Map<String, String> main = new LinkedHashMap<>();
        java.forEach((p, c) -> { if (!isTestPath(p)) main.put(p, c); });
        return main;
    }

    private static boolean isTestPath(String path) {
        return path.contains("/test/") || path.startsWith("test/");
    }

    private Map<String, String> readTextFiles(Path root, List<String> skipped) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) {
            skipped.add(root + " (not a directory)");
            return out;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
            for (Path file : files) {
                String rel = root.relativize(file).toString().replace('\\', '/');
                if (inToolingDir(rel)) { skipped.add(rel + " (build tooling, not project source)"); continue; }
                String suffix = rel.contains(".") ? rel.substring(rel.lastIndexOf('.')) : "";
                if (!TEXT_SUFFIXES.contains(suffix)) { skipped.add(rel + " (not a text source file)"); continue; }
                try {
                    if (Files.size(file) > MAX_TEXT_BYTES) { skipped.add(rel + " (larger than the read limit)"); continue; }
                    out.put(rel, Files.readString(file));
                } catch (MalformedInputException e) {
                    skipped.add(rel + " (not readable as text)");
                } catch (IOException e) {
                    skipped.add(rel + " (" + e.getMessage() + ")");
                }
            }
        } catch (IOException e) {
            skipped.add(root + " (" + e.getMessage() + ")");
        }
        return out;
    }

    private static boolean inToolingDir(String relativePath) {
        for (String segment : relativePath.split("/")) {
            if (NOT_PROJECT_DIRS.contains(segment)) return true;
        }
        return false;
    }

    private static final Pattern PARENT = Pattern.compile("(?s)<parent>(.*?)</parent>");
    private static final Pattern NESTED_BLOCKS = Pattern.compile(
            "(?s)<(parent|dependencies|dependencyManagement|build|profiles|reporting|properties|modules|repositories|pluginRepositories|distributionManagement|developers|licenses|scm)>.*?</\\1>");
    private static final Pattern JAVA_VERSION = Pattern.compile(
            "<(java\\.version|maven\\.compiler\\.release|maven\\.compiler\\.source)>([^<]+)</\\1>");

    private ColdRead.Build readBuild(Map<String, String> text, List<String> uncertainties) {
        String pom = text.get("pom.xml");
        if (pom == null) {
            String gradle = text.containsKey("build.gradle") ? "build.gradle"
                    : (text.containsKey("build.gradle.kts") ? "build.gradle.kts" : null);
            if (gradle != null) {
                uncertainties.add("the build file is " + gradle + ", which this reading does not parse: "
                        + "the coordinates and dependencies below are unknown rather than absent");
                return new ColdRead.Build("gradle", null, null, null, List.of());
            }
            uncertainties.add("no build file was found, so how this project is built is unknown");
            return new ColdRead.Build(null, null, null, null, List.of());
        }
        // The project's own coordinates are the ones at the top level. They used to be read by
        // position -- "the first artifactId is the parent's, the next is ours" -- which holds only
        // when a parent block exists: the second adopted project has none, and its cold read named
        // a DEPENDENCY as the project. So the nested blocks are removed first, and the parent's
        // coordinates are read from the parent block itself.
        Matcher parentBlock = PARENT.matcher(pom);
        Map<String, String> parent = new LinkedHashMap<>();
        if (parentBlock.find()) {
            Matcher pm = TAG.matcher(parentBlock.group(1));
            while (pm.find()) parent.putIfAbsent(pm.group(1), pm.group(2).trim());
        }
        String topLevel = NESTED_BLOCKS.matcher(pom).replaceAll("");
        Map<String, String> first = new LinkedHashMap<>();
        Matcher m = TAG.matcher(topLevel);
        while (m.find()) first.putIfAbsent(m.group(1), m.group(2).trim());
        // Properties are nested but are the project's own; java.version lives there.
        Matcher javaVersion = JAVA_VERSION.matcher(pom);
        String java = javaVersion.find() ? javaVersion.group(2).trim() : null;

        String framework = null;
        if ("spring-boot-starter-parent".equals(parent.get("artifactId"))) {
            framework = "Spring Boot " + parent.getOrDefault("version", "(version not stated)");
        }
        List<String> dependencies = new ArrayList<>();
        Matcher d = DEPENDENCY.matcher(pom);
        while (d.find()) {
            Matcher inner = TAG.matcher(d.group(1));
            String g = null, a = null;
            while (inner.find()) {
                if ("groupId".equals(inner.group(1)) && g == null) g = inner.group(2).trim();
                if ("artifactId".equals(inner.group(1)) && a == null) a = inner.group(2).trim();
            }
            if (a != null) dependencies.add((g == null ? "" : g + ":") + a);
        }
        String artifact = first.get("artifactId");
        return new ColdRead.Build("maven", artifact, java, framework, dependencies);
    }

    /**
     * T4-CQ: the path prefix every route of the tree at {@code root} is served under, when its own
     * configuration declares one; empty when it declares none.
     *
     * <p>Found by the first adopted project: its routes were declared as {@code /basic/add} and
     * answered at {@code /calculator/basic/add}, and the boot gate, asking the declared path,
     * reported a working route as a 404 and raised a work item to "fix" it. The prefix is
     * configuration, not code, so it is read where this class already reads configuration, and
     * the gate asks here rather than holding a second copy of the rule.
     */
    public java.util.Optional<String> servedPrefix(Path root) {
        if (root == null) return java.util.Optional.empty();
        Path resources = root.resolve("src").resolve("main").resolve("resources");
        if (!java.nio.file.Files.isDirectory(resources)) return java.util.Optional.empty();
        Map<String, String> config = new java.util.LinkedHashMap<>();
        try (var files = java.nio.file.Files.list(resources)) {
            for (Path f : files.toList()) {
                String name = f.getFileName().toString();
                if (java.nio.file.Files.isRegularFile(f)) {
                    config.put(name, java.nio.file.Files.readString(f));
                }
            }
        } catch (java.io.IOException | java.io.UncheckedIOException e) {
            return java.util.Optional.empty();
        }
        String path = readRuntime(config).contextPath();
        if (path == null) return java.util.Optional.empty();
        path = path.strip();
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(path.startsWith("/") ? path : "/" + path);
    }

    private ColdRead.Runtime readRuntime(Map<String, String> text) {
        String contextPath = null, port = null;
        List<String> settings = new ArrayList<>();
        for (Map.Entry<String, String> e : text.entrySet()) {
            String name = e.getKey();
            if (!name.contains("application") || (!name.endsWith(".properties") && !name.endsWith(".yml"))) continue;
            for (String line : e.getValue().split("\\R")) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) continue;
                settings.add(t);
                if (t.startsWith("server.servlet.context-path")) contextPath = valueAfter(t);
                if (t.startsWith("server.port")) port = valueAfter(t);
            }
        }
        return new ColdRead.Runtime(contextPath, port, settings);
    }

    private static String valueAfter(String line) {
        int i = line.indexOf('=');
        if (i < 0) i = line.indexOf(':');
        return i < 0 ? null : line.substring(i + 1).strip();
    }

    /** Quoted, never summarised: what the project says about itself is evidence, not raw material. */
    private static boolean hasReadme(Map<String, String> text) {
        return text.keySet().stream().anyMatch(k -> k.toLowerCase().endsWith("readme.md") || k.equalsIgnoreCase("README"));
    }

    private List<String> readProse(Map<String, String> text) {
        for (Map.Entry<String, String> e : text.entrySet()) {
            if (!e.getKey().toLowerCase().endsWith("readme.md") && !e.getKey().equalsIgnoreCase("README")) continue;
            return ReadmeProse.of(e.getValue(), MAX_PROSE_LINES);   // T1-GK: its words, not its markup
        }
        return List.of();
    }

    private List<String> readTypes(Map<String, String> java) {
        List<String> out = new ArrayList<>();
        java.forEach((path, content) -> {
            if (isTestPath(path)) return;
            parse(content).ifPresent(cu -> {
                String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("(default)");
                for (TypeDeclaration<?> type : cu.getTypes()) {
                    List<String> annotations = type.getAnnotations().stream()
                            .map(AnnotationExpr::getNameAsString).map(a -> "@" + a).toList();
                    out.add(pkg + "." + type.getNameAsString()
                            + (annotations.isEmpty() ? "" : " " + String.join(" ", annotations)));
                }
            });
        });
        return out;
    }

    /** Someone else's tests are the one place an imported project states its own intent executably. */
    private List<String> readTests(Map<String, String> java) {
        List<String> out = new ArrayList<>();
        java.forEach((path, content) -> {
            if (!isTestPath(path)) return;
            parse(content).ifPresent(cu -> {
                for (TypeDeclaration<?> type : cu.getTypes()) {
                    for (MethodDeclaration method : type.getMethods()) {
                        boolean isTest = method.getAnnotations().stream()
                                .anyMatch(a -> a.getNameAsString().equals("Test")
                                        || a.getNameAsString().equals("ParameterizedTest"));
                        if (isTest) out.add(type.getNameAsString() + "." + method.getNameAsString());
                    }
                }
            });
        });
        return out;
    }

    private Optional<CompilationUnit> parse(String source) {
        try {
            return com.walpro.autobuilder.analysis.structure.JavaSources.parser().parse(source).getResult();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
