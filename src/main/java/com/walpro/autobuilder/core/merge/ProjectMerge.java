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
        for (Parent p : List.of(first, second)) {
            JsonNode stack = MAPPER.readTree(p.dir().resolve("meta/GoalStack.json").toFile());
            String lineage = lineageOf(p);
            ObjectNode copy = (ObjectNode) stack.path("goalTree").deepCopy();
            copy.put("type", "SUBGOAL");
            restamp(copy, p, lineage, criteria);
            children.add(copy);
            String parentRootId = copy.path("id").asText();
            String seed = p == first ? seedA : seedB, obl = p == first ? oblA : oblB;
            int i = 0;
            // Every line the parent was asked, rules aside (they guide the work and never block completion) -- not
            // only the ones its closure's verb filter kept: a merge keeps what each parent was asked.
            List<String> rules = SeedRequirements.rules(nz(seed), obl);
            List<String> gated = SeedRequirements.answeredByGate(nz(seed), obl, SeedRequirements.GATE_TESTS);
            for (String line : SeedRequirements.obligationsOrClauses(nz(seed), obl)) {
                if (rules.contains(line)) continue;
                add(criteria, line, new Origin(p.label(), parentRootId, copy.path("metadata").path("sourceGoalId").asText(),
                        "obligation of " + p.label(), ++i, null, gated.contains(line) ? "gate-tests" : "obligation"));
            }
        }
        ObjectNode goalStack = MAPPER.createObjectNode();
        goalStack.put("currentGoalId", rootId);
        goalStack.set("goalTree", root);
        return new Result(tree, adopted, manifest, goalStack, List.copyOf(criteria.values()), provenance, conflicts,
                bootstrap, notes);
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
