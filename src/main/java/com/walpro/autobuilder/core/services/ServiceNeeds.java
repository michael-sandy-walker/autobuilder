package com.walpro.autobuilder.core.services;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.LlmCallContext;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * T4-EN: what services a project that did not start needs, and what to start for each -- in order of authority:
 * <ol>
 *   <li>its own compose file: the services it names with an image (not the ones it builds -- that is the project), taken
 *       as written;</li>
 *   <li>else its configuration, README and build file, and what it said when it did not start: the model writes a
 *       proposal from those lines ONLY, quoted back to the operator beside it (no table of known services here: the
 *       project's own lines say what it needs, the operator checks the reading).</li>
 * </ol>
 * The need itself is OBSERVED, never read: an address on this machine in the project's configuration that does not
 * answer, AND a start that failed. The port is always the configured one -- the project is not changed to reach it.
 */
@Slf4j
@Component
public class ServiceNeeds {

    /** An address on this machine the project's configuration names, with the line it was read from. */
    public record Address(String file, int line, String text, int port, String scheme) {
        String where() { return file + ":" + line; }
    }

    static final Pattern LOCAL = Pattern.compile("(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0)(?::|\\s*,\\s*port\\s*[=:]\\s*)(\\d{2,5})\\b");
    static final Pattern SCHEME = Pattern.compile("([A-Za-z][A-Za-z0-9+.:-]*?)://");
    private static final Set<String> CONFIG_EXT = Set.of(".properties", ".yml", ".yaml", ".env", ".conf", ".toml", ".ini", ".xml");
    private static final Set<String> SKIP_DIRS = Set.of("target", "build", ".git", "node_modules", "out", ".idea", "test");
    private static final List<String> COMPOSE = List.of("docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml");
    private static final int MAX_LINE = 200;

    private final UnaryOperator<String> model;
    private final IntPredicate answers;

    @Autowired
    public ServiceNeeds(LLMClient llmClient) {
        this(prompt -> ask(llmClient, prompt), ServiceContainers::answers);
    }

    /** The model and the reachability probe given directly -- for callers that bring their own. */
    public ServiceNeeds(UnaryOperator<String> model, IntPredicate answers) {
        this.model = model;
        this.answers = answers;
    }

    /** The addresses on this machine the project's configuration names that nothing answers at now. */
    public List<Address> unanswered(Path tree) {
        return addresses(tree).stream().filter(a -> !answers.test(a.port())).toList();
    }

    /** Every address on this machine in the project's configuration files (comment lines are not read). */
    static List<Address> addresses(Path tree) {
        List<Address> out = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (Path f : files(tree, p -> CONFIG_EXT.stream().anyMatch(e -> p.getFileName().toString().endsWith(e))
                && !p.getFileName().toString().equals("pom.xml"))) {
            List<String> lines = read(f);
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i).strip();
                if (comment(l)) continue;
                Matcher m = LOCAL.matcher(l);
                if (!m.find()) continue;
                int port = Integer.parseInt(m.group(1));
                if (port > 65535 || !seen.add(port)) continue;
                Matcher s = SCHEME.matcher(l);
                String scheme = null;
                if (s.find()) {
                    String[] parts = s.group(1).split(":");
                    scheme = parts[parts.length - 1].toLowerCase(Locale.ROOT);
                }
                out.add(new Address(rel(tree, f), i + 1, clip(l), port, scheme));
            }
        }
        return out;
    }

    /**
     * What to start for the addresses that did not answer, after a start that failed with {@code failure}: the compose
     * file's services when the project has one, else one proposal per address written by the model from the evidence.
     */
    public List<ServiceProposal> propose(Path tree, List<Address> missing, String failure) {
        List<ServiceProposal> composed = fromCompose(tree, missing, failure);
        if (!composed.isEmpty()) return composed;
        List<ServiceProposal> out = new ArrayList<>();
        for (Address a : missing) {
            List<String> evidence = evidence(tree, a);
            String answer = model.apply(prompt(a, evidence, failure));
            ServiceProposal p = parse(answer, a, evidence, scripts(tree), failure);
            if (p != null) out.add(p);
            else log.warn("T4-EN: no proposal for {} -- the model's answer did not have the form: {}", a.where(), clip(String.valueOf(answer)));
        }
        return out;
    }

    /**
     * A proposal the operator said yes to whose container could not be started, read again by the model with what the
     * container itself said -- the image's own words are the authority on what it needs. A compose file's proposal is
     * not rewritten (it is the project's own answer); neither is one the model cannot answer in the form.
     */
    public ServiceProposal revise(ServiceProposal p, String containerSaid, String why) {
        ServiceProposal failed = new ServiceProposal(p.name(), p.image(), p.hostPort(), p.containerPort(), p.env(), p.initDir(),
                p.initScripts(), p.initScript(), p.source(), p.evidence(), why);
        if (!"model".equals(p.source()) || containerSaid == null) return failed;
        Address a = new Address(null, 0, null, p.hostPort(), p.name());
        StringBuilder tried = new StringBuilder("IMAGE: " + p.image() + "\nCONTAINER_PORT: " + p.containerPort() + "\n");
        p.env().forEach((k, v) -> tried.append("ENV: ").append(k).append('=').append(v).append('\n'));
        String prompt = prompt(a, p.evidence(), p.failure()) + "\nThis was tried and its container did not come up:\n"
                + tried + "What the container printed:\n" + containerSaid
                + "\n\nAnswer again, in the same form, so that it comes up and the project's lines still work unchanged.";
        ServiceProposal revised = parse(model.apply(prompt), a, p.evidence(), p.initScripts(), why);
        if (revised == null) return failed;
        return new ServiceProposal(p.name(), revised.image(), p.hostPort(), revised.containerPort(), revised.env(),
                revised.initDir(), revised.initDir() == null ? List.of() : p.initScripts(),
                revised.initDir() == null ? null : p.initScript(), "model", p.evidence(), why);
    }

    // ---- the project's own compose file ----

    @SuppressWarnings("unchecked")
    static List<ServiceProposal> fromCompose(Path tree, List<Address> missing, String failure) {
        for (String name : COMPOSE) {
            Path f = tree.resolve(name);
            if (!Files.isRegularFile(f)) continue;
            Object doc;
            try {
                doc = new Yaml().load(Files.readString(f));
            } catch (Exception e) {
                log.warn("T4-EN: {} could not be read -- {}", f, e.getMessage());
                return List.of();
            }
            if (!(doc instanceof Map<?, ?> root) || !(root.get("services") instanceof Map<?, ?> services)) return List.of();
            Set<Integer> wanted = new LinkedHashSet<>();
            missing.forEach(a -> wanted.add(a.port()));
            List<ServiceProposal> all = new ArrayList<>();
            for (Map.Entry<?, ?> e : services.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> svc) || svc.containsKey("build") || !(svc.get("image") instanceof String image)) continue;
                int[] ports = ports(svc.get("ports"));
                if (ports == null) continue;
                Map<String, String> env = new LinkedHashMap<>();
                Object environment = svc.get("environment");
                if (environment instanceof Map<?, ?> m) m.forEach((k, v) -> env.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
                else if (environment instanceof List<?> l) for (Object o : l) {
                    String s = String.valueOf(o);
                    int eq = s.indexOf('=');
                    if (eq > 0) env.put(s.substring(0, eq), s.substring(eq + 1));
                }
                String initDir = null, initScript = null;
                if (svc.get("volumes") instanceof List<?> vols) for (Object v : vols) {
                    String[] parts = String.valueOf(v).split(":");
                    if (parts.length < 2 || !parts[0].startsWith(".")) continue;
                    Path local = tree.resolve(parts[0]).normalize();
                    if (!local.startsWith(tree) || !Files.isRegularFile(local)) continue;
                    String inside = parts[1];
                    int slash = inside.lastIndexOf('/');
                    if (slash <= 0) continue;
                    initDir = inside.substring(0, slash);
                    initScript = rel(tree, local);
                }
                all.add(new ServiceProposal(String.valueOf(e.getKey()), image, ports[0], ports[1], env, initDir,
                        initScript == null ? List.of() : List.of(initScript), initScript, "compose",
                        List.of(name + ": service '" + e.getKey() + "', image " + image + ", ports " + ports[0] + ":" + ports[1]),
                        failure));
            }
            List<ServiceProposal> matching = all.stream().filter(p -> wanted.contains(p.hostPort())).toList();
            return matching.isEmpty() ? all : matching;
        }
        return List.of();
    }

    /** {host, container} from a compose service's first port mapping; null when it publishes none. */
    static int[] ports(Object ports) {
        if (!(ports instanceof List<?> list) || list.isEmpty()) return null;
        Object first = list.get(0);
        try {
            if (first instanceof Map<?, ?> m) {
                int target = Integer.parseInt(String.valueOf(m.get("target")));
                Object published = m.get("published");
                return new int[]{published == null ? target : Integer.parseInt(String.valueOf(published)), target};
            }
            String[] parts = String.valueOf(first).replaceAll("/(tcp|udp)$", "").split(":");
            int container = Integer.parseInt(parts[parts.length - 1]);
            int host = parts.length >= 2 ? Integer.parseInt(parts[parts.length - 2]) : container;
            return new int[]{host, container};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- the evidence, and the model's reading of it ----

    /**
     * The lines a proposal rests on, each with where it was read: the address line and the lines beside it that share
     * its key's prefix (or, in a nested file, the lines around it); the README's and the build file's lines that name
     * the address's scheme.
     */
    static List<String> evidence(Path tree, Address a) {
        List<String> out = new ArrayList<>();
        Path config = tree.resolve(a.file());
        List<String> lines = read(config);
        String key = a.text().split("[=:]", 2)[0].strip();
        int dot = key.lastIndexOf('.');
        String prefix = dot > 0 && a.file().endsWith(".properties") ? key.substring(0, dot + 1) : null;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).strip();
            if (l.isEmpty() || comment(l)) continue;
            boolean near = prefix != null ? l.startsWith(prefix) : Math.abs(i + 1 - a.line()) <= 4;
            if (near || i + 1 == a.line()) out.add(a.file() + ":" + (i + 1) + ": " + clip(l));
        }
        if (a.scheme() != null) {
            String word = a.scheme().toLowerCase(Locale.ROOT);
            for (Path f : files(tree, p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return (n.startsWith("readme") || n.equals("pom.xml") || n.startsWith("build.gradle"))
                        && tree.relativize(p).getNameCount() == 1;
            })) {
                List<String> fl = read(f);
                int n = 0;
                for (int i = 0; i < fl.size() && n < 8; i++) {
                    String l = fl.get(i).strip();
                    if (l.toLowerCase(Locale.ROOT).contains(word) && !l.contains("<img") && !l.contains("](http")) {
                        out.add(rel(tree, f) + ":" + (i + 1) + ": " + clip(l));
                        n++;
                    }
                }
            }
        }
        return out;
    }

    /** The scripts the project ships that could fill a database: its {@code .sql} files outside tests and build output. */
    static List<String> scripts(Path tree) {
        return files(tree, p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql")).stream()
                .map(p -> rel(tree, p)).toList();
    }

    static String prompt(Address a, List<String> evidence, String failure) {
        return "A software project does not start: it reaches a service at localhost:" + a.port()
                + " and nothing answers there. A container will be started for it on this machine, at that port.\n\n"
                + "What the project says about it (file:line: text):\n" + String.join("\n", evidence) + "\n\n"
                + "What the project printed when it did not start:\n" + (failure == null ? "(nothing)" : failure) + "\n\n"
                + "Which container image runs this service, and how must it be set up so that THIS project's lines above "
                + "work against it unchanged (its user, its password -- an empty one included -- and its database or other "
                + "names)? Use only what the lines above say; where they name a version, use an image of that version.\n"
                + "Answer in exactly this form, nothing else:\n"
                + "IMAGE: <image:tag>\n"
                + "CONTAINER_PORT: <the port the image listens on inside the container>\n"
                + "ENV: <NAME=value> (one line per variable the image needs; none when it needs none)\n"
                + "INIT_DIR: <the directory inside the container whose scripts the image runs on its first start, or none>\n";
    }

    static ServiceProposal parse(String answer, Address a, List<String> evidence, List<String> scripts, String failure) {
        if (answer == null) return null;
        String image = null, initDir = null;
        Integer port = null;
        Map<String, String> env = new LinkedHashMap<>();
        for (String raw : answer.lines().toList()) {
            String l = raw.strip().replaceAll("^[-*`]+\\s*", "").replace("`", "");
            int colon = l.indexOf(':');
            if (colon <= 0) continue;
            String field = l.substring(0, colon).strip().toUpperCase(Locale.ROOT);
            String value = l.substring(colon + 1).strip();
            switch (field) {
                case "IMAGE" -> image = value.split("\\s+")[0];
                case "CONTAINER_PORT" -> {
                    Matcher m = Pattern.compile("\\d{2,5}").matcher(value);
                    if (m.find()) port = Integer.parseInt(m.group());
                }
                case "ENV" -> {
                    int eq = value.indexOf('=');
                    if (eq > 0) env.put(value.substring(0, eq).strip(), value.substring(eq + 1).strip());
                }
                case "INIT_DIR" -> initDir = value.equalsIgnoreCase("none") || !value.startsWith("/") ? null : value.split("\\s+")[0];
                default -> { }
            }
        }
        if (image == null || port == null) return null;
        ServiceProposal p = new ServiceProposal(a.scheme() == null ? "service-" + a.port() : a.scheme(), image, a.port(), port,
                env, initDir, initDir == null ? List.of() : scripts, null, "model", evidence, failure);
        return p.invalid() == null ? p : null;
    }

    // ---- files ----

    private static List<Path> files(Path tree, java.util.function.Predicate<Path> wanted) {
        if (!Files.isDirectory(tree)) return List.of();
        try (Stream<Path> walk = Files.walk(tree, 6)) {
            return walk.filter(Files::isRegularFile).filter(p -> {
                Path r = tree.relativize(p);
                for (int i = 0; i < r.getNameCount() - 1; i++) if (SKIP_DIRS.contains(r.getName(i).toString())) return false;
                return true;
            }).filter(wanted).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<String> read(Path f) {
        try {
            return Files.readAllLines(f);
        } catch (IOException | java.io.UncheckedIOException e) {
            try {
                return List.of(new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.ISO_8859_1).split("\n"));
            } catch (IOException ex) {
                return List.of();
            }
        }
    }

    private static boolean comment(String l) {
        return l.startsWith("#") || l.startsWith("//") || l.startsWith("<!--") || l.startsWith(";");
    }

    private static String rel(Path tree, Path f) {
        return tree.relativize(f).toString().replace('\\', '/');
    }

    private static String clip(String s) {
        return s.length() <= MAX_LINE ? s : s.substring(0, MAX_LINE) + "...";
    }

    private static String ask(LLMClient client, String prompt) {
        try (var scope = LlmCallContext.scope("service-needs", "services")) {
            ResponseEntity<ChatResponse> r = client.ask(List.of(
                    new Message("system", "You say which container to start for a service a software project needs, "
                            + "reading only the project's own lines that are shown."),
                    new Message("user", prompt)));
            return r == null || r.getBody() == null ? null : r.getBody().getFirstContent();
        } catch (RuntimeException e) {
            log.warn("T4-EN: the model could not be asked -- {}", e.getMessage());
            return null;
        }
    }
}
