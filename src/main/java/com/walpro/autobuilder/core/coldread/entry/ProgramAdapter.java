package com.walpro.autobuilder.core.coldread.entry;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery;
import com.walpro.autobuilder.core.build.BootGate;
import com.walpro.autobuilder.core.build.BuildExecutor;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.coldread.ClaimProposer.Claim;
import com.walpro.autobuilder.core.coldread.ColdRead;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Observation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * T4-EK: a project that is STARTED -- one that has a program entry (a {@code main}). Started, it either keeps running and
 * answers requests (service-style: its code declares routes, and they are its ways in) or runs to its end
 * (command-style: its program entry is its way in, exercised with arguments and files). The boot gate's own two
 * completion models; the launch, the requests and the runs are the boot gate's.
 */
@Slf4j
@Component
public class ProgramAdapter implements EntryAdapter {

    private static final String MAIN = "src/main/java/";

    private final EndpointSurfaceQuery surface = new EndpointSurfaceQuery();
    private final BuildExecutor buildExecutor;
    private final BootGate bootGate;
    private final String packageCommand;
    private final long runTimeoutMs;

    public ProgramAdapter(BuildExecutor buildExecutor, BootGate bootGate,
                          // The command that packages a variant, the package gate's own.
                          @Value("${autobuilder.build.package-gate.command:mvn package -DskipTests}") String packageCommand,
                          // How long one command-style run may take before it is stopped and counts as not observed.
                          @Value("${autobuilder.reverse-engineering.command.run-timeout-ms:15000}") long runTimeoutMs) {
        this.buildExecutor = buildExecutor;
        this.bootGate = bootGate;
        this.packageCommand = packageCommand;
        this.runTimeoutMs = runTimeoutMs;
    }

    /** The routes when the code declares some, else the program entries; nothing without a program entry. */
    @Override
    public List<EntryPoint> find(Map<String, String> files, ColdRead coldRead) {
        List<EntryPoint> programs = programs(files);
        if (programs.isEmpty()) return List.of();
        List<EntryPoint> routes = surface.handlersByCode(files).orElse(List.of()).stream()
                .map(h -> new EntryPoint(EntryKind.SERVICE_STYLE, h.endpoint().toString(), h.type(), h.qualifiedType(), h.method()))
                .toList();
        return routes.isEmpty() ? programs : routes;
    }

    @Override
    public String exerciseForm(EntryKind kind) {
        if (kind == EntryKind.SERVICE_STYLE) {
            return """
                    SEND: <METHOD> <path> <JSON body, or nothing>
                    SEND: <further requests; the LAST one is the one the claim is about, the ones before it set things up>
                    EXPECT: <the HTTP status of the last request>
                    In a path or body, $N.field stands for a field of the answer to request N of the same claim (e.g. \
                    GET /things/$1.id after a first request created one). A setup request must succeed, or the claim is \
                    not judged.
                    """;
        }
        return """
                FILE: <a file the run reads, created beside it first -- optional, repeat for several>
                | <its content, one line per line, each starting with "| ">
                RUN: <the arguments the program is started with, space-separated; "double quotes" keep spaces inside one argument>
                EXPECT: exit <the exit status the run ends with>
                OUTPUT: <text its output must contain -- optional>
                The program is started with these arguments in a directory holding only the files above, and gets no input.
                """;
    }

    @Override
    public Ran exercise(Path dir, String variant, List<Claim> claims, Map<String, Map<String, Observation>> seen) {
        if (claims.isEmpty()) return new Ran(true, true, null);
        BuildOutcome build = buildExecutor.executeIn(dir.toString(), "reverse-engineering", packageCommand);
        if (build.failed()) {
            log.warn("T4-EK: variant {} did not build -- {}", variant, Observation.tail(build.stderrTail()));
            return new Ran(false, false, Observation.tail(build.stderrTail()));
        }
        List<Claim> served = claims.stream().filter(k -> k.kind() == EntryKind.SERVICE_STYLE).toList();
        List<Claim> runs = claims.stream().filter(k -> k.kind() == EntryKind.COMMAND_STYLE).toList();
        if (!served.isEmpty()) {
            BootGate.ServeAttempt attempt = bootGate.serveOrWhy(dir);
            if (attempt.served().isEmpty()) {
                log.warn("T4-EK: variant {} built but did not serve -- {}", variant, attempt.why());
                return new Ran(true, false, "built, but " + attempt.why());
            }
            try (BootGate.Served app = attempt.served().get()) {
                for (Claim k : served) {
                    BootGate.Served.Response r = app.send(k.sequence());
                    boolean held = r.status() != null && r.status() == k.expected();
                    seen.get(k.id()).put(variant, new Observation(variant, r.status(), r.unobserved(),
                            Observation.clip(r.body()), held));
                }
            }
        }
        if (!runs.isEmpty()) {
            Path base = dir.resolveSibling(dir.getFileName() + "-runs");
            try {
                clear(base);
                for (Claim k : runs) {
                    Path cwd = Files.createDirectories(base.resolve(k.id()));
                    for (Map.Entry<String, String> f : k.run().files().entrySet()) {
                        Path file = cwd.resolve(f.getKey()).normalize();
                        if (!file.startsWith(cwd)) continue;   // a file named outside the claim's own directory is not written
                        Files.createDirectories(file.getParent());
                        Files.writeString(file, f.getValue());
                    }
                    Optional<BootGate.Ended> ended = bootGate.runToEnd(dir, k.run().args(), cwd, runTimeoutMs);
                    if (ended.isEmpty()) return new Ran(true, false, "built, but no packaged program was found to run");
                    BootGate.Ended e = ended.get();
                    boolean held = e.exit() != null && e.exit() == k.expected() && (k.run().outputContains() == null
                            || (e.output() != null && e.output().contains(k.run().outputContains())));
                    seen.get(k.id()).put(variant, new Observation(variant, e.exit(), e.unobserved(), Observation.clip(e.output()), held));
                }
            } catch (IOException e) {
                return new Ran(true, false, e.getMessage());
            }
        }
        return new Ran(true, true, null);
    }

    /** Every type that declares {@code public static void main(String[])} under the main sources. */
    static List<EntryPoint> programs(Map<String, String> files) {
        List<EntryPoint> out = new ArrayList<>();
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
        files.forEach((path, text) -> {
            if (!path.endsWith(".java") || !path.contains(MAIN)) return;
            CompilationUnit cu = parser.parse(text).getResult().orElse(null);
            if (cu == null) return;
            for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
                for (MethodDeclaration m : t.getMethodsByName("main")) {
                    if (!m.isStatic() || !m.isPublic() || m.getParameters().size() != 1
                            || !m.getParameter(0).getType().asString().replace("...", "[]").equals("String[]")) continue;
                    String nested = nested(t);
                    String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
                    out.add(new EntryPoint(EntryKind.COMMAND_STYLE, "program " + nested, nested,
                            pkg.isEmpty() ? nested : pkg + "." + nested, "main"));
                }
            }
        });
        return out;
    }

    private static void clear(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> w = Files.walk(dir)) {
            for (Path p : w.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
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
}
