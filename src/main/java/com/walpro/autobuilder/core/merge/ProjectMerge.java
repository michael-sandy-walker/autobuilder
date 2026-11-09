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
 * T4-EC: what the bringing of a project shares -- reading a project directory, placing files, its build file, its goal
 * tree restamped with where it came from. A project is a target directory as a run left it: {@code output/},
 * {@code meta/GoalStack.json}, {@code bootstrap/}. Every node of its tree gets a new id (derived from its label and its
 * own id, so a re-run gives the same tree) and keeps where it came from; every criterion and obligation is listed once
 * per distinct text with all the places it came from.
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
    private ProjectMerge() {
    }

    static void place(Map<String, String> tree, Map<String, String> files, String at, String from,
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

    /** {@code p}'s goal tree restamped under a new root, its criteria and non-rule obligations added to {@code criteria}. */
    static ObjectNode parentTree(Parent p, Map<String, Criterion> criteria) throws IOException {
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

    static String lineageOf(Parent p) {
        try {
            return MAPPER.readTree(p.dir().resolve("meta/run-identity.json").toFile()).path("lineageId").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    static Map<String, String> under(Map<String, String> files, String prefix) {
        Map<String, String> out = new TreeMap<>();
        files.forEach((p, t) -> {
            if (p.startsWith(prefix)) out.put(p.substring(prefix.length()), t);
        });
        return out;
    }

    static Map<String, String> javaOnly(Map<String, String> files) {
        Map<String, String> out = new TreeMap<>();
        files.forEach((p, t) -> {
            if (p.endsWith(".java")) out.put(p, t);
        });
        return out;
    }

    /** Every regular file under {@code root} except build output, path relative to it. */
    public static Map<String, String> files(Path root) throws IOException {
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

    static String read(Path p) throws IOException {
        return Files.isRegularFile(p) ? Files.readString(p) : null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
