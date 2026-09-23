package com.walpro.autobuilder.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.analysis.structure.CommandSurfaceQuery;
import com.walpro.autobuilder.core.build.ExercisableCriterionQuery;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * T2-FL: which authored acceptance criteria the claim readers can read, over the whole archived
 * corpus rather than over one run.
 *
 * <p><b>Why this exists.</b> The promotion sweep's decline reasons are now on the behaviour trace,
 * and the first run carrying them produced a decline distribution that immediately invited a fix
 * per shape. That distribution is ONE run: 13 of its 29 declines were a single criterion repeated
 * by the sweep firing every cycle, and choosing which reader to write next from it is sampling
 * error dressed as a priority ([[feedback-dont-evaluate-a-build-with-older-runs]],
 * [[feedback-count-events-not-log-lines]]). The archived goal stacks hold every criterion the
 * planner has ever authored; this asks all of them at once, deterministically, in seconds
 * ([[feedback-replay-over-batches]]).
 *
 * <p><b>It calls the real reader.</b> {@link ExercisableCriterionQuery#claimOf} is invoked
 * directly rather than reimplemented here, because a second derivation of one question is free to
 * disagree with the first and the disagreement is invisible until it matters — the mistake
 * {@code addressingSurface} was extracted to undo.
 *
 * <p><b>What it does NOT answer.</b> Whether a criterion is SATISFIED, and whether an unreadable
 * criterion is a reader gap or a criterion that states nothing observable. <i>"Create
 * BookController.java"</i> has no claim in it and no reader should invent one; the report
 * clusters shapes so a reader can tell those two apart by eye, and takes no position itself.
 *
 * <p><b>It measures {@code claimOf} alone, which OVERSTATES the unreadable half.</b>
 * {@code whyNotSatisfied} tries a structural endpoint-existence branch first, so a criterion
 * of the form "a request mapping for X exists in the application sources" is decided there
 * and never reaches the reader this report exercises. Those show up here as unreadable; the
 * corpus holds roughly eight. Stated rather than corrected for, because correcting for it
 * would mean re-deriving that branch's predicate here -- a second home for one question,
 * which is the thing this class exists to avoid.
 *
 * <p>Split by target, because a target changes what a criterion population means and pooling them
 * has produced wrong numbers here twice
 * ([[feedback-no-target-domain-in-framework-strings]]).
 *
 * <p>Usage: {@code CriterionParseReport [archive-dir] [extra GoalStack.json …]} — the archive
 * directory defaults to {@code ~/.autobuilder/run-archive}.
 */
public final class CriterionParseReport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Identity for deduplication: one criterion within one target. */
    private record Key(String target, String criterion) {
    }

    /** One authored criterion, with the run and specification it was authored under. */
    private record Authored(String runId, String target, String spec, String criterion) {
    }

    public static void main(String[] args) throws IOException {
        Path archive = args.length > 0
                ? Path.of(args[0])
                : Path.of(System.getProperty("user.home"), ".autobuilder", "run-archive");

        List<Authored> corpus = new ArrayList<>();
        if (Files.isDirectory(archive)) {
            try (Stream<Path> runs = Files.list(archive)) {
                for (Path run : runs.sorted().toList()) {
                    readStack(run.resolve("GoalStack.json.gz"), run.getFileName().toString(),
                            targetOf(run), corpus);
                    readStack(run.resolve("GoalStack.json"), run.getFileName().toString(),
                            targetOf(run), corpus);
                }
            }
        }
        for (int i = 1; i < args.length; i++) {
            Path p = Path.of(args[i]);
            readStack(p, p.toString(), "(explicit)", corpus);
        }

        if (corpus.isEmpty()) {
            System.out.println("no criteria found — checked " + archive);
            return;
        }
        report(corpus);
    }

    private static String targetOf(Path run) {
        Path marker = run.resolve("target.txt");
        if (!Files.isRegularFile(marker)) return "(unstamped)";
        try {
            String text = Files.readString(marker).strip();
            int eq = text.indexOf('=');
            return eq >= 0 ? text.substring(eq + 1).strip() : text;
        } catch (IOException e) {
            return "(unreadable)";
        }
    }

    private static void readStack(Path stack, String runId, String target, List<Authored> into) {
        if (!Files.isRegularFile(stack)) return;
        try (InputStream raw = Files.newInputStream(stack);
             InputStream in = stack.getFileName().toString().endsWith(".gz")
                     ? new GZIPInputStream(raw) : raw) {
            JsonNode root = MAPPER.readTree(in);
            JsonNode tree = root.path("goalTree");
            if (tree.isMissingNode()) return;
            String spec = tree.path("description").asText("");
            collect(tree, runId, target, spec, into);
        } catch (IOException e) {
            System.err.println("skipped " + stack + ": " + e.getMessage());
        }
    }

    private static void collect(JsonNode node, String runId, String target, String spec,
                                List<Authored> into) {
        JsonNode criteria = node.path("metadata").path("acceptanceCriteria");
        List<String> texts = new ArrayList<>();
        if (criteria.isArray()) {
            for (JsonNode c : criteria) {
                String text = c.asText("").strip();
                if (!text.isEmpty()) {
                    into.add(new Authored(runId, target, spec, text));
                    texts.add(text);
                }
            }
        }
        JsonNode children = node.path("subGoals");
        boolean isLeaf = !children.isArray() || children.isEmpty();
        if (isLeaf && !texts.isEmpty() && "SUBGOAL".equals(node.path("type").asText(""))) {
            LEAVES.add(new Leaf(target, spec, List.copyOf(texts),
                    "ACTIVE".equals(node.path("status").asText(""))));
        }
        for (JsonNode child : children) {
            collect(child, runId, target, spec, into);
        }
    }

    /**
     * A leaf and the criteria it states. The unit that matters for promotion: a leaf is promoted
     * only when EVERY criterion it states is read and observed passing, so a leaf with one
     * unreadable criterion is unpromotable however readable the rest are — and a per-criterion
     * rate says nothing about how many leaves that leaves reachable.
     */
    private record Leaf(String target, String spec, List<String> criteria, boolean activeAtEnd) {
    }

    private static final List<Leaf> LEAVES = new ArrayList<>();

    private static final com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery SURFACE =
            new com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery();

    // ------------------------------------------------------------------ the report

    private static void report(List<Authored> corpus) {
        ExercisableCriterionQuery query =
                new ExercisableCriterionQuery(new CommandSurfaceQuery(), "java -jar {artifact}");

        // Deduplicated by (criterion, spec): the same criterion authored in twenty runs is one
        // shape the readers either handle or do not, and counting it twenty times would let the
        // longest-running target set the priorities.
        // Keyed by a record and not by a joined string: the obvious separator for that join is a
        // control character, and writing one into this source made the FILE binary -- `file` read
        // it as data and grep then skipped it silently, which looks exactly like "no matches".
        Map<Key, Authored> distinct = new LinkedHashMap<>();
        for (Authored a : corpus) {
            distinct.putIfAbsent(new Key(a.target(), a.criterion()), a);
        }

        Map<String, int[]> perTarget = new TreeMap<>();          // target -> {parsed, unparsed}
        Map<String, Integer> claimShapes = new LinkedHashMap<>();
        Map<String, List<String>> unparsedShapes = new LinkedHashMap<>();

        for (Map.Entry<Key, Authored> e : distinct.entrySet()) {
            Authored a = e.getValue();
            Optional<String> claim = query.claimOf(a.criterion(), a.spec());
            int[] tally = perTarget.computeIfAbsent(a.target(), k -> new int[2]);
            if (claim.isPresent()) {
                tally[0]++;
                claimShapes.merge(shapeOfClaim(claim.get()), 1, Integer::sum);
            } else {
                tally[1]++;
                unparsedShapes.computeIfAbsent(shapeOf(a.criterion()), k -> new ArrayList<>())
                        .add(a.criterion());
            }
        }

        int parsed = perTarget.values().stream().mapToInt(t -> t[0]).sum();
        int unparsed = perTarget.values().stream().mapToInt(t -> t[1]).sum();
        int total = parsed + unparsed;

        System.out.printf("CRITERION PARSE REPORT — %d authored, %d distinct per target%n",
                corpus.size(), total);
        System.out.printf("  readable   %4d  (%d%%)%n", parsed, pct(parsed, total));
        System.out.printf("  unreadable %4d  (%d%%)%n", unparsed, pct(unparsed, total));
        System.out.println("  (an OVER-count: \"a request mapping for X exists\" is decided by"
                + " a structural branch upstream of this reader)\n");

        System.out.println("BY TARGET  (a target changes what the population means — never pooled)");
        for (Map.Entry<String, int[]> t : perTarget.entrySet()) {
            int p = t.getValue()[0];
            int u = t.getValue()[1];
            System.out.printf("  %-14s %4d readable · %4d unreadable  (%d%% readable)%n",
                    t.getKey(), p, u, pct(p, p + u));
        }

        reportLeaves(query);

        System.out.println("\nREADABLE — what the claim looks like");
        claimShapes.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(c -> System.out.printf("  %4d  %s%n", c.getValue(), c.getKey()));

        System.out.println("\nUNREADABLE — clustered by shape, most common first");
        System.out.println("  (a cluster is a SPELLING, not a mechanism: decide by eye whether each"
                + " states something observable at all before writing a reader for it)");
        unparsedShapes.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, List<String>>>comparingInt(
                        e -> e.getValue().size()).reversed())
                .forEach(e -> {
                    System.out.printf("%n  %4d  %s%n", e.getValue().size(), e.getKey());
                    e.getValue().stream().distinct().limit(3)
                            .forEach(x -> System.out.println("          e.g. "
                                    + (x.length() > 120 ? x.substring(0, 117) + "..." : x)));
                });
    }

    /**
     * Per target: distinct leaves (by the set of criteria they state), how many have EVERY
     * criterion readable, and the same for the leaves still ACTIVE when their run ended — the
     * residue an observation-driven promotion exists to retire. Readable means what
     * {@code whyNotSatisfied} can decide: the structural endpoint-existence template (asked through
     * the planner's own public predicate, not a copy of it) or a {@code claimOf} answer.
     */
    private static void reportLeaves(ExercisableCriterionQuery query) {
        Map<String, Boolean> readable = new LinkedHashMap<>();
        Map<String, int[]> perTarget = new TreeMap<>();   // target -> {leaves, allReadable, residue, residueAllReadable}
        Map<String, Integer> blockers = new LinkedHashMap<>();
        java.util.Set<List<Object>> seen = new java.util.HashSet<>();
        for (Leaf leaf : LEAVES) {
            List<Object> key = List.of(leaf.target(), new java.util.TreeSet<>(leaf.criteria()), leaf.activeAtEnd());
            if (!seen.add(key)) continue;
            int unreadable = 0;
            String firstBlocker = null;
            for (String c : leaf.criteria()) {
                boolean ok = readable.computeIfAbsent(leaf.target() + "\n" + c,
                        k -> GoalPlannerService.endpointWhoseExistenceIsDemanded(c, SURFACE).isPresent()
                                || GoalPlannerService.sourceFileWhoseExistenceIsDemanded(c).isPresent()
                                || query.claimOf(c, leaf.spec()).isPresent());
                if (!ok) {
                    unreadable++;
                    if (firstBlocker == null) firstBlocker = shapeOf(c);
                }
            }
            int[] tally = perTarget.computeIfAbsent(leaf.target(), k -> new int[4]);
            tally[0]++;
            if (unreadable == 0) tally[1]++;
            if (leaf.activeAtEnd()) {
                tally[2]++;
                if (unreadable == 0) tally[3]++;
                if (unreadable == 1) blockers.merge(firstBlocker, 1, Integer::sum);
            }
        }
        System.out.println("\nLEAVES  (promotable only if EVERY criterion is readable; deduplicated by criterion set)");
        for (Map.Entry<String, int[]> t : perTarget.entrySet()) {
            int[] v = t.getValue();
            System.out.printf("  %-14s %4d leaves, %4d all-readable (%d%%) · residue %3d, %3d all-readable (%d%%)%n",
                    t.getKey(), v[0], v[1], pct(v[1], v[0]), v[2], v[3], pct(v[3], v[2]));
        }
        System.out.println("  (readable includes the file-existence shapes; a leaf decided partly by them still needs one claim observed on the running artifact)");
        System.out.println("  residue leaves ONE unreadable criterion away from all-readable, by that criterion's shape:");
        blockers.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(12)
                .forEach(b -> System.out.printf("    %4d  %s%n", b.getValue(), b.getKey()));
    }

    private static int pct(int n, int of) {
        return of == 0 ? 0 : (int) Math.round(100.0 * n / of);
    }

    /** A claim's family, so the readable half is grouped by what was read rather than by value. */
    private static String shapeOfClaim(String claim) {
        if (claim.contains("→ exit")) return "<invocation> → exit <status>";
        if (claim.matches(".*→\\s*\\d{3}.*")) return "<request> → <http status>";
        return claim;
    }

    private static final Pattern QUOTED = Pattern.compile("[\"'“”][^\"'“”]*[\"'“”]");
    private static final Pattern PATHISH = Pattern.compile("(?:/|\\b)[A-Za-z0-9_./{}-]*\\.[A-Za-z]{2,4}\\b");
    private static final Pattern ROUTE = Pattern.compile("(?i)\\b(GET|POST|PUT|PATCH|DELETE)\\s+\\S+");
    private static final Pattern NUMBER = Pattern.compile("\\b\\d+\\b");
    private static final Pattern CAMEL = Pattern.compile("\\b[A-Z][a-z]+(?:[A-Z][a-z]+)+\\b");

    /**
     * A criterion reduced to its shape, so twenty criteria naming twenty different files cluster
     * as one thing to decide about. Aggressive on purpose: the clusters are for reading, and the
     * examples printed beneath each one are what a reader actually judges.
     */
    private static String shapeOf(String criterion) {
        String s = criterion.strip();
        s = QUOTED.matcher(s).replaceAll("<quoted>");
        s = ROUTE.matcher(s).replaceAll("<request>");
        s = PATHISH.matcher(s).replaceAll("<file>");
        s = CAMEL.matcher(s).replaceAll("<Type>");
        s = NUMBER.matcher(s).replaceAll("<n>");
        s = s.replaceAll("\\s+", " ");
        String[] words = s.toLowerCase(Locale.ROOT).split(" ");
        StringBuilder head = new StringBuilder();
        for (int i = 0; i < Math.min(words.length, 7); i++) {
            head.append(i == 0 ? "" : " ").append(words[i]);
        }
        return Matcher.quoteReplacement(head.toString()) + (words.length > 7 ? " …" : "");
    }

    private CriterionParseReport() {
    }
}
