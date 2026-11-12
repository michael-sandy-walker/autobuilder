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
                .map(h -> new EntryPoint(EntryKind.SERVICE_STYLE, h.asDeclared(), h.type(), h.qualifiedType(), h.method()))
                .toList();
        return routes.isEmpty() ? programs : routes;
    }

    @Override
    public String exerciseForm(EntryKind kind) {
        if (kind == EntryKind.SERVICE_STYLE) {
            return """
                    SEND: <METHOD> <path> <body, or nothing>
                    SEND: <further requests; the LAST one is the one the claim is about, the ones before it set things up>
                    EXPECT: <the HTTP status of the last request>
                    In a path or body, $N.field stands for a field of the answer to request N of the same claim (e.g. \
                    GET /things/$1.id after a first request created one). A setup request must succeed, or the claim is \
                    not judged.
                    A body is JSON, or a form's fields written name=value&name=value (values as typed; they are encoded \
                    when sent). Each claim is one new client: the cookies an answer sets are sent with the later requests \
                    of the same claim and with no other claim, so what needs a session obtains it in its own first \
                    requests. A request that a page's form makes carries that form's hidden fields when the page was \
                    fetched earlier in the same claim -- fetch the page, then send the form. A redirect is not followed: \
                    its own status is the answer.
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
                // T4-EO: this start is asked once for the pages it shows a client with no cookies, so a claim that sends
                // a form without having fetched its page is given the page first. The ways in are this tree's own.
                for (String path : askable(routes(dir))) app.ask(path);
                for (Claim k : readersFirst(served)) {
                    BootGate.Served.Response r = app.send(k.sequence());
                    boolean held = r.status() != null && r.status() == k.expected();
                    seen.get(k.id()).put(variant, new Observation(variant, r.status(), r.unobserved(),
                            Observation.clip(r.shown()), held, r.sent()));
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
                    seen.get(k.id()).put(variant, new Observation(variant, e.exit(), e.unobserved(), Observation.clip(e.output()), held, null,
                            Observation.clip(e.errorOutput())));
                }
            } catch (IOException e) {
                return new Ran(true, false, e.getMessage());
            }
        }
        return new Ran(true, true, null);
    }

    /**
     * T4-EO: the order one start exercises its claims in -- the fewer requests of a claim can change anything (those
     * that are not a GET), the earlier it runs; claims alike in that keep their order. One start serves every claim, so
     * what a claim changes is what the later ones find: field runs 3 and 4 lost 17 and 9 claims that only read, because
     * an earlier claim had changed the user they signed in as. The same order on every variant.
     */
    static List<Claim> readersFirst(List<Claim> claims) {
        List<Claim> ordered = new ArrayList<>(claims);
        ordered.sort(Comparator.comparing(Claim::sequence, com.walpro.autobuilder.core.build.CriterionExercises.EXERCISE_ORDER));
        return ordered;
    }

    /** How many ways in are asked before any use; a project with more is asked for its first ones only. */
    static final int MAX_ASKED = 80;

    /**
     * T4-EO: the tree built and started once, and every way in that can be asked as written (a GET with no placeholder)
     * asked by a client with no cookies. Reported: the ways in that send such a client elsewhere, and the pages that
     * carry forms. A project where neither occurs -- one whose answers do not depend on who asks -- reports nothing,
     * and its claims are proposed as they always were.
     */
    @Override
    public String observedBeforeAnyUse(Path dir, List<EntryPoint> points) {
        List<String> paths = askable(points);
        if (paths.isEmpty()) return null;
        BuildOutcome build = buildExecutor.executeIn(dir.toString(), "reverse-engineering", packageCommand);
        if (build.failed()) return null;   // the variants say so themselves
        BootGate.ServeAttempt attempt = bootGate.serveOrWhy(dir);
        if (attempt.served().isEmpty()) return null;
        List<BootGate.Served.Asked> asked = new ArrayList<>();
        try (BootGate.Served app = attempt.served().get()) {
            for (String path : paths) asked.add(app.ask(path));
        }
        return observed(asked);
    }

    /** The paths of the ways in a client can ask as written: a GET whose path has no placeholder. */
    static List<String> askable(List<EntryPoint> points) {
        return points.stream().filter(e -> e.kind() == EntryKind.SERVICE_STYLE).map(EntryPoint::label)
                .filter(l -> l.startsWith("GET ") && l.indexOf('{') < 0).map(l -> l.substring(4).strip())
                .filter(p -> p.startsWith("/")).distinct().limit(MAX_ASKED).toList();
    }

    /** What was observed, in words; null when no way in redirected and no page carried a form. */
    static String observed(List<BootGate.Served.Asked> asked) {
        List<String> lines = new ArrayList<>();
        int plain = 0;
        for (BootGate.Served.Asked a : asked) {
            if (a.status() == null) continue;
            if (a.location() != null) {
                lines.add("  GET " + a.path() + " -> " + a.status() + ", redirects to " + a.location());
            } else if (!a.forms().isEmpty()) {
                StringBuilder sb = new StringBuilder("  GET " + a.path() + " -> " + a.status() + ", a page with ");
                List<String> forms = new ArrayList<>();
                for (var f : a.forms()) {
                    // Only the fields someone fills in. Naming what the page carries by itself made the model try to
                    // carry it too (field run 2: "&_csrf=$1._csrf" in 41 requests) -- the client does that.
                    forms.add("a form that posts to " + f.target() + " (fields: "
                            + (f.fields().isEmpty() ? "none" : String.join(", ", f.fields())) + ")");
                }
                lines.add(sb.append(String.join(" and ", forms)).toString());
            } else {
                plain++;
            }
        }
        if (lines.isEmpty()) return null;
        return "WHAT THE RUNNING PROJECT ANSWERED JUST NOW to a client with no cookies (observed on the project as it is, "
                + "not read from its code -- where the two differ, this is what it does):\n" + String.join("\n", lines) + "\n"
                + (plain == 0 ? "" : "  (" + plain + " other way(s) in answered such a client themselves, with no form)\n")
                + "Only GET ways in without a {placeholder} were asked. Where a way in sends a client with no cookies "
                + "elsewhere, a claim about what it does for someone who has been let in must first do, in its own earlier "
                + "requests, what the page it is sent to asks for; a claim about a client with no cookies states the answer "
                + "above.\n";
    }

    private List<EntryPoint> routes(Path dir) {
        try {
            return find(com.walpro.autobuilder.core.merge.ProjectMerge.files(dir), null);
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * T4-EN: the tree built and started once, to see whether it starts at all: null when it served, else why not -- in
     * its own words when it said why.
     */
    public String startFailure(Path dir) {
        BuildOutcome build = buildExecutor.executeIn(dir.toString(), "reverse-engineering", packageCommand);
        if (build.failed()) return null;   // not a question of services: the variants say so themselves
        BootGate.ServeAttempt attempt = bootGate.serveOrWhy(dir);
        if (attempt.served().isEmpty()) return attempt.why();
        attempt.served().get().close();
        return null;
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
