package com.walpro.autobuilder.core.merge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.walpro.autobuilder.core.build.ProtectedPaths;
import com.walpro.autobuilder.goal.SeedRequirements;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * T4-EC stage 1: two parent projects that grew one adopted tree in different directions, merged into ONE child --
 * code, goal tree, criteria and seed in one operation, from the parents' own snapshots and nothing made before.
 *
 * <p>A parent is a target directory as a run left it: {@code output/} (its code), {@code meta/GoalStack.json} (its
 * goal tree and criteria), {@code meta/adoption-baseline} (the text it was adopted with), {@code bootstrap/} (its
 * seed, obligations, protected paths). The child is the same layout:
 * <ul>
 *   <li><b>code</b>: the library three-way by declaration against the adopted text both share
 *       ({@link LineageCodeMerge}); the tests member by member ({@link LineageTestMerge}); any other file from the
 *       first parent, a difference reported;</li>
 *   <li><b>goal tree</b>: a new root over both parents' trees; every node gets a new id (derived from its parent's
 *       label and its own id, so a re-run gives the same tree) and keeps where it came from -- parent, run, lineage,
 *       its id there, the verdicts it had there;</li>
 *   <li><b>criteria</b>: every criterion of every node, and every obligation the parent's root was closed against,
 *       listed once per distinct text with all the places it came from -- answered later against the child;</li>
 *   <li><b>what the child was adopted with</b>: the parents' adopted text, and a record of which files are as
 *       adopted (the parent's manifest, or, before manifests existed, its adopted text and the paths no writer could
 *       touch), so "nothing existing was changed" can be asked of the child.</li>
 * </ul>
 * Refused when the parents did not start from the same adopted text.
 */
public final class ProjectMerge {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A parent: its label, the run that grew it, its target directory. */
    public record Parent(String label, String runId, Path dir) {
    }

    /** One place a criterion came from. {@code goalId} is the node's id in the child; null for a root obligation. */
    public record Origin(String parent, String goalId, String sourceGoalId, String goalDescription, int index,
                         Boolean parentVerdict, String kind) {
    }

    /** A distinct criterion text and every place it came from. */
    public record Criterion(String text, List<Origin> origins) {
        public boolean gateTests() {
            return origins.stream().allMatch(o -> "gate-tests".equals(o.kind()));
        }
    }

    /**
     * @param tree     the child's files, path relative to its project root
     * @param adopted  the adopted text of the child's files, same paths
     * @param manifest which files are as adopted (path -> sha-256), or null when it could not be told
     */
    public record Result(Map<String, String> tree, Map<String, String> adopted, Map<String, String> manifest,
                         ObjectNode goalStack, List<Criterion> criteria, List<String> provenance, List<String> conflicts,
                         Map<String, String> bootstrap, List<String> notes) {
    }

    private ProjectMerge() {
    }

    public static Result merge(Parent first, Parent second, Instant now) throws IOException {
        List<String> conflicts = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, String> treeA = files(first.dir().resolve("output"));
        Map<String, String> treeB = files(second.dir().resolve("output"));
        Map<String, String> adoptedA = files(first.dir().resolve(ProtectedPaths.BASELINE_DIR));
        Map<String, String> adoptedB = files(second.dir().resolve(ProtectedPaths.BASELINE_DIR));
        Map<String, String> adopted = new TreeMap<>(adoptedA);
        for (Map.Entry<String, String> e : adoptedB.entrySet()) {
            String a = adopted.putIfAbsent(e.getKey(), e.getValue());
            if (a != null && !a.equals(e.getValue())) {
                throw new IllegalArgumentException("the parents did not start from the same adopted text: " + e.getKey()
                        + " differs -- a merge without a common base is not this operation");
            }
        }

        // Code.
        Map<String, String> tree = new TreeMap<>();
        LineageCodeMerge.Result main = LineageCodeMerge.merge(under(adopted, "src/main/"), under(treeA, "src/main/"),
                under(treeB, "src/main/"), first.label(), second.label());
        main.files().forEach((p, t) -> tree.put("src/main/" + p, t));
        Map<String, String> javaTestsA = javaOnly(under(treeA, "src/test/")), javaTestsB = javaOnly(under(treeB, "src/test/"));
        LineageTestMerge.Result tests = LineageTestMerge.merge(javaTestsA, javaTestsB, first.label(), second.label());
        tests.files().forEach((p, t) -> tree.put("src/test/" + p, t));
        List<String> provenance = new ArrayList<>();
        for (LineageTestMerge.Origin o : main.provenance()) provenance.add("main/" + o.file() + "\t" + o.member() + "\t" + o.from());
        for (LineageTestMerge.Origin o : tests.provenance()) provenance.add(o.file() + "\t" + o.member() + "\t" + o.from());
        conflicts.addAll(main.conflicts());
        conflicts.addAll(tests.conflicts());
        Set<String> rest = new LinkedHashSet<>(treeA.keySet());
        rest.addAll(treeB.keySet());
        for (String p : rest) {
            if (tree.containsKey(p) || (p.startsWith("src/main/")) || (p.startsWith("src/test/") && p.endsWith(".java"))) continue;
            String a = treeA.get(p), b = treeB.get(p);
            tree.put(p, a != null ? a : b);
            if (a != null && b != null && !a.equals(b)) conflicts.add(p + ": differs between the parents; " + first.label() + "'s kept");
        }

        // What the child was adopted with.
        Map<String, String> manifest = new TreeMap<>();
        for (Parent p : List.of(first, second)) {
            Map<String, String> m = adoptedRecord(p, p == first ? treeA : treeB, notes);
            if (m == null) {
                manifest = null;
                break;
            }
            for (Map.Entry<String, String> e : m.entrySet()) {
                String had = manifest.putIfAbsent(e.getKey(), e.getValue());
                if (had != null && !had.equals(e.getValue())) {
                    notes.add("the parents' adopted records disagree on " + e.getKey() + " -- no record of what is as adopted");
                    manifest = null;
                    break;
                }
            }
            if (manifest == null) break;
        }

        // Seed, obligations, protected paths.
        Map<String, String> bootstrap = new LinkedHashMap<>();
        String seedA = read(first.dir().resolve("bootstrap/seed.txt")), seedB = read(second.dir().resolve("bootstrap/seed.txt"));
        String oblA = read(first.dir().resolve("bootstrap/seed.obligations.txt")), oblB = read(second.dir().resolve("bootstrap/seed.obligations.txt"));
        bootstrap.put("seed.txt", "The project merged from " + first.label() + " and " + second.label()
                + ". Everything either parent was asked to do holds in it.\n\n## " + first.label() + "\n\n" + nz(seedA).strip()
                + "\n\n## " + second.label() + "\n\n" + nz(seedB).strip() + "\n");
        bootstrap.put("seed.obligations.txt", "# Obligations of both parents, each under its parent's name.\n"
                + "# Edit freely: a hand-edited file is kept, and reported as yours.\n"
                + "# " + first.label() + "\n" + body(oblA) + "# " + second.label() + "\n" + body(oblB));
        Set<String> protectedLines = new LinkedHashSet<>();
        for (Parent p : List.of(first, second)) {
            for (String l : nz(read(p.dir().resolve("bootstrap/protected-paths.txt"))).split("\n")) {
                if (!l.isBlank() && !l.startsWith("#")) protectedLines.add(l.strip());
            }
        }
        if (!protectedLines.isEmpty()) {
            bootstrap.put("protected-paths.txt", "# The union of both parents' protected paths.\n" + String.join("\n", protectedLines) + "\n");
        }

        // Goal tree and criteria.
        String rootId = "goal-" + UUID.nameUUIDFromBytes(("merge:" + first.runId() + "+" + second.runId()).getBytes(StandardCharsets.UTF_8));
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", rootId);
        root.put("description", "The project merged from " + first.label() + " (run " + first.runId() + ") and "
                + second.label() + " (run " + second.runId() + "): everything each parent was asked to do and verified holds in"
                + " the child. Each parent's goal tree is kept below with what it was asked and what it found.");
        root.put("role", "planner");
        root.put("type", "GOAL");
        root.put("status", "ACTIVE");
        root.put("created", now.toString());
        ObjectNode rootMeta = root.putObject("metadata");
        rootMeta.put("mergeParents", first.label() + "=" + first.runId() + ", " + second.label() + "=" + second.runId());
        ArrayNode children = root.putArray("subGoals");
        Map<String, Criterion> criteria = new LinkedHashMap<>();
        for (Parent p : List.of(first, second)) children.add(parentTree(p, criteria));
        ObjectNode goalStack = MAPPER.createObjectNode();
        goalStack.put("currentGoalId", rootId);
        goalStack.set("goalTree", root);
        return new Result(tree, adopted, manifest, goalStack, List.copyOf(criteria.values()), provenance, conflicts,
                bootstrap, notes);
    }

    /**
     * T4-EC stage 2: how a project brought into another lives in the child, when the two share no base.
     * <ul>
     *   <li>{@code PACKAGES}: one project -- the brought project's sources and tests sit beside the receiving one's, in
     *       their own packages; its build dependencies are added to the receiving build;</li>
     *   <li>{@code DEPENDENCY}: the receiving project alone, its build depending on the brought project's artifact
     *       (installed from the brought project's own tree; its tests run in its own build, not the child's);</li>
     *   <li>{@code MODULES}: a multi-module build -- each project a module under its artifact id, the receiving one
     *       depending on the brought one, one reactor build running every test.</li>
     * </ul>
     */
    public enum Layout { PACKAGES, DEPENDENCY, MODULES }

    /**
     * As {@link #integrate(Parent, Parent, Layout, String, List, Instant)} in the default layout, {@link Layout#MODULES}
     * (the user's choice, 2026-10-21, after three runs of each layout closed 3 of 3): each project keeps its own build,
     * dependencies and suite, nothing two projects name alike collides, and every file stays under the project it came
     * from. PACKAGES is the option for two small libraries with compatible builds; DEPENDENCY is "use B as a library",
     * not a merge -- the child cannot judge B's criteria nor run B's tests.
     */
    public static Result integrate(Parent into, Parent brought, String goal, List<String> integration, Instant now)
            throws IOException {
        return integrate(into, brought, Layout.MODULES, goal, integration, now);
    }

    /**
     * T4-EC stage 2: {@code brought} integrated into {@code into} toward {@code goal} -- two projects with no common base.
     * The child's code is placed by {@code layout}; its goal tree is a new ACTIVE root whose description is {@code goal}
     * (byte for byte: the obligations are keyed to it), both parents' trees below it with their provenance; its
     * obligations are {@code integration} followed by both parents' non-rule obligations ("nothing either parent did
     * breaks"); its protected paths are both parents', placed with their code. What the child was adopted with is not
     * made here: the loop records it when it adopts the child. The integration itself is the loop's work.
     */
    public static Result integrate(Parent into, Parent brought, Layout layout, String goal, List<String> integration,
                                   Instant now) throws IOException {
        List<String> conflicts = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<String> provenance = new ArrayList<>();
        Map<String, String> a = files(into.dir().resolve("output")), b = files(brought.dir().resolve("output"));
        Pom pomA = Pom.parse(a.get("pom.xml")), pomB = Pom.parse(b.get("pom.xml"));
        String dirA = pomA.artifactId(), dirB = pomB.artifactId();
        Map<String, String> tree = new TreeMap<>();
        switch (layout) {
            case PACKAGES -> {
                place(tree, a, "", into.label(), provenance, conflicts, p -> true);
                place(tree, b, "", brought.label(), provenance, conflicts, p -> !p.equals("pom.xml") && !p.equals("README.md"));
                List<String> added = pomA.addDependenciesOf(pomB);
                tree.put("pom.xml", pomA.render());
                if (!added.isEmpty()) notes.add("the build gains " + brought.label() + "'s dependencies: " + String.join(", ", added));
            }
            case DEPENDENCY -> {
                place(tree, a, "", into.label(), provenance, conflicts, p -> true);
                pomA.addDependency(pomB.groupId(), pomB.artifactId(), pomB.version());
                tree.put("pom.xml", pomA.render());
                notes.add("the build depends on " + pomB.groupId() + ":" + pomB.artifactId() + ":" + pomB.version()
                        + " -- install it from " + brought.dir().resolve("output") + " before building the child");
            }
            case MODULES -> {
                place(tree, b, dirB + "/", brought.label(), provenance, conflicts, p -> true);
                place(tree, a, dirA + "/", into.label(), provenance, conflicts, p -> true);
                pomA.addDependency(pomB.groupId(), pomB.artifactId(), pomB.version());
                tree.put(dirA + "/pom.xml", pomA.render());
                tree.put("pom.xml", Pom.aggregator(pomA.groupId(), dirA + "-with-" + dirB, pomA.version(), List.of(dirB, dirA)));
            }
        }

        Map<String, String> bootstrap = new LinkedHashMap<>();
        bootstrap.put("seed.txt", goal);
        Map<String, Criterion> criteria = new LinkedHashMap<>();
        String rootId = "goal-" + UUID.nameUUIDFromBytes(("integrate:" + layout + ":" + into.runId() + "<" + brought.runId())
                .getBytes(StandardCharsets.UTF_8));
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", rootId);
        root.put("description", goal);
        root.put("role", "planner");
        root.put("type", "GOAL");
        root.put("status", "ACTIVE");
        root.put("created", now.toString());
        ObjectNode rootMeta = root.putObject("metadata");
        rootMeta.put("mergeParents", into.label() + "=" + into.runId() + " (receiving), " + brought.label() + "="
                + brought.runId() + " (brought in)");
        rootMeta.put("mergeLayout", layout.name());
        ArrayNode children = root.putArray("subGoals");
        children.add(parentTree(into, criteria));
        children.add(parentTree(brought, criteria));

        List<String> lines = new ArrayList<>(integration);
        for (Parent p : List.of(into, brought)) {
            if (layout == Layout.DEPENDENCY && p == brought) continue;   // its code is not in this tree
            lines.addAll(parentObligations(nz(read(p.dir().resolve("bootstrap/seed.txt"))),
                    read(p.dir().resolve("bootstrap/seed.obligations.txt"))));
        }
        bootstrap.put("seed.obligations.txt", com.walpro.autobuilder.goal.SeedObligationsFile.render(goal,
                new ArrayList<>(new LinkedHashSet<>(lines))));
        Set<String> guarded = new LinkedHashSet<>();
        for (Parent p : List.of(into, brought)) {
            if (layout == Layout.DEPENDENCY && p == brought) continue;
            String at = layout == Layout.MODULES ? (p == into ? dirA : dirB) + "/" : "";
            for (String l : nz(read(p.dir().resolve("bootstrap/protected-paths.txt"))).split("\n")) {
                String g = l.strip();
                if (g.isEmpty() || g.startsWith("#")) continue;
                guarded.add(g.startsWith("members:") ? "members: " + at + g.substring("members:".length()).strip() : at + g);
            }
        }
        if (!guarded.isEmpty()) {
            bootstrap.put("protected-paths.txt", "# T4-CQ: paths under output/ the loop may not write, one glob per line.\n"
                    + "# Both parents' own protected paths, placed with their code: what either parent did is kept.\n"
                    + String.join("\n", guarded) + "\n");
        }
        ObjectNode goalStack = MAPPER.createObjectNode();
        goalStack.put("currentGoalId", rootId);
        goalStack.set("goalTree", root);
        return new Result(tree, Map.of(), null, goalStack, List.copyOf(criteria.values()), provenance, conflicts, bootstrap, notes);
    }

    private static void place(Map<String, String> tree, Map<String, String> files, String at, String from,
                              List<String> provenance, List<String> conflicts, java.util.function.Predicate<String> take) {
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (!take.test(e.getKey())) continue;
            String path = at + e.getKey();
            String had = tree.putIfAbsent(path, e.getValue());
            if (had != null && !had.equals(e.getValue())) conflicts.add(path + ": both projects have it; the receiving one's kept");
            else if (had == null) provenance.add(path + "\tfile\t" + from);
        }
    }

    /** The few things a merge needs of a Maven build file, read and written through the DOM. */
    static final class Pom {
        private final org.w3c.dom.Document doc;

        private Pom(org.w3c.dom.Document doc) {
            this.doc = doc;
        }

        static Pom parse(String xml) {
            if (xml == null) throw new IllegalArgumentException("a project to integrate needs a pom.xml");
            try {
                var f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                f.setNamespaceAware(false);
                return new Pom(f.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(xml))));
            } catch (Exception e) {
                throw new IllegalArgumentException("unreadable pom.xml: " + e.getMessage(), e);
            }
        }

        String groupId() { return own("groupId"); }
        String artifactId() { return own("artifactId"); }
        String version() { return own("version"); }

        private String own(String tag) {
            for (org.w3c.dom.Node n = doc.getDocumentElement().getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof org.w3c.dom.Element el && el.getTagName().equals(tag)) return el.getTextContent().strip();
            }
            throw new IllegalArgumentException("pom.xml declares no " + tag);
        }

        private org.w3c.dom.Element dependencies() {
            for (org.w3c.dom.Node n = doc.getDocumentElement().getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof org.w3c.dom.Element el && el.getTagName().equals("dependencies")) return el;
            }
            org.w3c.dom.Element deps = doc.createElement("dependencies");
            doc.getDocumentElement().appendChild(deps);
            return deps;
        }

        private List<org.w3c.dom.Element> dependencyList() {
            List<org.w3c.dom.Element> out = new ArrayList<>();
            var nodes = dependencies().getElementsByTagName("dependency");
            for (int i = 0; i < nodes.getLength(); i++) out.add((org.w3c.dom.Element) nodes.item(i));
            return out;
        }

        private static String key(org.w3c.dom.Element dep) {
            return text(dep, "groupId") + ":" + text(dep, "artifactId");
        }

        private static String text(org.w3c.dom.Element el, String tag) {
            var n = el.getElementsByTagName(tag);
            return n.getLength() == 0 ? "" : n.item(0).getTextContent().strip();
        }

        void addDependency(String groupId, String artifactId, String version) {
            org.w3c.dom.Element dep = doc.createElement("dependency");
            for (String[] kv : new String[][]{{"groupId", groupId}, {"artifactId", artifactId}, {"version", version}}) {
                org.w3c.dom.Element el = doc.createElement(kv[0]);
                el.setTextContent(kv[1]);
                dep.appendChild(el);
            }
            dependencies().appendChild(dep);
        }

        /** The dependencies {@code other} declares and this build does not, added; their coordinates. */
        List<String> addDependenciesOf(Pom other) {
            Set<String> have = new LinkedHashSet<>();
            for (org.w3c.dom.Element d : dependencyList()) have.add(key(d));
            List<String> added = new ArrayList<>();
            for (org.w3c.dom.Element d : other.dependencyList()) {
                if (have.add(key(d))) {
                    dependencies().appendChild(doc.importNode(d, true));
                    added.add(key(d));
                }
            }
            return added;
        }

        String render() {
            try {
                var t = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
                t.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes");
                var w = new java.io.StringWriter();
                t.transform(new javax.xml.transform.dom.DOMSource(doc), new javax.xml.transform.stream.StreamResult(w));
                return w.toString().replaceAll("(?m)^[ \t]+$\\n", "") + (w.toString().endsWith("\n") ? "" : "\n");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        /** A build file that only aggregates {@code modules}, in that order. */
        static String aggregator(String groupId, String artifactId, String version, List<String> modules) {
            StringBuilder sb = new StringBuilder("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                        <modelVersion>4.0.0</modelVersion>
                    """);
            sb.append("    <groupId>").append(groupId).append("</groupId>\n")
                    .append("    <artifactId>").append(artifactId).append("</artifactId>\n")
                    .append("    <version>").append(version).append("</version>\n")
                    .append("    <packaging>pom</packaging>\n    <modules>\n");
            for (String m : modules) sb.append("        <module>").append(m).append("</module>\n");
            return sb.append("    </modules>\n</project>\n").toString();
        }
    }

    /**
     * {@code integrate <layout> <intoDir> <intoLabel> <intoRun> <broughtDir> <broughtLabel> <broughtRun> <goalFile>
     * <integrationObligationsFile> <childDir>}: writes the child as a target directory -- {@code output/},
     * {@code meta/GoalStack.json}, {@code bootstrap/} -- and {@code MERGE-PROVENANCE.tsv}, {@code MERGE-CONFLICTS.txt},
     * {@code MERGE-NOTES.txt} beside them. The child directory must not exist or be empty.
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 11 || !args[0].equals("integrate")) {
            System.err.println("usage: ProjectMerge integrate <PACKAGES|DEPENDENCY|MODULES> <intoDir> <intoLabel> <intoRun>"
                    + " <broughtDir> <broughtLabel> <broughtRun> <goalFile> <integrationObligationsFile> <childDir>");
            System.exit(2);
        }
        Path child = Path.of(args[10]);
        if (Files.isDirectory(child)) {
            try (Stream<Path> w = Files.list(child)) {
                if (w.findAny().isPresent()) throw new IllegalArgumentException(child + " is not empty");
            }
        }
        List<String> integration = Files.readAllLines(Path.of(args[9])).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
        Result r = integrate(new Parent(args[3], args[4], Path.of(args[2])), new Parent(args[6], args[7], Path.of(args[5])),
                Layout.valueOf(args[1]), Files.readString(Path.of(args[8])), integration, Instant.now());
        writeAll(child.resolve("output"), r.tree());
        writeAll(child.resolve("bootstrap"), r.bootstrap());
        Files.createDirectories(child.resolve("meta"));
        Files.writeString(child.resolve("meta/GoalStack.json"), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(r.goalStack()));
        Files.writeString(child.resolve("MERGE-PROVENANCE.tsv"), "file\tmember\tfrom\n" + String.join("\n", r.provenance()) + "\n");
        Files.writeString(child.resolve("MERGE-CONFLICTS.txt"), r.conflicts().isEmpty() ? "" : String.join("\n", r.conflicts()) + "\n");
        Files.writeString(child.resolve("MERGE-NOTES.txt"), r.notes().isEmpty() ? "" : String.join("\n", r.notes()) + "\n");
        System.out.println("integrated (" + args[1] + "): " + r.tree().size() + " files, " + r.conflicts().size()
                + " conflict(s), " + r.criteria().size() + " distinct criteria carried, "
                + r.criteria().stream().mapToInt(c -> c.origins().size()).sum() + " origins");
    }

    private static void writeAll(Path dir, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path p = dir.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue());
        }
    }

    /** {@code p}'s goal tree restamped under a new root, its criteria and non-rule obligations added to {@code criteria}. */
    private static ObjectNode parentTree(Parent p, Map<String, Criterion> criteria) throws IOException {
        JsonNode stack = MAPPER.readTree(p.dir().resolve("meta/GoalStack.json").toFile());
        ObjectNode copy = (ObjectNode) stack.path("goalTree").deepCopy();
        copy.put("type", "SUBGOAL");
        restamp(copy, p, lineageOf(p), criteria);
        String parentRootId = copy.path("id").asText();
        String seed = nz(read(p.dir().resolve("bootstrap/seed.txt")));
        String obl = read(p.dir().resolve("bootstrap/seed.obligations.txt"));
        int i = 0;
        // Every line the parent was asked, rules aside (they guide the work and never block completion) -- not
        // only the ones its closure's verb filter kept: a merge keeps what each parent was asked.
        for (String line : parentObligations(seed, obl)) {
            boolean gate = line.startsWith(SeedRequirements.GATE_MARKER);
            add(criteria, gate ? line.substring(line.indexOf(SeedRequirements.GATE_TESTS) + SeedRequirements.GATE_TESTS.length()).strip() : line,
                    new Origin(p.label(), parentRootId, copy.path("metadata").path("sourceGoalId").asText(),
                            "obligation of " + p.label(), ++i, null, gate ? "gate-tests" : "obligation"));
        }
        return copy;
    }

    /** A parent's obligations without its rules; a line its test gate answers keeps its {@code gate: tests} marker. */
    static List<String> parentObligations(String seed, String obligations) {
        List<String> rules = SeedRequirements.rules(seed, obligations);
        List<String> gated = SeedRequirements.answeredByGate(seed, obligations, SeedRequirements.GATE_TESTS);
        List<String> out = new ArrayList<>();
        for (String line : SeedRequirements.obligationsOrClauses(seed, obligations)) {
            if (rules.contains(line)) continue;
            out.add(gated.contains(line) ? SeedRequirements.GATE_MARKER + " " + SeedRequirements.GATE_TESTS + " " + line : line);
        }
        return out;
    }

    /** New id, provenance and the parent's verdicts set aside, for {@code node} and everything under it. */
    private static void restamp(ObjectNode node, Parent p, String lineage, Map<String, Criterion> criteria) {
        String sourceId = node.path("id").asText();
        String id = "goal-" + UUID.nameUUIDFromBytes((p.label() + ":" + p.runId() + ":" + sourceId).getBytes(StandardCharsets.UTF_8));
        node.put("id", id);
        ObjectNode meta = node.has("metadata") && node.get("metadata").isObject() ? (ObjectNode) node.get("metadata") : node.putObject("metadata");
        meta.put("mergedFrom", p.label());
        meta.put("sourceRunId", p.runId());
        meta.put("sourceGoalId", sourceId);
        if (lineage != null) meta.put("sourceLineageId", lineage);
        JsonNode verdicts = meta.remove("criteriaPassStatus");
        if (verdicts != null) meta.set("parentCriteriaPassStatus", verdicts);
        meta.remove("criteriaFailStreak");
        JsonNode list = meta.path("acceptanceCriteria");
        if (list.isArray()) {
            for (int i = 0; i < list.size(); i++) {
                JsonNode v = verdicts == null ? null : verdicts.get(String.valueOf(i + 1));
                Boolean parentVerdict = v == null || v.isNull() ? null : v.asBoolean();
                add(criteria, list.get(i).asText(), new Origin(p.label(), id, sourceId,
                        node.path("description").asText(), i + 1, parentVerdict, "criterion"));
            }
        }
        for (JsonNode c : node.path("subGoals")) {
            if (c.isObject()) restamp((ObjectNode) c, p, lineage, criteria);
        }
    }

    private static void add(Map<String, Criterion> criteria, String text, Origin origin) {
        String key = text.strip();
        Criterion had = criteria.get(key);
        List<Origin> origins = had == null ? new ArrayList<>() : new ArrayList<>(had.origins());
        origins.add(origin);
        criteria.put(key, new Criterion(key, List.copyOf(origins)));
    }

    /**
     * Which of a parent's files are as adopted, path -> sha-256: its manifest when it has one; before manifests
     * existed, its adopted text plus the files under the paths it protected whole (no writer could change those, so
     * they are as adopted). Null when neither is there.
     */
    private static Map<String, String> adoptedRecord(Parent p, Map<String, String> tree, List<String> notes) throws IOException {
        Path m = p.dir().resolve("meta/adoption-manifest.json");
        if (Files.isRegularFile(m)) {
            Map<String, String> out = new TreeMap<>();
            MAPPER.readTree(m.toFile()).fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
            return out;
        }
        Map<String, String> adopted = files(p.dir().resolve(ProtectedPaths.BASELINE_DIR));
        if (adopted.isEmpty()) {
            notes.add(p.label() + " has no record of what it was adopted with");
            return null;
        }
        Map<String, String> out = new TreeMap<>();
        adopted.forEach((path, text) -> out.put(path, sha256(text)));
        List<String> whole = new ArrayList<>();
        for (String l : nz(read(p.dir().resolve("bootstrap/protected-paths.txt"))).split("\n")) {
            String g = l.strip();
            if (g.isEmpty() || g.startsWith("#") || g.contains(":")) continue;   // members: protects declarations, not files
            whole.add(g.replaceAll("/\\*\\*$", "/").replaceAll("\\*\\*$", ""));
        }
        tree.forEach((path, text) -> {
            if (whole.stream().anyMatch(path::startsWith)) out.putIfAbsent(path, sha256(text));
        });
        notes.add(p.label() + ": no adoption manifest (it predates them); what is as adopted is taken from its adopted"
                + " text and the paths it protected whole (" + String.join(", ", whole) + ")");
        return out;
    }

    private static String lineageOf(Parent p) {
        try {
            return MAPPER.readTree(p.dir().resolve("meta/run-identity.json").toFile()).path("lineageId").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String body(String obligations) {
        StringBuilder sb = new StringBuilder();
        for (String l : nz(obligations).split("\n")) {
            if (l.isBlank() || l.startsWith("#")) continue;
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    private static Map<String, String> under(Map<String, String> files, String prefix) {
        Map<String, String> out = new TreeMap<>();
        files.forEach((p, t) -> {
            if (p.startsWith(prefix)) out.put(p.substring(prefix.length()), t);
        });
        return out;
    }

    private static Map<String, String> javaOnly(Map<String, String> files) {
        Map<String, String> out = new TreeMap<>();
        files.forEach((p, t) -> {
            if (p.endsWith(".java")) out.put(p, t);
        });
        return out;
    }

    /** Every regular file under {@code root} except build output, path relative to it. */
    static Map<String, String> files(Path root) throws IOException {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> w = Files.walk(root)) {
            for (Path f : w.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                if (rel.startsWith("target/") || rel.contains("/target/") || rel.startsWith(".git/")) continue;
                try {
                    out.put(rel, Files.readString(f));
                } catch (java.nio.charset.MalformedInputException binary) {
                    // not text: no declaration, criterion or seed lives in it
                }
            }
        }
        return out;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(Path p) throws IOException {
        return Files.isRegularFile(p) ? Files.readString(p) : null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
