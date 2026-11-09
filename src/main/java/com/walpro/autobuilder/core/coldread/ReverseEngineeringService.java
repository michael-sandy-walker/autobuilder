package com.walpro.autobuilder.core.coldread;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.walpro.autobuilder.core.build.BootGate;
import com.walpro.autobuilder.core.build.BuildExecutor;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.coldread.ClaimProposer.Claim;
import com.walpro.autobuilder.core.merge.CapabilityCut;
import com.walpro.autobuilder.core.merge.CapabilityCut.Capability;
import com.walpro.autobuilder.core.merge.CapabilityCut.Declaration;
import com.walpro.autobuilder.core.merge.CapabilityDiscovery;
import com.walpro.autobuilder.core.merge.ProjectMerge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * T4-EK slice (a): what a project the loop did not build can be shown to do -- code, then evidence, then capabilities,
 * then claims that must be EARNED.
 *
 * <p><b>The circularity this guards against.</b> A loop that writes a project's specification and then grades the
 * project against it always scores well: both sides come from the same guess. So the model only proposes here, and the
 * running application decides. A claim survives when the application as it is answers its last request with the status
 * it states, and the application with the claim's capability taken out ({@link CapabilityStub}: its methods throw, the
 * tree still builds and starts) does not. A claim the gutted tree also bears out asserts nothing about that capability,
 * and is dropped however true it is.
 *
 * <p>Every claim is also run against every OTHER capability's gutted tree. That costs nothing extra (each variant is
 * started once and every claim is sent to it) and reports what one capability needs of another, or a claim filed
 * under the wrong one.
 *
 * <p>Nothing in the project is touched: the tree is copied, and every variant is built and started in the copy. The
 * account is written beside the copy, as JSON, for a person to compare against what the project really is.
 */
@Slf4j
@Service
public class ReverseEngineeringService {

    private static final Set<String> NOT_COPIED = Set.of("target", ".git", ".idea", "build", "out", "node_modules");

    private final ColdReadService coldReadService;
    private final com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery surface =
            new com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery();
    private final CapabilityDiscovery discovery;
    private final ClaimProposer proposer;
    private final BuildExecutor buildExecutor;
    private final BootGate bootGate;
    private final String packageCommand;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public ReverseEngineeringService(ColdReadService coldReadService, CapabilityDiscovery discovery, ClaimProposer proposer,
                                     BuildExecutor buildExecutor, BootGate bootGate,
                                     // The command that packages a variant, the package gate's own.
                                     @Value("${autobuilder.build.package-gate.command:mvn package -DskipTests}") String packageCommand) {
        this.coldReadService = coldReadService;
        this.discovery = discovery;
        this.proposer = proposer;
        this.buildExecutor = buildExecutor;
        this.bootGate = bootGate;
        this.packageCommand = packageCommand;
    }

    /**
     * One variant: the tree as it is ({@code capability} null), with one capability taken out ({@code c1}, ...), or
     * with one feature's request handlers taken out ({@code feature:<name>}).
     */
    public record Variant(String capability, List<String> stubbedMethods, boolean built, boolean served, String why) {
    }

    /** What one claim got from one variant: the status, or why nothing was observed. */
    public record Observation(String variant, Integer status, String unobserved, String body) {
    }

    /**
     * @param verdict    {@code anchored}: holds on the tree and not with its capability taken out; {@code not-held}: the
     *                   tree as it is does not bear it out; {@code not-anchored}: it also holds with its capability taken
     *                   out; {@code unjudged}: a variant it needed did not build or start, or its capability had
     *                   nothing to take out
     * @param alsoNeeds  the other capabilities whose removal also breaks it (a dependency, or the wrong attribution)
     */
    public record JudgedClaim(Claim claim, String verdict, List<String> alsoNeeds, List<Observation> observations,
                              FeatureVerdict byFeature) {
        JudgedClaim(Claim claim, String verdict, List<String> alsoNeeds, List<Observation> observations) {
            this(claim, verdict, alsoNeeds, observations, null);
        }
    }

    /**
     * The same claim judged by FEATURE, which is what decides whether it is kept.
     *
     * @param feature   the feature its last request goes to, or null when that request reaches no declared route
     * @param verdict   {@code anchored}: holds, and not with its feature's handlers taken out; {@code not-anchored}: holds
     *                  without them too (the framework answers it, not the project); {@code not-held}; {@code unowned}:
     *                  its last request reaches no declared route; {@code unjudged}: a variant it needed did not serve
     * @param dependsOn the other features whose handlers taken out also break it -- its setup goes through them
     */
    public record FeatureVerdict(String feature, String verdict, List<String> dependsOn) {
    }

    /**
     * What the project does, cut the way a person would name it: one feature per resource its served surface offers.
     *
     * @param routes    the routes it serves, as "VERB /path"
     * @param handlers  the methods that handle them -- what its variant takes out
     * @param dependsOn the features its kept claims were OBSERVED to need: their setup failed without them
     * @param madeOf    the capabilities (the layer cut) whose removal breaks its kept claims: the code it is made of
     * @param claims    its kept claims, by id
     */
    public record Feature(String name, List<String> routes, List<String> handlers, List<String> dependsOn,
                          List<String> madeOf, List<String> claims) {
    }

    public record Account(String source, String copy, ColdRead coldRead, List<Feature> features, List<Capability> capabilities,
                          List<String> unknownDeclarations, List<ClaimProposer.Proposal> proposals, List<Variant> variants,
                          List<JudgedClaim> claims, Map<String, Integer> verdicts, long durationMs) {
    }

    public Account reverseEngineer(Path source, Path workRoot) throws IOException {
        return reverseEngineer(source, workRoot, stage -> { });
    }

    /** As above, telling {@code progress} each stage as it begins, in words a person reads. */
    public Account reverseEngineer(Path source, Path workRoot, java.util.function.Consumer<String> progress) throws IOException {
        long t0 = System.currentTimeMillis();
        progress.accept("Reading the code");
        Path work = workRoot.resolve("reverse-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")));
        Path original = work.resolve("tree");
        copy(source, original);

        ColdRead coldRead = coldReadService.read(original);
        Map<String, String> files = ProjectMerge.files(original);
        Map<String, Declaration> inventory = CapabilityCut.inventory(files);
        if (inventory.isEmpty()) throw new IllegalArgumentException(source + " declares nothing under src/main/java");

        progress.accept("Finding its capabilities (the model reads the code)");
        CapabilityDiscovery.Found found = discovery.discover(original, files, null);
        List<Capability> capabilities = found.capabilities();
        log.warn("T4-EK: {} capabilities in {}", capabilities.size(), source);

        List<com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery.Handler> handlers =
                surface.handlersByCode(files).orElse(List.of());
        Map<String, List<com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery.Handler>> byFeature = featuresOf(handlers);
        log.warn("T4-EK: {} feature(s) on the served surface: {}", byFeature.size(), byFeature.keySet());

        List<ClaimProposer.Proposal> proposals = new ArrayList<>();
        List<Claim> claims = new ArrayList<>();
        for (Capability c : capabilities) {
            progress.accept("Proposing claims for " + c.name() + " (" + (proposals.size() + 1) + " of " + capabilities.size() + ")");
            ClaimProposer.Proposal p = proposer.propose(original, files, inventory, c, capabilities, coldRead, claims.size() + 1);
            proposals.add(p);
            claims.addAll(p.claims());
        }

        List<Variant> variants = new ArrayList<>();
        Map<String, Map<String, Observation>> seen = new LinkedHashMap<>();   // claim id -> variant -> observation
        claims.forEach(k -> seen.put(k.id(), new LinkedHashMap<>()));
        int total = 1 + capabilities.size() + byFeature.size(), n = 1;
        progress.accept("Checking " + claims.size() + " claims against the code as it is (1 of " + total + ")");
        variants.add(runVariant("as-is", original, List.of(), claims, seen));
        for (Capability c : capabilities) {
            progress.accept("Checking them without " + c.name() + " (" + (++n) + " of " + total + ")");
            Path dir = work.resolve("without-" + c.id());
            copy(original, dir);
            CapabilityStub.Result stub = CapabilityStub.stub(files, c.declarations(), inventory);
            for (Map.Entry<String, String> e : stub.stubbed().entrySet()) {
                Files.writeString(dir.resolve(e.getKey()), e.getValue());
            }
            variants.add(runVariant(c.id(), dir, stub.methods(), claims, seen));
        }
        for (var f : byFeature.entrySet()) {
            progress.accept("Checking them without the " + f.getKey() + " feature (" + (++n) + " of " + total + ")");
            String name = "feature:" + f.getKey();
            Path dir = work.resolve("without-feature-" + f.getKey().replaceAll("[^A-Za-z0-9_-]", "_"));
            copy(original, dir);
            List<String> ids = f.getValue().stream().map(h -> handlerId(h, inventory)).filter(java.util.Objects::nonNull).toList();
            CapabilityStub.Result stub = CapabilityStub.stub(files, ids, inventory);
            for (Map.Entry<String, String> e : stub.stubbed().entrySet()) {
                Files.writeString(dir.resolve(e.getKey()), e.getValue());
            }
            variants.add(runVariant(name, dir, stub.methods(), claims, seen));
        }

        Map<String, Variant> byName = new LinkedHashMap<>();
        variants.forEach(v -> byName.put(v.capability() == null ? "as-is" : v.capability(), v));
        List<JudgedClaim> judged = new ArrayList<>();
        Map<String, Integer> verdicts = new LinkedHashMap<>();
        String contextPath = coldRead.runtime() == null ? null : coldRead.runtime().contextPath();
        for (Claim k : claims) {
            JudgedClaim layer = judge(k, seen.get(k.id()), byName, capabilities);
            String feature = featureOf(k, byFeature.keySet(), contextPath);
            FeatureVerdict fv = judgeFeature(k, feature, seen.get(k.id()), byName, byFeature.keySet());
            JudgedClaim j = new JudgedClaim(k, layer.verdict(), layer.alsoNeeds(), layer.observations(), fv);
            judged.add(j);
            verdicts.merge(fv.verdict(), 1, Integer::sum);
        }
        List<Feature> features = summarise(byFeature, judged);
        Account account = new Account(source.toString(), work.toString(), coldRead, features, capabilities, found.unknown(),
                proposals, variants, judged, verdicts, System.currentTimeMillis() - t0);
        json.writeValue(work.resolve("account.json").toFile(), account);
        log.warn("T4-EK: account of {} written to {} -- {} ({} ms)", source, work, verdicts, account.durationMs());
        return account;
    }

    /** One claim's verdict from what every variant answered it; {@code variants} by name, {@code "as-is"} included. */
    static JudgedClaim judge(Claim k, Map<String, Observation> obs, Map<String, Variant> variants, List<Capability> capabilities) {
        Observation asIs = obs.get("as-is");
        Variant own = variants.get(k.capability());
        String verdict;
        if (!variants.get("as-is").served()) {
            verdict = "unjudged";
        } else if (!holds(asIs, k)) {
            verdict = "not-held";
        } else if (own == null || !own.served() || own.stubbedMethods().isEmpty()) {
            // A capability with nothing to take out (an entry point, kept so the tree starts) leaves the tree as it is:
            // every claim would "hold without it", which says nothing about the claim.
            verdict = "unjudged";
        } else {
            verdict = holds(obs.get(k.capability()), k) ? "not-anchored" : "anchored";
        }
        List<String> alsoNeeds = new ArrayList<>();
        if (holds(asIs, k)) {
            for (Capability other : capabilities) {
                Variant v = variants.get(other.id());
                if (other.id().equals(k.capability()) || v == null || !v.served()) continue;
                if (!holds(obs.get(other.id()), k)) alsoNeeds.add(other.id());
            }
        }
        return new JudgedClaim(k, verdict, alsoNeeds, List.copyOf(obs.values()));
    }

    /**
     * The served surface grouped by resource: the first segment of each route's path ("/" for the root). A person names
     * an application's parts this way, and so does its specification; nothing here asks the model.
     */
    static Map<String, List<com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery.Handler>> featuresOf(
            List<com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery.Handler> handlers) {
        Map<String, List<com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery.Handler>> out = new java.util.TreeMap<>();
        for (var h : handlers) out.computeIfAbsent(firstSegment(h.endpoint().path()), k -> new ArrayList<>()).add(h);
        return out;
    }

    static String firstSegment(String path) {
        if (path == null) return "/";
        String p = path.split("\\?", 2)[0];
        for (String seg : p.split("/")) {
            if (!seg.isBlank()) return seg;
        }
        return "/";
    }

    /** The feature a claim's LAST request goes to, the context path the application serves under taken off; or null. */
    static String featureOf(Claim k, Set<String> features, String contextPath) {
        String path = k.sequence().get(k.sequence().size() - 1).path();
        if (contextPath != null && !contextPath.isBlank() && !contextPath.equals("/") && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        String seg = firstSegment(path);
        return features.contains(seg) ? seg : null;
    }

    /** One claim judged by feature; {@code variants} by name, {@code "as-is"} and {@code "feature:<name>"} included. */
    static FeatureVerdict judgeFeature(Claim k, String feature, Map<String, Observation> obs, Map<String, Variant> variants,
                                       Set<String> features) {
        Observation asIs = obs.get("as-is");
        Variant own = feature == null ? null : variants.get("feature:" + feature);
        String verdict;
        if (!variants.get("as-is").served()) verdict = "unjudged";
        else if (!holds(asIs, k)) verdict = "not-held";
        else if (feature == null) verdict = "unowned";
        else if (own == null || !own.served() || own.stubbedMethods().isEmpty()) verdict = "unjudged";
        else verdict = holds(obs.get("feature:" + feature), k) ? "not-anchored" : "anchored";
        List<String> dependsOn = new ArrayList<>();
        if (holds(asIs, k)) {
            for (String other : features) {
                Variant v = variants.get("feature:" + other);
                if (other.equals(feature) || v == null || !v.served()) continue;
                if (!holds(obs.get("feature:" + other), k)) dependsOn.add(other);
            }
        }
        return new FeatureVerdict(feature, verdict, dependsOn);
    }

    static List<Feature> summarise(Map<String, List<com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery.Handler>> byFeature,
                                   List<JudgedClaim> judged) {
        List<Feature> out = new ArrayList<>();
        for (var f : byFeature.entrySet()) {
            java.util.Set<String> routes = new java.util.LinkedHashSet<>(), methods = new java.util.LinkedHashSet<>();
            f.getValue().forEach(h -> {
                routes.add(h.endpoint().toString());
                methods.add(h.type() + "." + h.method());
            });
            java.util.Set<String> dependsOn = new java.util.TreeSet<>(), madeOf = new java.util.TreeSet<>();
            List<String> kept = new ArrayList<>();
            for (JudgedClaim j : judged) {
                if (j.byFeature() == null || !f.getKey().equals(j.byFeature().feature())
                        || !j.byFeature().verdict().equals("anchored")) continue;
                kept.add(j.claim().id());
                dependsOn.addAll(j.byFeature().dependsOn());
                if (j.verdict().equals("anchored")) madeOf.add(j.claim().capability());
                madeOf.addAll(j.alsoNeeds());
            }
            out.add(new Feature(f.getKey(), List.copyOf(routes), List.copyOf(methods), List.copyOf(dependsOn),
                    List.copyOf(madeOf), kept));
        }
        return out;
    }

    /** A handler's id in the inventory: by its nested type name when that is how the inventory names it, else qualified. */
    private static String handlerId(com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery.Handler h,
                                    Map<String, Declaration> inventory) {
        String simple = h.type() + "." + h.method(), qualified = h.qualifiedType() + "." + h.method();
        if (inventory.containsKey(simple)) return simple;
        return inventory.containsKey(qualified) ? qualified : null;
    }

    /** Holds = observed, and the status the claim states. A sequence that could not be completed bears nothing out. */
    static boolean holds(Observation o, Claim k) {
        return o != null && o.status() != null && o.status() == k.expected();
    }

    private Variant runVariant(String name, Path dir, List<String> stubbed, List<Claim> claims,
                               Map<String, Map<String, Observation>> seen) {
        String capability = name.equals("as-is") ? null : name;
        BuildOutcome build = buildExecutor.executeIn(dir.toString(), "reverse-engineering", packageCommand);
        if (build.failed()) {
            log.warn("T4-EK: variant {} did not build -- {}", name, tail(build.stderrTail()));
            return new Variant(capability, stubbed, false, false, tail(build.stderrTail()));
        }
        var served = bootGate.serve(dir);
        if (served.isEmpty()) {
            log.warn("T4-EK: variant {} built but did not serve", name);
            return new Variant(capability, stubbed, true, false, "built, but did not start serving");
        }
        try (BootGate.Served app = served.get()) {
            for (Claim k : claims) {
                BootGate.Served.Response r = app.send(k.sequence());
                seen.get(k.id()).put(name, new Observation(name, r.status(), r.unobserved(), clip(r.body())));
            }
        }
        return new Variant(capability, stubbed, true, true, null);
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path rel = from.relativize(p);
                if (rel.getNameCount() > 0 && NOT_COPIED.contains(rel.getName(0).toString())) continue;
                Path target = to.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target);
            }
        }
    }

    private static String tail(String s) {
        if (s == null) return null;
        return s.length() <= 600 ? s : s.substring(s.length() - 600);
    }

    private static String clip(String s) {
        if (s == null) return null;
        return s.length() <= 300 ? s : s.substring(0, 300);
    }
}
