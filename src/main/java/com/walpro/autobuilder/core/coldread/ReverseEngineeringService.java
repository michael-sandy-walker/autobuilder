package com.walpro.autobuilder.core.coldread;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.walpro.autobuilder.core.coldread.ClaimProposer.Claim;
import com.walpro.autobuilder.core.coldread.entry.EntryAdapter;
import com.walpro.autobuilder.core.coldread.entry.EntryKind;
import com.walpro.autobuilder.core.coldread.entry.EntryPoint;
import com.walpro.autobuilder.core.coldread.entry.LibraryStyleAdapter;
import com.walpro.autobuilder.core.coldread.entry.ProgramAdapter;
import com.walpro.autobuilder.core.merge.CapabilityCut;
import com.walpro.autobuilder.core.merge.CapabilityCut.Capability;
import com.walpro.autobuilder.core.merge.CapabilityCut.Declaration;
import com.walpro.autobuilder.core.merge.CapabilityDiscovery;
import com.walpro.autobuilder.core.merge.ProjectMerge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * T4-EK: what a project the loop did not build can be shown to do -- code, then evidence, then capabilities, then claims
 * that must be EARNED. One pipeline for every kind of project; how a project is exercised is an adapter's business: it
 * is STARTED when it has a program entry ({@link ProgramAdapter}: service-style when its code declares routes,
 * command-style otherwise), and CALLED when it has none ({@link LibraryStyleAdapter}, library-style).
 *
 * <p><b>The circularity this guards against.</b> A loop that writes a project's specification and then grades the
 * project against it always scores well: both sides come from the same guess. So the model only proposes here, and the
 * project decides. A claim is kept when the project as it is bears it out and the project with one of its FEATURES taken
 * out ({@link CapabilityStub}: the feature's ways in throw, the tree still builds) does not. A claim no gutted tree
 * refutes asserts nothing about the project, and is dropped however true it is.
 *
 * <p><b>Features</b> are the types that declare the ways in -- one rule for every kind (a route's controller, a program's
 * main class, a library's public types). <b>Which feature a claim belongs to is OBSERVED, never read from the claim:</b>
 * every claim is exercised on every feature's gutted tree; the features whose removal breaks it are its broken-by set;
 * its own feature is the most specific of them (the one whose removal breaks the fewest claims overall), and the others
 * are what it depends on. Every claim is also exercised on every capability's gutted tree (the layer cut the model
 * proposes): the code a feature is made of.
 *
 * <p>Nothing in the project is touched: the tree is copied, and every variant is built and exercised in the copy. The
 * account is written beside the copy, as JSON.
 */
@Slf4j
@Service
public class ReverseEngineeringService {

    private static final Set<String> NOT_COPIED = Set.of("target", ".git", ".idea", "build", "out", "node_modules");

    private final ColdReadService coldReadService;
    private final CapabilityDiscovery discovery;
    private final ClaimProposer proposer;
    private final EntryAdapter started;
    private final EntryAdapter called;
    private final ProgramAdapter program;

    /** T4-EN: what services a project that does not start needs, and what the operator said about them. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @lombok.Setter
    private com.walpro.autobuilder.core.services.ServiceNeeds serviceNeeds;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @lombok.Setter
    private com.walpro.autobuilder.core.services.ProjectServices projectServices;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @lombok.Setter
    private com.walpro.autobuilder.core.services.ServiceContainers serviceContainers;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public ReverseEngineeringService(ColdReadService coldReadService, CapabilityDiscovery discovery, ClaimProposer proposer,
                                     ProgramAdapter started, LibraryStyleAdapter called) {
        this.coldReadService = coldReadService;
        this.discovery = discovery;
        this.proposer = proposer;
        this.started = started;
        this.called = called;
        this.program = started;
    }

    /**
     * One variant: the tree as it is ({@code capability} null), with one capability taken out ({@code c1}, ...), or
     * with one way in taken out ({@code entry:<Type.method>}).
     *
     * @param ran whether its claims could be exercised at all -- a tree that did not build or start says nothing
     */
    public record Variant(String capability, List<String> stubbedMethods, boolean built, boolean ran, String why) {
    }

    /**
     * What one claim got from one variant.
     *
     * @param status     the kind's own answer: an HTTP status, an exit status, 0 for a test that passed
     * @param unobserved why there is no answer, when there is none
     * @param held       whether the claim held there, as its kind judges it
     */
    public record Observation(String variant, Integer status, String unobserved, String body, boolean held,
                              List<com.walpro.autobuilder.core.build.CriterionProbeSynthesizer.ProbeRequest> sent) {

        public Observation(String variant, Integer status, String unobserved, String body, boolean held) {
            this(variant, status, unobserved, body, held, null);
        }

        public static String tail(String s) {
            if (s == null) return null;
            return s.length() <= 600 ? s : s.substring(s.length() - 600);
        }

        public static String clip(String s) {
            if (s == null) return null;
            return s.length() <= 300 ? s : s.substring(0, 300);
        }
    }

    /**
     * @param verdict    {@code anchored}: holds on the tree and not with its capability taken out; {@code not-held}: the
     *                   tree as it is does not bear it out; {@code not-anchored}: it also holds with its capability taken
     *                   out; {@code unjudged}: a variant it needed did not run, or its capability had nothing to take out
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
     * @param feature   its own feature -- of the features whose removal breaks it, the most specific; null when none
     * @param verdict   {@code anchored}: holds, and some feature's removal breaks it; {@code not-anchored}: holds with any
     *                  one feature taken out (the platform answers it, not the project); {@code not-held};
     *                  {@code unjudged}: the tree as it is, or every feature variant, could not be exercised
     * @param dependsOn the other features whose ways in, taken out, also break it -- what it goes through
     * @param entry     its own way in (the implementing method), from which its feature follows; null when none
     */
    public record FeatureVerdict(String feature, String verdict, List<String> dependsOn, String entry) {
        public FeatureVerdict(String feature, String verdict, List<String> dependsOn) {
            this(feature, verdict, dependsOn, null);
        }
    }

    /**
     * One feature: a type that declares entry points.
     *
     * @param kind            how it is used, as a person reads it ("service-style", ...)
     * @param entryPoints     its ways in, as a person reads them
     * @param implementations the methods that implement them -- what its variant takes out
     * @param dependsOn       the features its kept claims were OBSERVED to need
     * @param madeOf          the capabilities (the layer cut) whose removal breaks its kept claims: the code it is made of
     * @param claims          its kept claims, by id
     */
    public record Feature(String name, String kind, List<String> entryPoints, List<String> implementations,
                          List<String> dependsOn, List<String> madeOf, List<String> claims) {
    }

    /**
     * @param kind how the project is used ("service-style", ...), or null when no way in was found
     */
    public record Account(String source, String copy, ColdRead coldRead, String kind, List<Feature> features,
                          List<Capability> capabilities, List<String> unknownDeclarations, List<ClaimProposer.Proposal> proposals,
                          List<Variant> variants, List<JudgedClaim> claims, Map<String, Integer> verdicts, long durationMs) {
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

        // Started when it has a program entry, called when it has none.
        EntryAdapter adapter = started;
        List<EntryPoint> points = started.find(files, coldRead);
        if (points.isEmpty()) {
            adapter = called;
            points = called.find(files, coldRead);
        }
        if (points.isEmpty()) adapter = null;
        Map<String, List<EntryPoint>> byFeature = featuresOf(points);
        Map<EntryKind, ClaimProposer.Way> offered = new LinkedHashMap<>();
        for (EntryPoint e : points) {
            EntryAdapter a = adapter;
            offered.computeIfAbsent(e.kind(), k -> new ClaimProposer.Way(new ArrayList<>(), a.exerciseForm(k))).points().add(e);
        }
        String kind = offered.isEmpty() ? null : offered.keySet().iterator().next().label();
        log.warn("T4-EK: {} -- {} way(s) in, {} feature(s): {}", kind == null ? "no way in" : kind, points.size(),
                byFeature.size(), byFeature.keySet());

        if (adapter == started && points.stream().anyMatch(e -> e.kind() == EntryKind.SERVICE_STYLE)) {
            askForServices(original, progress);
        }

        progress.accept("Finding its capabilities (the model reads the code)");
        CapabilityDiscovery.Found found = discovery.discover(original, files, null);
        List<Capability> capabilities = found.capabilities();
        log.warn("T4-EK: {} capabilities in {}", capabilities.size(), source);

        List<ClaimProposer.Proposal> proposals = new ArrayList<>();
        List<Claim> claims = new ArrayList<>();
        String startsWith = projectServices == null ? null : startState(projectServices.toStart(), original);
        if (adapter != null) {
            progress.accept("Asking the project, started as it is, what it answers before anyone has used it");
            String observed = adapter.observedBeforeAnyUse(original, points);
            if (observed != null) {
                log.warn("T4-EO: observed before any use --\n{}", observed);
                startsWith = (startsWith == null ? "" : startsWith.strip() + "\n\n") + observed;
            }
            for (Capability c : capabilities) {
                progress.accept("Proposing claims for " + c.name() + " (" + (proposals.size() + 1) + " of " + capabilities.size() + ")");
                ClaimProposer.Proposal p = proposer.propose(original, files, inventory, c, capabilities, coldRead, offered,
                        claims.size() + 1, startsWith);
                proposals.add(p);
                claims.addAll(p.claims());
            }
        }

        List<Variant> variants = new ArrayList<>();
        Map<String, Map<String, Observation>> seen = new LinkedHashMap<>();   // claim id -> variant -> observation
        claims.forEach(k -> seen.put(k.id(), new LinkedHashMap<>()));
        // One variant per way in (its implementing method), not per feature: the "most specific" rule below holds for
        // single ways in -- what depends on B breaks wherever B is taken out, so B breaks at least as many claims -- and
        // NOT for a type that groups unrelated methods (labelmaker, 2026-11-09: Zone, small, was read as depending on
        // Labeler, large, because a claim of next was broken by both and Zone broke fewer claims in all).
        Map<String, String> typeOfEntry = new TreeMap<>();
        for (EntryPoint e : points) {
            String id = e.memberId(inventory);
            if (id != null) typeOfEntry.putIfAbsent(id, e.type());
        }
        if (adapter != null) {
            int total = 1 + capabilities.size() + typeOfEntry.size(), n = 1;
            progress.accept("Checking " + claims.size() + " claims against the code as it is (1 of " + total + ")");
            variants.add(runVariant(adapter, "as-is", original, List.of(), claims, seen));
            for (Capability c : capabilities) {
                progress.accept("Checking them without " + c.name() + " (" + (++n) + " of " + total + ")");
                variants.add(stubbedVariant(adapter, c.id(), work.resolve("without-" + c.id()), original, files, inventory,
                        c.declarations(), claims, seen));
            }
            for (String id : typeOfEntry.keySet()) {
                progress.accept("Checking them without " + id + " (" + (++n) + " of " + total + ")");
                variants.add(stubbedVariant(adapter, "entry:" + id,
                        work.resolve("without-entry-" + id.replaceAll("[^A-Za-z0-9_-]", "_")), original, files,
                        inventory, List.of(id), claims, seen));
            }
        }

        Map<String, Variant> byName = new LinkedHashMap<>();
        variants.forEach(v -> byName.put(v.capability() == null ? "as-is" : v.capability(), v));
        List<JudgedClaim> judged = new ArrayList<>();
        Map<String, Integer> verdicts = new LinkedHashMap<>();
        Map<String, FeatureVerdict> byClaim = attribute(claims, seen, byName, typeOfEntry);
        for (Claim k : claims) {
            JudgedClaim layer = judge(k, seen.get(k.id()), byName, capabilities);
            FeatureVerdict fv = byClaim.get(k.id());
            judged.add(new JudgedClaim(k, layer.verdict(), layer.alsoNeeds(), layer.observations(), fv));
            verdicts.merge(fv.verdict(), 1, Integer::sum);
        }
        List<Feature> features = summarise(byFeature, judged);
        Account account = new Account(source.toString(), work.toString(), coldRead, kind, features, capabilities,
                found.unknown(), proposals, variants, judged, verdicts, System.currentTimeMillis() - t0);
        json.writeValue(work.resolve("account.json").toFile(), account);
        log.warn("T4-EK: account of {} written to {} -- {} ({} ms)", source, work, verdicts, account.durationMs());
        return account;
    }

    /**
     * What checking one stated line found.
     *
     * @param line     the line, as stated
     * @param feature  the feature its kept claims belong to (the first kept claim's), null when none was kept
     * @param claims   the claims that hold on the tree and stop holding with their way in taken out -- with each, the
     *                 sequence as it went out; empty when the code was not shown to do what the line states
     * @param proposed how many claims the model proposed for it
     * @param why      for a line with no kept claim: what was seen instead, in words
     */
    public record StatedLine(String line, String feature, List<Claim> claims, int proposed, String why) {
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean holds() {
            return !claims.isEmpty();
        }
    }

    /**
     * Keeps what checking stated lines found IN THE PROJECT ({@code file} under its {@code meta/}): each line, the
     * claims kept for it with their exercises, how many were proposed, and -- for a line that became open work -- why
     * nothing was kept. The goals and stored checks are only what followed from it; without this record the reason a
     * line was built, or was not, is gone when the application stops (the user, 2026-11-11: "store it in the
     * project's meta").
     */
    public static void keepStated(Path file, List<StatedLine> checked) throws IOException {
        if (checked == null || checked.isEmpty()) return;
        Files.createDirectories(file.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), checked);
    }

    /**
     * T4-EK slice (c): lines someone STATED about a project that already has code (the operator, adding to a
     * reverse-engineered seed) are checked before they become work -- each the reverse-engineering way: the model
     * proposes claims for the line, and a claim is kept when it holds on the tree and no longer does with its own way
     * in taken out. A line with a kept claim is something the project already does, and gets its criteria; a line with
     * none is open work, as every stated line was before. Field, 2026-11-10: "a refused postcode uses up no running
     * number" already held, and the loop built a second method for it in 8 cycles.
     */
    public List<StatedLine> checkStated(Account account, List<String> lines, java.util.function.Consumer<String> progress)
            throws IOException {
        return checkStated(Path.of(account.source()), Path.of(account.copy()).getParent(), account.coldRead(),
                account.capabilities(), account.claims().size() + 1, lines, progress);
    }

    /**
     * As above for ANY project that has code -- one the loop built as much as one it did not: the tree at
     * {@code source} is read as it stands ({@code coldRead} null: read here), no account of an earlier reverse
     * engineering is needed. A line someone states about a finished project later (its next input, in words) is the
     * same question as a line added to a composed seed.
     *
     * @param workRoot    where the copies of the tree are made
     * @param firstNumber the number the first claim is numbered from; the caller keeps claim ids apart across calls
     */
    public List<StatedLine> checkStated(Path source, Path workRoot, ColdRead coldReadOrNull, List<Capability> capabilities,
                                        int firstNumber, List<String> lines, java.util.function.Consumer<String> progress)
            throws IOException {
        List<StatedLine> out = new ArrayList<>();
        if (lines.isEmpty()) return out;
        Path work = workRoot.resolve("stated-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")));
        Path original = work.resolve("tree");
        copy(source, original);
        ColdRead coldRead = coldReadOrNull != null ? coldReadOrNull : coldReadService.read(original);
        Map<String, String> files = ProjectMerge.files(original);
        Map<String, Declaration> inventory = CapabilityCut.inventory(files);
        EntryAdapter adapter = started;
        List<EntryPoint> points = started.find(files, coldRead);
        if (points.isEmpty()) {
            adapter = called;
            points = called.find(files, coldRead);
        }
        if (points.isEmpty()) {
            lines.forEach(l -> out.add(new StatedLine(l, null, List.of(), 0, "the project has no way in to exercise it by")));
            return out;
        }
        Map<EntryKind, ClaimProposer.Way> offered = new LinkedHashMap<>();
        for (EntryPoint e : points) {
            EntryAdapter a = adapter;
            offered.computeIfAbsent(e.kind(), k -> new ClaimProposer.Way(new ArrayList<>(), a.exerciseForm(k))).points().add(e);
        }
        Map<String, String> typeOfEntry = new TreeMap<>();
        for (EntryPoint e : points) {
            String id = e.memberId(inventory);
            if (id != null) typeOfEntry.putIfAbsent(id, e.type());
        }
        String known = projectServices == null ? null : startState(projectServices.toStart(), original);
        String observed = adapter.observedBeforeAnyUse(original, points);
        if (observed != null) known = (known == null ? "" : known.strip() + "\n\n") + observed;

        Map<String, List<Claim>> byLine = new LinkedHashMap<>();
        List<Claim> claims = new ArrayList<>();
        int first = firstNumber;
        for (String line : lines) {
            progress.accept("Checking whether the project already does what you added (" + (byLine.size() + 1) + " of "
                    + lines.size() + "): the model reads the code");
            ClaimProposer.Proposal p = proposer.proposeFor(original, line, capabilities, coldRead, offered,
                    first + claims.size(), known);
            byLine.put(line, p.claims());
            claims.addAll(p.claims());
        }
        Map<String, Map<String, Observation>> seen = new LinkedHashMap<>();
        claims.forEach(k -> seen.put(k.id(), new LinkedHashMap<>()));
        Map<String, Variant> byName = new LinkedHashMap<>();
        if (!claims.isEmpty()) {
            progress.accept("Checking " + claims.size() + " claims for your lines against the code as it is");
            byName.put("as-is", runVariant(adapter, "as-is", original, List.of(), claims, seen));
            List<Claim> held = claims.stream().filter(k -> holds(seen.get(k.id()).get("as-is"))).toList();
            int n = 0;
            // Only the claims that hold are worth a variant; with none, the lines are open work and nothing more is built.
            for (String id : held.isEmpty() ? List.<String>of() : typeOfEntry.keySet()) {
                progress.accept("Checking them without " + id + " (" + (++n) + " of " + typeOfEntry.size() + ")");
                byName.put("entry:" + id, stubbedVariant(adapter, "entry:" + id,
                        work.resolve("without-entry-" + id.replaceAll("[^A-Za-z0-9_-]", "_")), original, files, inventory,
                        List.of(id), held, seen));
            }
        }
        Map<String, FeatureVerdict> verdicts = attribute(claims, seen, byName, typeOfEntry);
        for (String line : lines) {
            List<Claim> kept = new ArrayList<>();
            String feature = null;
            Map<String, Integer> others = new TreeMap<>();
            for (Claim k : byLine.get(line)) {
                FeatureVerdict v = verdicts.get(k.id());
                if (v != null && "anchored".equals(v.verdict())) {
                    Observation asIs = seen.get(k.id()).get("as-is");
                    kept.add(asIs != null && asIs.sent() != null
                            ? new Claim(k.id(), k.capability(), k.text(), k.kind(), asIs.sent(), k.expected(), k.test(), k.run()) : k);
                    if (feature == null) feature = v.feature();
                } else {
                    others.merge(v == null ? "unjudged" : v.verdict(), 1, Integer::sum);
                }
            }
            String why = !kept.isEmpty() ? null : byLine.get(line).isEmpty() ? "the model found nothing in the code that does it"
                    : "no claim for it was borne out (" + others + ")";
            out.add(new StatedLine(line, feature, List.copyOf(kept), byLine.get(line).size(), why));
            log.warn("T4-EK: stated \"{}\" -- {} of {} claim(s) kept{}", line, kept.size(), byLine.get(line).size(),
                    kept.isEmpty() ? " (" + why + ")" : " under " + feature);
        }
        json.writeValue(work.resolve("stated.json").toFile(), out);
        return out;
    }

    /**
     * T4-EN: before minutes of model work on a project that cannot start -- when nothing has been said about its
     * services yet, its configuration names an address on this machine that does not answer, and a start fails, the
     * job stops and asks ({@link com.walpro.autobuilder.core.services.ServicesNeeded}). A recorded answer, either way,
     * is not asked again: a yes is started before every program start by the boot gate, a no starts nothing.
     */
    /** How much of a setup script is quoted to the claim proposer; a longer one is named, and it can be read by probe. */
    static final int SETUP_SCRIPT_BUDGET_CHARS = 6_000;

    /**
     * T4-EO: what exists each time the project is started for an exercise, said from the operator's recorded answer --
     * each service starts fresh, filled by the project's own setup script when one was ticked and empty otherwise. A
     * claim about a rule over existing things (someone who may log in, a record to read) can only be exercised with
     * things that exist; without this the proposer guesses them. Null when the project is started with no service.
     */
    static String startState(List<com.walpro.autobuilder.core.services.ServiceProposal> services, Path tree) {
        if (services == null || services.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("WHAT EXISTS EACH TIME IT IS STARTED FOR AN EXERCISE:\n");
        for (var s : services) {
            sb.append("Its ").append(s.name()).append(" (").append(s.image()).append(") is started new each time. ");
            String script = null;
            if (s.initScript() != null) {
                try {
                    script = java.nio.file.Files.readString(tree.resolve(s.initScript()));
                } catch (java.io.IOException | RuntimeException e) {
                    script = null;   // named below, to be read by probe
                }
            }
            if (s.initScript() == null) {
                sb.append("It starts EMPTY: nothing exists in it but what the program creates when it starts. Whatever a "
                        + "claim needs to exist, the claim's own earlier requests must create.\n");
            } else if (script == null || script.length() > SETUP_SCRIPT_BUDGET_CHARS) {
                sb.append("It is filled first by the project's own script ").append(s.initScript())
                        .append(" -- read it to see what exists; nothing else does unless the program or a claim's own "
                                + "earlier requests create it.\n");
            } else {
                sb.append("It is filled first by the project's own script ").append(s.initScript())
                        .append(" (below); nothing else exists unless the program or a claim's own earlier requests "
                                + "create it.\n--- ").append(s.initScript()).append('\n').append(script.strip()).append('\n');
            }
        }
        return sb.toString();
    }

    private void askForServices(Path tree, java.util.function.Consumer<String> progress) {
        if (serviceNeeds == null || projectServices == null) return;
        var recorded = projectServices.recorded();
        if (recorded.isPresent()) {
            if (recorded.get().yes() && serviceContainers != null) tryRecorded(tree, recorded.get(), progress);
            return;
        }
        var missing = serviceNeeds.unanswered(tree);
        if (missing.isEmpty()) return;
        progress.accept("Trying to start it once (its configuration names a service at port " + missing.get(0).port()
                + ", where nothing answers)");
        String why = program.startFailure(tree);
        if (why == null) return;
        progress.accept("Finding what it needs to start");
        var proposals = serviceNeeds.propose(tree, missing, why);
        if (proposals.isEmpty()) {
            log.warn("T4-EN: {} did not start ({}) and no service could be proposed", tree, why);
            return;
        }
        throw new com.walpro.autobuilder.core.services.ServicesNeeded(proposals);
    }

    /** How many times a yes whose container did not come up is changed from its own words before it is asked again. */
    static final int MAX_REVISIONS = 2;

    /**
     * T4-EN: what the operator said yes to, started once on its own before anything else: a service that does not come
     * up would fail every one of the program starts that follow. When it does not, the model reads what its container
     * said; a revision that changes only its environment (the same image, ports and setup script -- what the yes was
     * given to) is recorded as the yes, with what changed and why, and tried again; anything else, or a service still
     * not up after {@link #MAX_REVISIONS}, is asked again with the container's words.
     */
    private void tryRecorded(Path tree, com.walpro.autobuilder.core.services.ProjectServices.Decision yes,
                             java.util.function.Consumer<String> progress) {
        progress.accept("Starting the services you said yes to, once, to see that they come up");
        var decision = yes;
        for (int round = 0; ; round++) {
            List<com.walpro.autobuilder.core.services.ServiceProposal> again = new ArrayList<>();
            try (var started = serviceContainers.startFresh(tree)) {
                if (!started.failed()) return;
                log.warn("T4-EN: what the operator said yes to did not come up -- {}", started.why());
                progress.accept("Reading what the service said");
                com.walpro.autobuilder.core.services.ServiceProposal revisedOne = null;
                for (var p : decision.services()) {
                    if (!p.equals(started.failedOn())) {
                        again.add(p);
                        continue;
                    }
                    var revised = serviceNeeds.revise(p, started.said(), started.why());
                    if (round < MAX_REVISIONS && p.sameButEnvironment(revised)) {
                        revised = new com.walpro.autobuilder.core.services.ServiceProposal(revised.name(), revised.image(),
                                revised.hostPort(), revised.containerPort(), revised.env(), revised.initDir(),
                                revised.initScripts(), revised.initScript(), revised.source(), revised.evidence(),
                                p.failure(), revised.environmentChangeFrom(p) + " -- after it said: " + clip(started.said()));
                        revisedOne = revised;
                    }
                    again.add(revised);
                }
                if (revisedOne == null) throw new com.walpro.autobuilder.core.services.ServicesNeeded(again);
                decision = new com.walpro.autobuilder.core.services.ProjectServices.Decision(true, again, java.time.Instant.now());
                projectServices.record(decision);
                log.warn("T4-EN: {} changed under the same yes -- {}", revisedOne.name(), revisedOne.revisedBecause());
                progress.accept("Trying " + revisedOne.name() + " again with its environment changed from what it said");
            }
        }
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() <= 400 ? s : "..." + s.substring(s.length() - 400);
    }

    /** One claim's verdict by capability; {@code variants} by name, {@code "as-is"} included. */
    static JudgedClaim judge(Claim k, Map<String, Observation> obs, Map<String, Variant> variants, List<Capability> capabilities) {
        Observation asIs = obs.get("as-is");
        Variant own = variants.get(k.capability());
        String verdict;
        if (!variants.get("as-is").ran()) {
            verdict = "unjudged";
        } else if (!holds(asIs)) {
            verdict = "not-held";
        } else if (own == null || !own.ran() || own.stubbedMethods().isEmpty()) {
            // A capability with nothing to take out leaves the tree as it is: every claim would "hold without it".
            verdict = "unjudged";
        } else {
            verdict = holds(obs.get(k.capability())) ? "not-anchored" : "anchored";
        }
        List<String> alsoNeeds = new ArrayList<>();
        if (holds(asIs)) {
            for (Capability other : capabilities) {
                Variant v = variants.get(other.id());
                if (other.id().equals(k.capability()) || v == null || !v.ran()) continue;
                if (!holds(obs.get(other.id()))) alsoNeeds.add(other.id());
            }
        }
        return new JudgedClaim(k, verdict, alsoNeeds, List.copyOf(obs.values()));
    }

    /** Entry points grouped by the type that declares their implementation: a feature per type, one rule for every kind. */
    static Map<String, List<EntryPoint>> featuresOf(List<EntryPoint> points) {
        Map<String, List<EntryPoint>> out = new TreeMap<>();
        for (EntryPoint e : points) out.computeIfAbsent(e.type(), k -> new ArrayList<>()).add(e);
        return out;
    }

    /**
     * Every claim judged by feature, from what the way-in variants answered it -- nothing is read from the claim.
     * A held claim's broken-by set is the ways in whose removal breaks it; empty, it asserts nothing about the project
     * (not-anchored). Its own way in is the member of that set whose removal breaks the FEWEST held claims overall:
     * whatever depends on a way in breaks wherever that way in is taken out, so the depended-on one breaks at least as
     * many claims and the dependent is the more specific. Ties go to the first by name. Its feature is the type of its
     * own way in; the types of the others in the set (its own excepted) are what it depends on.
     *
     * @param typeOfEntry each way in that has a variant (its member id), and the type -- the feature -- that declares it
     */
    static Map<String, FeatureVerdict> attribute(List<Claim> claims, Map<String, Map<String, Observation>> seen,
                                                 Map<String, Variant> variants, Map<String, String> typeOfEntry) {
        Variant asIsVariant = variants.get("as-is");
        boolean asIsRan = asIsVariant != null && asIsVariant.ran();
        List<String> judging = typeOfEntry.keySet().stream().filter(id -> {
            Variant v = variants.get("entry:" + id);
            return v != null && v.ran() && !v.stubbedMethods().isEmpty();
        }).toList();
        Map<String, List<String>> brokenBy = new LinkedHashMap<>();
        Map<String, Integer> breaks = new TreeMap<>();
        for (Claim k : claims) {
            Map<String, Observation> obs = seen.get(k.id());
            if (!asIsRan || !holds(obs.get("as-is"))) continue;
            List<String> set = new ArrayList<>();
            for (String id : judging) {
                if (!holds(obs.get("entry:" + id))) {
                    set.add(id);
                    breaks.merge(id, 1, Integer::sum);
                }
            }
            brokenBy.put(k.id(), set);
        }
        Map<String, FeatureVerdict> out = new LinkedHashMap<>();
        for (Claim k : claims) {
            List<String> set = brokenBy.get(k.id());
            if (!asIsRan) out.put(k.id(), new FeatureVerdict(null, "unjudged", List.of()));
            else if (set == null) out.put(k.id(), new FeatureVerdict(null, "not-held", List.of()));
            else if (judging.isEmpty()) out.put(k.id(), new FeatureVerdict(null, "unjudged", List.of()));
            else if (set.isEmpty()) out.put(k.id(), new FeatureVerdict(null, "not-anchored", List.of()));
            else {
                String own = set.stream().min(java.util.Comparator.comparing((String id) -> breaks.get(id))
                        .thenComparing(id -> id)).orElseThrow();
                String feature = typeOfEntry.get(own);
                List<String> dependsOn = set.stream().map(typeOfEntry::get).filter(t -> !t.equals(feature)).distinct().toList();
                out.put(k.id(), new FeatureVerdict(feature, "anchored", dependsOn, own));
            }
        }
        return out;
    }

    static List<Feature> summarise(Map<String, List<EntryPoint>> byFeature, List<JudgedClaim> judged) {
        List<Feature> out = new ArrayList<>();
        for (var f : byFeature.entrySet()) {
            Set<String> labels = new LinkedHashSet<>(), methods = new LinkedHashSet<>();
            f.getValue().forEach(e -> {
                labels.add(e.label());
                methods.add(e.type() + "." + e.method());
            });
            Set<String> dependsOn = new TreeSet<>(), madeOf = new TreeSet<>();
            List<String> kept = new ArrayList<>();
            for (JudgedClaim j : judged) {
                if (j.byFeature() == null || !f.getKey().equals(j.byFeature().feature())
                        || !j.byFeature().verdict().equals("anchored")) continue;
                kept.add(j.claim().id());
                dependsOn.addAll(j.byFeature().dependsOn());
                if (j.verdict().equals("anchored")) madeOf.add(j.claim().capability());
                madeOf.addAll(j.alsoNeeds());
            }
            String kind = f.getValue().isEmpty() ? null : f.getValue().get(0).kind().label();
            out.add(new Feature(f.getKey(), kind, List.copyOf(labels), List.copyOf(methods),
                    List.copyOf(dependsOn), List.copyOf(madeOf), kept));
        }
        return out;
    }

    /** Held = observed, and as the claim's kind judges it. A sequence that could not be completed bears nothing out. */
    static boolean holds(Observation o) {
        return o != null && o.held();
    }

    private Variant stubbedVariant(EntryAdapter adapter, String name, Path dir, Path original, Map<String, String> files,
                                   Map<String, Declaration> inventory, List<String> ids, List<Claim> claims,
                                   Map<String, Map<String, Observation>> seen) throws IOException {
        copy(original, dir);
        CapabilityStub.Result stub = CapabilityStub.stub(files, ids, inventory);
        for (Map.Entry<String, String> e : stub.stubbed().entrySet()) {
            Files.writeString(dir.resolve(e.getKey()), e.getValue());
        }
        return runVariant(adapter, name, dir, stub.methods(), claims, seen);
    }

    private static Variant runVariant(EntryAdapter adapter, String name, Path dir, List<String> stubbed, List<Claim> claims,
                                      Map<String, Map<String, Observation>> seen) {
        String capability = name.equals("as-is") ? null : name;
        EntryAdapter.Ran ran = adapter.exercise(dir, name, claims, seen);
        return new Variant(capability, stubbed, ran.built(), ran.ran(), ran.why());
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
}
