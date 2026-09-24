package com.walpro.autobuilder.analysis.structure;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * T4-BS: extracts structural "wiring" facts from the output Java sources to sharpen the completion
 * verifier's input. T4-BP-3 runs the same LOCAL model that false-positived 18/18 on the 2026-06-16
 * hollow artifact; local-first rules out a stronger model, so the lever is the verifier's INPUT.
 * Instead of a flat 25-files × 150-lines blob, this surfaces the concrete wiring smells that
 * distinguish a hollow artifact from a real one — pattern recognition over wiring beats
 * presence-matching over a blob ([[feedback-activity-not-progress]]: source "what's missing" from
 * the compiler grammar + symbol model, not string-scraping).
 *
 * <p>Facts surfaced (each a high-confidence structural observation — the analyzer errs toward
 * silence so it raises, not lowers, precision):
 * <ul>
 *   <li><b>stub bodies</b> — a method that only {@code return null} / has an empty body / only throws;</li>
 *   <li><b>never-assigned fields</b> — a returned-object's fields that no code ever assigns (the
 *       empty {@code PriceBreakdown});</li>
 *   <li><b>missing persistence</b> — entities are built but no {@code save*}/{@code persist*} call
 *       exists anywhere (the order never persisted);</li>
 *   <li><b>unmapped throws</b> — exceptions are thrown but no {@code @ControllerAdvice} /
 *       {@code @ExceptionHandler} / {@code @ResponseStatus} maps them to a status; when mappings
 *       DO exist, the comparison sharpens to type level: thrown types no mapping covers, and
 *       mapped types nothing ever instantiates (field run 3, 2026-07-03: raw
 *       {@code RuntimeException} thrown past 8 mapped-but-never-thrown exception types — every
 *       rule violation a 500 while the file-level check stayed silent);</li>
 *   <li><b>unread parameters</b> — a method parameter the body never references;</li>
 *   <li><b>missing validation surface</b> — a request-body binding whose declared type carries no
 *       field constraint annotations and/or whose parameter has no validation trigger, so a
 *       "rejects invalid input / returns 400" requirement cannot be met (the run-5 measured
 *       verifier false-pass class: validation criteria graded ✓ with no validation code).</li>
 * </ul>
 *
 * <p>Pure AST over the supplied file set — no classpath, no symbol-solver configuration, no network;
 * unparseable files are skipped. Bounded output ({@value #MAX_OBSERVATIONS}) so it never bloats the prompt.
 */
@Slf4j
@Component
public class WiringFactsAnalyzer {

    static final int MAX_OBSERVATIONS = 25;

    /** T3-BW: how many OTHER types must agree before their shared shape counts as the norm. */
    static final int PEER_GROUP_MIN_OTHERS = 2;

    /**
     * The peer bar for {@link #findUnregisteredInjectedTypes}, and the one place in this class that
     * does not need {@link #PEER_GROUP_MIN_OTHERS}.
     *
     * <p><b>Why this rule may ask for less, 2026-09-03.</b> The other rules infer an anomaly from
     * the peer group ALONE, so they need a group big enough to establish a norm. This rule has
     * independent evidence the others do not: the type is a constructor parameter of a type that IS
     * registered, so the container will be asked for it and will fail without it. The peer set is
     * only there to name WHICH annotation is missing, and one annotated peer names it as well as
     * two do.
     *
     * <p>Requiring two hid the defect exactly as it got worse. Run {@code 2026-08-30_18-44-37} left
     * one repository of three unregistered, saw two annotated peers, and was caught. Run
     * {@code 2026-09-03_19-05-02} left TWO of three unregistered, so each candidate saw a single
     * annotated peer, the rule went silent, and the artifact ended unstartable on the same defect
     * this rule and its repair exist for. A detector that stops firing as the fault spreads is
     * worse than one that never fired.
     */
    static final int INJECTED_TYPE_MIN_ANNOTATED_PEERS = 1;

    private static final Pattern MUTATOR_CALL = Pattern.compile("^(save|persist|store|insert)\\w*$");
    private static final Set<String> STATUS_MAPPING_ANNOTATIONS = AnnotationVocabularies.STATUS_MAPPING;
    private static final Pattern JAVA_FILE = Pattern.compile(".*\\.java$");

    /**
     * One wording, two consumers. Stated once so the criteria verifier and the evolution prompt
     * cannot drift apart on the same fact — the drift that let this half reach only the verifier.
     */
    private static final String NO_STATUS_MAPPING_FACT =
            "Exceptions are thrown but no HTTP status mapping was found "
            + "(no @ControllerAdvice / @ExceptionHandler / @ResponseStatus) — failures will surface as 500s.";

    // Annotation-role vocabularies live in AnnotationVocabularies (one home — the sibling lists
    // this analyzer used to carry had already drifted from InjectionWiringQuery's). Every check
    // still self-gates on its marker actually appearing in the target code — no opinion otherwise.
    private static final Set<String> INJECTION_MARKER_ANNOTATIONS =
            AnnotationVocabularies.FRAMEWORK_ASSIGNED_FIELD;
    private static final String REQUEST_BODY_ANNOTATION = AnnotationVocabularies.REQUEST_BODY;
    private static final Set<String> VALIDATION_TRIGGER_ANNOTATIONS = AnnotationVocabularies.VALIDATION_TRIGGER;

    /**
     * Returns the wiring observations for the given output files, capped at {@value #MAX_OBSERVATIONS}.
     * Empty when nothing structural stands out (or nothing parses) — callers treat empty as
     * "no structural concern", never as an observation of correctness.
     */
    public List<String> analyze(Map<String, String> outputFiles) {
        if (outputFiles == null || outputFiles.isEmpty()) return List.of();

        List<CompilationUnit> units = new ArrayList<>();
        for (Map.Entry<String, String> e : outputFiles.entrySet()) {
            if (e.getKey() == null || !JAVA_FILE.matcher(e.getKey()).matches()) continue;
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            try {
                new JavaParser().parse(e.getValue()).getResult().ifPresent(units::add);
            } catch (RuntimeException ex) {
                log.debug("T4-BS: skipping unparseable {}: {}", e.getKey(), ex.getMessage());
            }
        }
        if (units.isEmpty()) return List.of();

        List<String> observations = new ArrayList<>();
        // Project-wide facts computed once.
        Set<String> calledMethodNames = collectCalledMethodNames(units);
        boolean anyThrow = units.stream().anyMatch(u ->
                !u.findAll(ThrowStmt.class).isEmpty()
                        || u.findAll(MethodCallExpr.class).stream().anyMatch(c -> "orElseThrow".equals(c.getNameAsString())));
        boolean anyStatusMapping = units.stream().anyMatch(u ->
                u.findAll(com.github.javaparser.ast.expr.AnnotationExpr.class).stream()
                        .anyMatch(a -> STATUS_MAPPING_ANNOTATIONS.contains(a.getNameAsString())));
        boolean anyPersistenceCall = calledMethodNames.stream().anyMatch(n -> MUTATOR_CALL.matcher(n).matches());
        boolean anyEntityType = units.stream().anyMatch(u ->
                u.findAll(com.github.javaparser.ast.expr.AnnotationExpr.class).stream()
                        .anyMatch(a -> AnnotationVocabularies.PERSISTENT_TYPE.contains(a.getNameAsString())));

        for (CompilationUnit cu : units) {
            for (TypeDeclaration<?> type : cu.getTypes()) {
                collectTypeObservations(type, observations);
                if (observations.size() >= MAX_OBSERVATIONS) return observations.subList(0, MAX_OBSERVATIONS);
            }
        }

        collectValidationSurfaceObservations(units, observations);
        if (observations.size() >= MAX_OBSERVATIONS) return observations.subList(0, MAX_OBSERVATIONS);

        collectPeerAnnotationOutliers(units, observations);
        if (observations.size() >= MAX_OBSERVATIONS) return observations.subList(0, MAX_OBSERVATIONS);

        collectUnregisteredInjectedTypes(units, observations);
        if (observations.size() >= MAX_OBSERVATIONS) return observations.subList(0, MAX_OBSERVATIONS);

        if (anyThrow && !anyStatusMapping && catchMappedTypes(units).isEmpty()) {
            observations.add(NO_STATUS_MAPPING_FACT);
        } else if (anyThrow) {
            // Type-level half of the same invariant (field run 3, 2026-07-03): the artifact mapped
            // 8 dedicated exception types in a @ControllerAdvice and threw raw RuntimeException
            // everywhere — every business-rule violation surfaced as a 500 while the file-level
            // check above stayed silent because mappings existed. Compare THROWN types against
            // MAPPED types (project-declared supertype chains resolved, so a thrown subclass
            // covered by a mapped ancestor never false-alarms).
            collectThrowMappingObservations(units, observations);
        }
        if (anyEntityType && !anyPersistenceCall) {
            observations.add("Persistent entities are declared but no save/persist call exists anywhere — "
                    + "entities may be built in memory but never stored.");
        }

        return observations.size() > MAX_OBSERVATIONS ? observations.subList(0, MAX_OBSERVATIONS) : observations;
    }

    /**
     * T3-BW: the odd one out. Reports a type whose annotation set is a strict subset of what every
     * one of its peers carries — where "peer" is established by the code itself, not by us.
     *
     * <p>Deliberately vocabulary-FREE: this rule names no framework, no annotation and no domain
     * concept ([[feedback-no-ecosystem-jargon-in-framework-code]]). It compares types against each
     * other and lets the difference name itself. Field case that motivated it (run 2026-07-26_22-41):
     * two controllers carried {@code @RestController} + a mapping annotation and served requests; a
     * third carried only the mapping annotation, was never registered, and 404'd every path — while
     * compiling perfectly, so no probe or build could see it. The loop then spent 27 of 110 cycles
     * re-implementing an endpoint that already existed (see T2-ES).
     *
     * <p>The bar is RELATIVE to the project ([[feedback-relative-thresholds]]): a group is only a
     * norm when at least {@value #PEER_GROUP_MIN_OTHERS} other types agree, and only a UNANIMOUS
     * peer set counts. One dissenter makes it a style choice, not an anomaly — the analyzer errs
     * toward silence.
     */
    /**
     * T3-BW: just the odd-one-out facts, for callers that want this one signal rather than the
     * whole analyzer output (the evolution prompt takes this; the criteria verifier takes
     * {@link #analyze}). Same parsing contract: unparseable files skipped, empty on no input.
     */
    public List<String> peerAnnotationAnomalies(Map<String, String> outputFiles) {
        List<CompilationUnit> units = parseAll(outputFiles);
        List<String> out = new ArrayList<>();
        collectPeerAnnotationOutliers(units, out);
        collectUnregisteredInjectedTypes(units, out);
        announce("T3-BW", "annotation", out);
        return out;
    }

    /**
     * T3-BZ: a type placed differently from its own structural peers. Same shape as
     * {@link #peerAnnotationAnomalies} one axis over — the norm is the project's own majority, and
     * where there is no majority there is no opinion.
     *
     * <p>The peer relation is DERIVED, never declared. Types sharing a package are peers by
     * placement; each of them that names exactly one project type nominates it; the nominated types
     * are the group whose placement is compared. Field case (run 2026-08-23_02-12-34): three
     * repositories share a package and name {@code Book}, {@code Loan} and {@code Member}; two of
     * those sit in {@code …domain.model} and {@code Book} sits in {@code …domain}. It compiled and
     * booted, so no gate had reason to speak — and {@code Book} was the only cannot-find-symbol
     * class in the whole run, because freshly written code kept looking where its peers live.
     *
     * <p><b>No convention is encoded and none is learned.</b> The words this rule would need to be
     * a convention table — what a package "should" be called, how deep it should nest, which layout
     * is better — appear nowhere in it. It can only speak when the project has already established
     * a norm and one type breaks it, which is what stops it becoming a style police
     * ([[feedback-no-hardcoded-ecosystem-data]], [[feedback-relative-thresholds]]).
     */
    public List<String> peerPlacementAnomalies(Map<String, String> outputFiles) {
        List<CompilationUnit> units = parseAll(outputFiles);
        List<String> out = new ArrayList<>();
        collectPeerPlacementOutliers(units, out);
        announce("T3-BZ", "placement", out);
        return out;
    }

    /**
     * The structured form of the placement fact, for the relocation that acts on it. The prose
     * sentence is rendered FROM this record, so the calibrated conditions (three or more
     * nominating peers, a strict majority, silence on a tie) have one home and the mover can
     * never act on a case the report would not have made ([[feedback-one-invariant-one-home]]).
     *
     * @param typeName        the type sitting apart from its peers
     * @param actualPackage   where it sits now ({@code ""} for the default package)
     * @param majorityPackage where the strict majority of its peer group sits
     * @param nominatedBy     the peer whose naming put this type in the group
     * @param peerPackage     the package those nominating peers share
     * @param agreeing        the peer-group members already in {@code majorityPackage}
     * @param majorityCount   how many of the group sit in {@code majorityPackage}
     * @param groupSize       how many types the group holds
     */
    public record PeerPlacementAnomaly(
            String typeName, String actualPackage, String majorityPackage, String nominatedBy,
            String peerPackage, List<String> agreeing, int majorityCount, int groupSize) {}

    /** The same fact the prompt block reports, before it becomes a sentence. */
    public List<PeerPlacementAnomaly> peerPlacementFindings(Map<String, String> outputFiles) {
        return findPeerPlacementOutliers(parseAll(outputFiles));
    }

    /**
     * Both peer rules reached the evolution prompt and nowhere else, so whether either had ever
     * fired in the field could only be answered by reading a prompt — and prompts are not logged.
     * A watch calibrated against a proxy (a non-empty deleted-file list) scored NO-OPPORTUNITY on
     * runs where the rule may well have spoken.
     *
     * <p>One line per FIRING, carrying the facts themselves, rather than one per anomaly: the
     * analyzer is called on several paths in a cycle, so a reader must count DISTINCT statements,
     * not lines ([[feedback-count-events-not-log-lines]]).
     */
    private void announce(String ticket, String kind, List<String> anomalies) {
        if (anomalies.isEmpty()) return;
        log.info("{}: {} peer {} anomal{} reported — {}", ticket, anomalies.size(), kind,
                anomalies.size() == 1 ? "y" : "ies", String.join(" | ", anomalies));
    }

    private void collectPeerPlacementOutliers(List<CompilationUnit> units, List<String> out) {
        for (PeerPlacementAnomaly f : findPeerPlacementOutliers(units)) {
            out.add(String.format(
                    "%s sits in %s, but %d of the %d types named by the peers in %s sit in %s (%s do) — "
                            + "%s names %s the way its peers name theirs, so code written to that pattern "
                            + "will look for %s in %s and not find it.",
                    f.typeName(), f.actualPackage().isEmpty() ? "the default package" : f.actualPackage(),
                    f.majorityCount(), f.groupSize(),
                    f.peerPackage().isEmpty() ? "the default package" : f.peerPackage(),
                    f.majorityPackage(), String.join(", ", f.agreeing()),
                    f.nominatedBy(), f.typeName(), f.typeName(), f.majorityPackage()));
            if (out.size() >= MAX_OBSERVATIONS) return;
        }
    }

    private List<PeerPlacementAnomaly> findPeerPlacementOutliers(List<CompilationUnit> units) {
        List<PeerPlacementAnomaly> found = new ArrayList<>();
        Map<String, String> packageByType = new LinkedHashMap<>();
        for (CompilationUnit cu : units) {
            String pkg = cu.getPackageDeclaration().map(d -> d.getNameAsString()).orElse("");
            for (TypeDeclaration<?> type : cu.getTypes()) packageByType.put(type.getNameAsString(), pkg);
        }
        if (packageByType.size() <= PEER_GROUP_MIN_OTHERS) return found;

        // Types sharing a package are peers by placement; each nominates the one project type it
        // is about. A unit naming several has no single subject and nominates nothing.
        Map<String, Map<String, String>> nominationsByPackage = new LinkedHashMap<>();
        for (CompilationUnit cu : units) {
            String pkg = cu.getPackageDeclaration().map(d -> d.getNameAsString()).orElse("");
            Set<String> own = cu.getTypes().stream().map(TypeDeclaration::getNameAsString)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Set<String> named = namedProjectTypes(cu, packageByType.keySet(), own);
            if (named.size() != 1) continue;
            String nominee = named.iterator().next();
            for (String owner : own) {
                nominationsByPackage.computeIfAbsent(pkg, k -> new LinkedHashMap<>()).put(owner, nominee);
            }
        }

        for (Map.Entry<String, Map<String, String>> group : nominationsByPackage.entrySet()) {
            Map<String, String> nominations = group.getValue();
            // Fewer than three nominating peers is a style choice, not a norm.
            if (nominations.size() <= PEER_GROUP_MIN_OTHERS) continue;

            Map<String, Long> byPackage = new LinkedHashMap<>();
            for (String nominee : new LinkedHashSet<>(nominations.values())) {
                String pkg = packageByType.get(nominee);
                if (pkg != null) byPackage.merge(pkg, 1L, Long::sum);
            }
            long total = byPackage.values().stream().mapToLong(Long::longValue).sum();
            if (total <= PEER_GROUP_MIN_OTHERS) continue;

            String majority = null;
            long best = 0;
            boolean tied = false;
            for (Map.Entry<String, Long> e : byPackage.entrySet()) {
                if (e.getValue() > best) { best = e.getValue(); majority = e.getKey(); tied = false; }
                else if (e.getValue() == best) { tied = true; }
            }
            // No strict majority means the project has stated no norm, so neither does this.
            if (majority == null || tied || best * 2 <= total) continue;

            for (Map.Entry<String, String> nomination : nominations.entrySet()) {
                String nominee = nomination.getValue();
                String actual = packageByType.get(nominee);
                if (actual == null || actual.equals(majority)) continue;
                final String norm = majority;
                List<String> agreeing = new LinkedHashSet<>(nominations.values()).stream()
                        .filter(t -> norm.equals(packageByType.get(t)))
                        .toList();
                found.add(new PeerPlacementAnomaly(nominee, actual, majority, nomination.getKey(),
                        group.getKey(), agreeing, (int) best, (int) total));
                if (found.size() >= MAX_OBSERVATIONS) return found;
            }
        }
        return found;
    }

    /**
     * Project types this unit names — via an import that resolves to a declared type, or by using
     * one directly (a same-package reference needs no import). Its own types never count.
     */
    private Set<String> namedProjectTypes(CompilationUnit cu, Set<String> declared, Set<String> own) {
        Set<String> named = new LinkedHashSet<>();
        cu.getImports().forEach(i -> {
            String simple = i.getNameAsString();
            simple = simple.substring(simple.lastIndexOf('.') + 1);
            if (declared.contains(simple) && !own.contains(simple)) named.add(simple);
        });
        for (com.github.javaparser.ast.type.ClassOrInterfaceType t
                : cu.findAll(com.github.javaparser.ast.type.ClassOrInterfaceType.class)) {
            String simple = t.getNameAsString();
            if (declared.contains(simple) && !own.contains(simple)) named.add(simple);
        }
        return named;
    }

    /** Shared parse step: unparseable or empty entries are skipped, never fatal. */
    private List<CompilationUnit> parseAll(Map<String, String> outputFiles) {
        List<CompilationUnit> units = new ArrayList<>();
        if (outputFiles == null || outputFiles.isEmpty()) return units;
        for (Map.Entry<String, String> e : outputFiles.entrySet()) {
            if (e.getKey() == null || !JAVA_FILE.matcher(e.getKey()).matches()) continue;
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            try {
                new JavaParser().parse(e.getValue()).getResult().ifPresent(units::add);
            } catch (RuntimeException ex) {
                log.debug("skipping unparseable {}: {}", e.getKey(), ex.getMessage());
            }
        }
        return units;
    }

    /**
     * T3-CA: the type that carries NOTHING. {@link #collectPeerAnnotationOutliers} only considers
     * types that already have an annotation, so it reports "has @A, missing @B" and is structurally
     * blind to "has nothing while its peers have @A" — which is the case where the type is never
     * registered at all, and therefore the only one of the two that stops the application starting.
     *
     * <p>Field case (run 2026-08-22_06-19-55, boot-failed, replayed): {@code BookRepository} plain,
     * its two same-package peers annotated in 27 of 27 renders, and it is a constructor parameter
     * of an annotated service. All three rules returned empty on that tree, so the loop had no
     * structural fact about the defect that ended the run.
     *
     * <p>Three independent pieces of the project's own evidence must agree, which is what keeps
     * this quiet on legitimate plain types (models, DTOs, exceptions): the INJECTION SITE says the
     * type has to be supplied from outside, the PEERS say how types in that package are supplied,
     * and the type itself carries nothing. Names no framework and no annotation
     * ([[feedback-no-ecosystem-jargon-in-framework-code]]); a project that annotates nothing
     * produces no fact.
     */
    /**
     * The structured form of the {@link #collectUnregisteredInjectedTypes} fact, for the repair
     * that acts on it. The prose sentence below is rendered FROM this record, so the calibrated
     * conditions (factory-supplied types, interfaces, peer unanimity) have one home and the
     * repair can never act on a case the report would not have made.
     */
    public record UnregisteredInjectedType(
            String typeName, String missingAnnotation, String injectedBy, List<String> peers) {}

    /** The same fact the prompt block reports, before it becomes a sentence. */
    public List<UnregisteredInjectedType> unregisteredInjectedTypes(Map<String, String> outputFiles) {
        return findUnregisteredInjectedTypes(parseAll(outputFiles));
    }

    private void collectUnregisteredInjectedTypes(List<CompilationUnit> units, List<String> out) {
        for (UnregisteredInjectedType f : findUnregisteredInjectedTypes(units)) {
            out.add(String.format(
                    "%s is a constructor parameter of %s, which is annotated, but %s itself carries no "
                            + "annotation at all — while %s in its package carr%s @%s (%s). "
                            + "If @%s is what makes a type available for injection, %s cannot be supplied "
                            + "and %s cannot be constructed.",
                    f.typeName(), f.injectedBy(), f.typeName(),
                    f.peers().size() == 1 ? "another type" : "all " + f.peers().size() + " other types",
                    f.peers().size() == 1 ? "ies" : "y",
                    f.missingAnnotation(),
                    String.join(", ", f.peers()), f.missingAnnotation(), f.typeName(), f.injectedBy()));
            if (out.size() >= MAX_OBSERVATIONS) return;
        }
    }

    private List<UnregisteredInjectedType> findUnregisteredInjectedTypes(List<CompilationUnit> units) {
        List<UnregisteredInjectedType> found = new ArrayList<>();
        Map<String, Set<String>> annotationsByType = new LinkedHashMap<>();
        Map<String, String> packageByType = new LinkedHashMap<>();
        Set<String> interfaces = new LinkedHashSet<>();
        for (CompilationUnit cu : units) {
            String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
            for (TypeDeclaration<?> type : cu.getTypes()) {
                annotationsByType.put(type.getNameAsString(), type.getAnnotations().stream()
                        .map(AnnotationExpr::getNameAsString)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
                packageByType.put(type.getNameAsString(), pkg);
                if (type instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration c
                        && c.isInterface()) {
                    interfaces.add(type.getNameAsString());
                }
            }
        }

        // Constructor parameters of ANNOTATED types only: a plain class's constructor argument is
        // supplied by whoever calls new, and says nothing about registration.
        Set<String> injectedIntoAnnotated = new LinkedHashSet<>();
        Map<String, String> injectedBy = new LinkedHashMap<>();
        for (CompilationUnit cu : units) {
            for (TypeDeclaration<?> type : cu.getTypes()) {
                if (type.getAnnotations().isEmpty()) continue;
                for (var ctor : type.findAll(com.github.javaparser.ast.body.ConstructorDeclaration.class)) {
                    for (Parameter param : ctor.getParameters()) {
                        String name = param.getType().asString().replaceAll("<.*>", "");
                        if (annotationsByType.containsKey(name) && injectedIntoAnnotated.add(name)) {
                            injectedBy.put(name, type.getNameAsString());
                        }
                    }
                }
            }
        }

        // A type an annotated class INSTANTIATES has a supply route that owes nothing to its own
        // annotations — a factory method hands the instance over ready-made. Found by calibrating
        // against real trees rather than reasoned about: run 2026-08-22_04-48-23 scored 23/23 while
        // its LoanRepository carried nothing, because a configuration type built all three
        // repositories itself. Without this the rule contradicts a working artifact.
        Set<String> suppliedByFactory = new LinkedHashSet<>();
        for (CompilationUnit cu : units) {
            for (TypeDeclaration<?> type : cu.getTypes()) {
                if (type.getAnnotations().isEmpty()) continue;
                for (var created : type.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class)) {
                    suppliedByFactory.add(created.getType().getNameAsString());
                }
            }
        }

        for (String candidate : injectedIntoAnnotated) {
            if (!annotationsByType.get(candidate).isEmpty()) continue;
            if (suppliedByFactory.contains(candidate)) continue;
            // An interface the project never implements is supplied from outside it, so the
            // project's own annotation habits say nothing about whether it is available. Also
            // found by calibration, on run 2026-08-24_00-07-06: a bare interface extending a
            // library supertype, beside two annotated siblings — a style difference, not a
            // wiring defect. An interface WITH a project implementation is likewise fine; the
            // implementation is where registration would live.
            if (interfaces.contains(candidate)) continue;
            String pkg = packageByType.get(candidate);
            List<String> peers = packageByType.entrySet().stream()
                    .filter(e -> e.getValue().equals(pkg) && !e.getKey().equals(candidate))
                    .map(Map.Entry::getKey)
                    .filter(t -> !annotationsByType.get(t).isEmpty())
                    .toList();
            if (peers.size() < INJECTED_TYPE_MIN_ANNOTATED_PEERS) continue;

            // Unanimity, as elsewhere in this class: one dissenter makes it a style choice.
            Set<String> unanimous = new LinkedHashSet<>(annotationsByType.get(peers.get(0)));
            for (String peer : peers) unanimous.retainAll(annotationsByType.get(peer));
            if (unanimous.isEmpty()) continue;

            String shared = unanimous.iterator().next();
            found.add(new UnregisteredInjectedType(
                    candidate, shared, injectedBy.get(candidate), peers));
            if (found.size() >= MAX_OBSERVATIONS) return found;
        }
        return found;
    }

    private void collectPeerAnnotationOutliers(List<CompilationUnit> units, List<String> out) {
        Map<String, Set<String>> annotationsByType = new LinkedHashMap<>();
        for (CompilationUnit cu : units) {
            for (TypeDeclaration<?> type : cu.getTypes()) {
                Set<String> anns = type.getAnnotations().stream()
                        .map(a -> a.getNameAsString())
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                if (!anns.isEmpty()) annotationsByType.put(type.getNameAsString(), anns);
            }
        }
        if (annotationsByType.size() <= PEER_GROUP_MIN_OTHERS) return;

        Set<String> reported = new LinkedHashSet<>();
        // A shared annotation is what makes types peers: whatever role it denotes, these types
        // were given it for the same reason.
        Set<String> allAnnotations = annotationsByType.values().stream()
                .flatMap(Set::stream).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        for (String peerMarker : allAnnotations) {
            List<String> group = annotationsByType.entrySet().stream()
                    .filter(e -> e.getValue().contains(peerMarker))
                    .map(Map.Entry::getKey)
                    .toList();
            if (group.size() <= PEER_GROUP_MIN_OTHERS) continue;

            for (String candidate : group) {
                List<String> peers = group.stream().filter(g -> !g.equals(candidate)).toList();
                if (peers.size() < PEER_GROUP_MIN_OTHERS) continue;

                // What EVERY peer carries and this one does not.
                Set<String> unanimousPeerAnnotations = new LinkedHashSet<>(annotationsByType.get(peers.get(0)));
                for (String peer : peers) unanimousPeerAnnotations.retainAll(annotationsByType.get(peer));
                unanimousPeerAnnotations.removeAll(annotationsByType.get(candidate));
                if (unanimousPeerAnnotations.isEmpty()) continue;

                for (String missing : unanimousPeerAnnotations) {
                    if (!reported.add(candidate + "\u0000" + missing)) continue;
                    out.add(String.format(
                            "%s is the only one of the %d types annotated @%s that does not also carry @%s "
                                    + "(%s do) — if @%s is what registers or activates these types, %s is inert.",
                            candidate, group.size(), peerMarker, missing,
                            String.join(", ", peers), missing, candidate));
                    if (out.size() >= MAX_OBSERVATIONS) return;
                }
            }
        }
    }

    /** Formats the observations as a prompt block, or empty string when there are none. */
    public String asPromptBlock(Map<String, String> outputFiles) {
        List<String> obs = analyze(outputFiles);
        if (obs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("STRUCTURAL WIRING NOTES (concrete facts from the code — weigh these against each requirement):\n");
        for (String o : obs) sb.append("- ").append(o).append("\n");
        return sb.toString();
    }

    /**
     * T4-CQ: a method whose parameter list someone else decided -- an override, or a method of an
     * anonymous class, which exists only to implement a supertype's -- may leave a parameter unread
     * without ignoring any input. A visitor that does not need its context argument is the ordinary
     * case, and reporting it told the completion verifier that the second adopted project's parser
     * "ignores its input" (and named the anonymous visitor's method as the outer type's).
     */
    private static boolean signatureIsImposed(MethodDeclaration md) {
        return md.isAnnotationPresent("Override")
                || md.findAncestor(com.github.javaparser.ast.expr.ObjectCreationExpr.class).isPresent();
    }

    private void collectTypeObservations(TypeDeclaration<?> type, List<String> out) {
        String typeName = type.getNameAsString();
        // Records auto-assign their components; skip field/param analysis that would misfire on them.
        boolean isRecord = type.isRecordDeclaration();

        for (MethodDeclaration md : type.findAll(MethodDeclaration.class)) {
            if (out.size() >= MAX_OBSERVATIONS) return;
            String label = typeName + "." + md.getNameAsString() + "()";
            md.getBody().ifPresent(body -> {
                if (isStubBody(md, body)) {
                    out.add(label + " is a stub (returns null / empty body / only throws) — no real implementation.");
                } else if (!signatureIsImposed(md)) {
                    for (Parameter p : md.getParameters()) {
                        if (out.size() >= MAX_OBSERVATIONS) return;
                        if (!parameterIsRead(body, p.getNameAsString())) {
                            out.add(label + " never reads its parameter '" + p.getNameAsString()
                                    + "' — the input is ignored.");
                        }
                    }
                }
            });
        }

        if (!isRecord) {
            Set<String> assigned = assignedFieldNames(type);
            for (com.github.javaparser.ast.body.FieldDeclaration fd : type.getFields()) {
                if (out.size() >= MAX_OBSERVATIONS) return;
                if (fd.isStatic()) continue;
                // Container-injected fields are assigned by the DI container, not by code — the
                // field-injection style produced 8 false "never assigned" facts on the run-3
                // artifact (2026-07-03). Annotation names are data rows (T4-BM territory).
                if (fd.getAnnotations().stream()
                        .anyMatch(a -> INJECTION_MARKER_ANNOTATIONS.contains(a.getNameAsString()))) {
                    continue;
                }
                for (VariableDeclarator v : fd.getVariables()) {
                    String name = v.getNameAsString();
                    boolean hasInitializer = v.getInitializer().isPresent();
                    if (!hasInitializer && !assigned.contains(name)) {
                        out.add(typeName + "." + name + " is never assigned — it stays at its default value "
                                + "(returned objects carry uncomputed fields).");
                    }
                }
            }
        }
    }

    /** A method body is a stub when it is empty (non-void), only {@code return null}, or only throws. */
    private static boolean isStubBody(MethodDeclaration md, BlockStmt body) {
        List<Statement> stmts = body.getStatements();
        boolean voidReturn = "void".equals(md.getType().asString());
        if (stmts.isEmpty()) return !voidReturn; // empty non-void body produces nothing meaningful
        if (stmts.size() == 1) {
            Statement only = stmts.get(0);
            if (only instanceof ReturnStmt r) {
                return r.getExpression().map(e -> e instanceof NullLiteralExpr).orElse(false);
            }
            return only instanceof ThrowStmt;
        }
        return false;
    }

    /**
     * Validation-surface facts: for every request-body binding whose declared type is in the file
     * set, report when the surface is missing on either side — no field constraint annotations on
     * the type, no validation trigger on the parameter (or its declaring type), or both. Silent
     * when both sides are present, when the body type is not declared in the file set, or when the
     * project has no request-body bindings at all (the self-gate).
     */
    /**
     * Type-level throw-vs-mapping comparison, applied only when SOME status mapping exists (the
     * mapping-free case is the file-level fact above). Reports, as facts:
     * <ul>
     *   <li>a thrown exception type not covered by any mapped type (its project-declared
     *       supertype chain resolved) — those failures bypass the mappings and surface as 500s;</li>
     *   <li>a handler-mapped exception type that is never instantiated anywhere — the mapping
     *       exists but nothing can ever reach it.</li>
     * </ul>
     * Conservative: bare re-throws ({@code throw e;}) carry no type name and are skipped; mapped
     * types count as reachable if instantiated ANYWHERE (covers {@code orElseThrow(() -> new E())}
     * and factory patterns), not just inside {@code throw} statements.
     */
    private void collectThrowMappingObservations(List<CompilationUnit> units, List<String> out) {
        Set<String> mapped = new LinkedHashSet<>();
        // Run 18 (2026-07-06): the gap-only facts left the checker guessing on behaviour that
        // EXISTS — it marked a working 422 mapping unmet, the banked ✗ walled the subgoal, and
        // the root gave up on finished work. Record the status expression each mapping declares
        // (quoted from the code, no translation) so a positive fact can be emitted below.
        Map<String, String> statusTextByMapped = new java.util.LinkedHashMap<>();
        for (CompilationUnit cu : units) {
            for (com.github.javaparser.ast.expr.AnnotationExpr a
                    : cu.findAll(com.github.javaparser.ast.expr.AnnotationExpr.class)) {
                if (!AnnotationVocabularies.EXCEPTION_HANDLER.equals(a.getNameAsString())) continue;
                List<String> targets = new ArrayList<>();
                // @ExceptionHandler(X.class) / ({X.class, Y.class}) — collect the class literals.
                a.findAll(com.github.javaparser.ast.expr.ClassExpr.class)
                        .forEach(cls -> targets.add(simpleName(cls.getType().asString())));
                // Marker form: @ExceptionHandler with the type on the handler method's parameter.
                a.getParentNode().ifPresent(parent -> {
                    if (parent instanceof MethodDeclaration handler) {
                        if (targets.isEmpty()) {
                            handler.getParameters().forEach(p -> targets.add(simpleName(p.getTypeAsString())));
                        }
                        String status = handlerStatusText(handler);
                        if (status != null) {
                            targets.forEach(t -> statusTextByMapped.putIfAbsent(t, status));
                        }
                    }
                });
                mapped.addAll(targets);
            }
            // A type annotated @ResponseStatus maps itself.
            for (TypeDeclaration<?> type : cu.getTypes()) {
                type.getAnnotations().stream()
                        .filter(a -> AnnotationVocabularies.STATUS_DECLARATION.equals(a.getNameAsString()))
                        .findFirst()
                        .ifPresent(a -> {
                            mapped.add(type.getNameAsString());
                            a.findFirst(com.github.javaparser.ast.expr.FieldAccessExpr.class)
                                    .ifPresent(f -> statusTextByMapped.putIfAbsent(type.getNameAsString(), f.toString()));
                        });
            }
        }
        // T4-DC: a catch clause that returns a status maps its type just as an annotation does.
        // Without this the 2026-07-24 artifact — which maps entirely in controller catches — reads
        // as having no coverage, and every one of its eight domain types looks unmapped.
        mapped.addAll(catchMappedTypes(units));
        if (mapped.isEmpty()) return; // advice class without resolvable targets — no opinion

        // Project-declared supertype chains by simple name (extends edges visible in the file set).
        Map<String, String> extendsEdge = new java.util.HashMap<>();
        for (CompilationUnit cu : units) {
            for (TypeDeclaration<?> type : cu.getTypes()) {
                if (type instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration c
                        && !c.getExtendedTypes().isEmpty()) {
                    extendsEdge.put(c.getNameAsString(), simpleName(c.getExtendedTypes(0).getNameAsString()));
                }
            }
        }

        Set<String> thrownUnmapped = new LinkedHashSet<>();
        Set<String> thrownAndMapped = new LinkedHashSet<>();
        Set<String> instantiated = new LinkedHashSet<>();
        for (CompilationUnit cu : units) {
            cu.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class)
                    .forEach(oce -> instantiated.add(simpleName(oce.getType().getNameAsString())));
            for (ThrowStmt ts : cu.findAll(ThrowStmt.class)) {
                if (!(ts.getExpression() instanceof com.github.javaparser.ast.expr.ObjectCreationExpr oce)) continue;
                String thrown = simpleName(oce.getType().getNameAsString());
                String covering = coveringMapping(thrown, mapped, extendsEdge);
                if (covering == null) {
                    thrownUnmapped.add(thrown);
                } else {
                    String status = statusTextByMapped.get(covering);
                    thrownAndMapped.add(status != null ? thrown + " → " + status : thrown);
                }
            }
        }
        List<String> mappedNeverThrown = mapped.stream().filter(m -> !instantiated.contains(m)).toList();

        if (!thrownUnmapped.isEmpty() && out.size() < MAX_OBSERVATIONS) {
            out.add("Status mappings exist, but thrown exception type(s) " + thrownUnmapped
                    + " are not covered by any mapped type — those failures bypass the handlers and surface as 500s.");
        }
        if (!mappedNeverThrown.isEmpty() && out.size() < MAX_OBSERVATIONS) {
            out.add("Exception type(s) " + mappedNeverThrown
                    + " are mapped to statuses but never instantiated anywhere — nothing can reach those mappings.");
        }
        // The positive half (run 18): a requirement about a failure status that IS wired should
        // be confirmable from this line alone, instead of guessed from raw handler code.
        if (!thrownAndMapped.isEmpty() && out.size() < MAX_OBSERVATIONS) {
            out.add("Thrown exception type(s) WITH a status mapping in place: " + thrownAndMapped
                    + " — these failure paths return the mapped status.");
        }
    }

    /**
     * The mapped type covering the thrown type — itself or any project-declared ancestor —
     * or {@code null} when no mapping covers it.
     */
    private static String coveringMapping(String thrown, Set<String> mapped, Map<String, String> extendsEdge) {
        String current = thrown;
        for (int hops = 0; current != null && hops < 10; hops++) {
            if (mapped.contains(current)) return current;
            current = extendsEdge.get(current);
        }
        return null;
    }

    /**
     * The status expression a handler method declares, quoted verbatim from the code: the
     * {@code @ResponseStatus} member when present, else the first status constant referenced in
     * the body. {@code null} when neither exists (the fact is then emitted without a status).
     */
    private static String handlerStatusText(MethodDeclaration handler) {
        for (com.github.javaparser.ast.expr.AnnotationExpr a : handler.getAnnotations()) {
            if (AnnotationVocabularies.STATUS_DECLARATION.equals(a.getNameAsString())) {
                return a.findFirst(com.github.javaparser.ast.expr.FieldAccessExpr.class)
                        .map(Object::toString)
                        .orElse(a.toString());
            }
        }
        return handler.getBody()
                .flatMap(b -> b.findAll(com.github.javaparser.ast.expr.FieldAccessExpr.class).stream()
                        .filter(f -> "HttpStatus".equals(simpleName(f.getScope().toString())))
                        .findFirst())
                .map(Object::toString)
                .orElse(null);
    }

    private static String simpleName(String typeName) {
        int lastDot = typeName.lastIndexOf('.');
        return lastDot >= 0 ? typeName.substring(lastDot + 1) : typeName;
    }

    private void collectValidationSurfaceObservations(List<CompilationUnit> units, List<String> out) {
        Map<String, TypeDeclaration<?>> declaredTypes = new java.util.LinkedHashMap<>();
        for (CompilationUnit u : units) {
            for (TypeDeclaration<?> t : u.findAll(TypeDeclaration.class)) {
                declaredTypes.putIfAbsent(t.getNameAsString(), t);
            }
        }

        // T4-BM: constraints the project declares itself (an @interface meta-annotated
        // @Constraint) extend the vocabulary — without them a body type validated purely by
        // custom constraints would yield a FALSE "no field constraint annotations" fact.
        Set<String> constraintAnnotations = new LinkedHashSet<>(AnnotationVocabularies.FIELD_CONSTRAINT);
        constraintAnnotations.addAll(AnnotationVocabularies.projectDeclaredConstraints(units));

        for (CompilationUnit u : units) {
            for (MethodDeclaration md : u.findAll(MethodDeclaration.class)) {
                for (Parameter p : md.getParameters()) {
                    if (out.size() >= MAX_OBSERVATIONS) return;
                    if (p.getAnnotations().stream()
                            .noneMatch(a -> REQUEST_BODY_ANNOTATION.equals(a.getNameAsString()))) continue;
                    if (!p.getType().isClassOrInterfaceType()) continue;
                    var paramType = p.getType().asClassOrInterfaceType();
                    if (paramType.getTypeArguments().isPresent()) continue; // container types: stay silent
                    TypeDeclaration<?> bodyType = declaredTypes.get(paramType.getNameAsString());
                    if (bodyType == null) continue; // not declared in the file set: stay silent

                    boolean hasConstraints = bodyType.findAll(
                                    com.github.javaparser.ast.expr.AnnotationExpr.class).stream()
                            .anyMatch(a -> constraintAnnotations.contains(a.getNameAsString()));
                    boolean hasTrigger = hasValidationTrigger(p, md);

                    // T3-BX: which OTHER body types DO declare constraints. Naming them turns
                    // "this type has none" into "this type is the only one without", which is the
                    // form the field evidence took: run 2026-07-27_14-42-19 scored 21/23 and its
                    // one status failure was POST /members answering 201, because Member carried no
                    // constraint while its peer Book did. The bare fact leaves the model free to
                    // read the absence as intentional; the comparison does not.
                    List<String> peersWithConstraints = peerBodyTypesDeclaringConstraints(
                            units, declaredTypes, constraintAnnotations, paramType.getNameAsString());
                    String peerEvidence = peersWithConstraints.isEmpty() ? ""
                            : " It is the only request-body type here without them — "
                              + String.join(", ", peersWithConstraints) + " declare them.";

                    String binding = md.findAncestor(TypeDeclaration.class)
                            .map(t -> t.getNameAsString() + "." + md.getNameAsString() + "()")
                            .orElse(md.getNameAsString() + "()");
                    String typeName = bodyType.getNameAsString();
                    if (!hasConstraints && !hasTrigger) {
                        out.add("Request body " + typeName + " bound in " + binding
                                + " has NO declarative validation surface: no field constraint annotations on "
                                + typeName + " and no validation trigger on the parameter. If a requirement"
                                + " expects invalid input to be rejected (e.g. a 400), confirm the method"
                                + " bodies validate manually — no annotation-driven validation exists.");
                    } else if (!hasTrigger) {
                        out.add("Request body " + typeName + " declares field constraints, but " + binding
                                + " binds it without a validation trigger — the declared constraints are not"
                                + " enforced on this binding unless the method bodies validate manually.");
                    } else if (!hasConstraints) {
                        out.add(binding + " requests validation of " + typeName + " but " + typeName
                                + " declares no field constraint annotations — the declarative constraint set"
                                + " is empty; only manual checks in the method bodies (if any) validate it."
                                + peerEvidence);
                    }
                }
            }
        }
    }

    /**
     * T3-BX: request-body types OTHER than {@code excludedType} that declare field constraints.
     *
     * <p>Peers are established by the code itself — a type is a peer when some method binds it as a
     * request body, the same relation the fact above is about. Empty means the comparison carries no
     * information (no other body type declares constraints either), and the caller then reports the
     * absence without claiming it is anomalous.
     */
    private List<String> peerBodyTypesDeclaringConstraints(
            List<CompilationUnit> units, Map<String, TypeDeclaration<?>> declaredTypes,
            Set<String> constraintAnnotations, String excludedType) {
        Set<String> peers = new LinkedHashSet<>();
        for (CompilationUnit u : units) {
            for (MethodDeclaration md : u.findAll(MethodDeclaration.class)) {
                for (Parameter p : md.getParameters()) {
                    if (p.getAnnotations().stream()
                            .noneMatch(a -> REQUEST_BODY_ANNOTATION.equals(a.getNameAsString()))) continue;
                    if (!p.getType().isClassOrInterfaceType()) continue;
                    String name = p.getType().asClassOrInterfaceType().getNameAsString();
                    if (name.equals(excludedType)) continue;
                    TypeDeclaration<?> type = declaredTypes.get(name);
                    if (type == null) continue;
                    boolean declares = type.findAll(com.github.javaparser.ast.expr.AnnotationExpr.class)
                            .stream().anyMatch(a -> constraintAnnotations.contains(a.getNameAsString()));
                    if (declares) peers.add(name);
                }
            }
        }
        return List.copyOf(peers);
    }

    /**
     * T3-BX: the facts that name a statically visible cause of a WRONG RUNTIME STATUS, for the
     * evolution prompt.
     *
     * <p>{@link #analyze} has computed these all along and they are correct — measured on real run
     * output, {@code 2026-07-28_23-16-15} yields <i>"LoanController.createLoan() requests validation
     * of Loan but Loan declares no field constraint annotations"</i>, which is precisely the defect
     * that made {@code POST /loans} answer 500 where the specification asks for 400. But
     * {@code analyze()} goes only to the criteria verifier, and the sole analyzer path into the
     * evolution prompt ({@link #peerAnnotationAnomalies}) returns nothing on those same trees. So the
     * loop could see the cause and never told the agent able to fix it.
     *
     * <p>Deliberately a SUBSET rather than all of {@code analyze()}: stub bodies and unread
     * parameters are real facts but they are not about response statuses, and shipping the whole
     * output would be exactly the raw-payload passthrough the prompt audit removed elsewhere. What
     * qualifies is a fact about the path between an input and its status — the validation surface,
     * and thrown exceptions no mapping covers.
     */
    public List<String> statusRiskFacts(Map<String, String> outputFiles) {
        List<CompilationUnit> units = parseAll(outputFiles);
        if (units.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        collectValidationSurfaceObservations(units, out);
        boolean anyThrow = units.stream().anyMatch(u ->
                !u.findAll(ThrowStmt.class).isEmpty()
                        || u.findAll(MethodCallExpr.class).stream()
                        .anyMatch(c -> "orElseThrow".equals(c.getNameAsString())));
        boolean anyStatusMapping = units.stream().anyMatch(u ->
                u.findAll(com.github.javaparser.ast.expr.AnnotationExpr.class).stream()
                        .anyMatch(a -> STATUS_MAPPING_ANNOTATIONS.contains(a.getNameAsString())));
        // T4-DC: the mapping-free case belongs here too, and its absence cost run
        // 2026-07-30_18-39-00 four smoke checks. That run's LoanService threw RuntimeException for
        // all six domain errors from early on, but this method stayed silent until a
        // GlobalExceptionHandler existed to make anyStatusMapping true — which happened at
        // CYCLE 24 OF 25. The fact was then rendered, correctly, into an evolution prompt whose
        // subgoal was literally "spec-named endpoint(s) fail at runtime: GET /loans answers 500",
        // one cycle before the deadline. It was not ignored; it arrived too late to act on.
        //
        // analyze() has emitted the mapping-free half all along, but analyze() goes to the criteria
        // verifier — the same split T3-BX was filed to close, reappearing one branch deeper in the
        // same method. Both halves now reach the agent that can act, from the first cycle a throw
        // exists rather than from the first cycle a mapping does.
        boolean anyMapping = anyStatusMapping || !catchMappedTypes(units).isEmpty();
        if (anyThrow && !anyMapping) {
            out.add(NO_STATUS_MAPPING_FACT);
        } else if (anyThrow) {
            collectThrowMappingObservations(units, out);
        }
        return out;
    }

    /**
     * T4-DC: exception types a {@code catch} clause turns into a specific response status.
     *
     * <p>An annotation is not the only way to map a failure to a status, and treating it as the
     * only way misreads the best artifact in the corpus. The 23/23 run of {@code 2026-07-24}
     * declares NO advice class and NO {@code @ResponseStatus}: it throws eight named domain types
     * and catches each one in the controller, returning the status beside the logic that raised it.
     * Counting only annotations would report that artifact as having no mapping at all.
     *
     * <p>A catch clause counts only when its body actually yields a status — a catch that logs and
     * swallows maps nothing. Union types ({@code catch (A | B e)}) contribute both names.
     */
    private static Set<String> catchMappedTypes(List<CompilationUnit> units) {
        Set<String> mapped = new LinkedHashSet<>();
        for (CompilationUnit cu : units) {
            for (com.github.javaparser.ast.stmt.CatchClause cc
                    : cu.findAll(com.github.javaparser.ast.stmt.CatchClause.class)) {
                if (!bodyYieldsAStatus(cc)) continue;
                var type = cc.getParameter().getType();
                if (type.isUnionType()) {
                    type.asUnionType().getElements()
                            .forEach(e -> mapped.add(simpleName(e.getElementType().asString())));
                } else {
                    mapped.add(simpleName(type.asString()));
                }
            }
        }
        return mapped;
    }

    /** Whether a catch body names a response status or builds a status-bearing response. */
    private static boolean bodyYieldsAStatus(com.github.javaparser.ast.stmt.CatchClause cc) {
        String body = cc.getBody().toString();
        return body.contains("HttpStatus.")
                || body.contains("notFound(")
                || body.contains("badRequest(")
                || body.contains("status(");
    }

    /** A trigger counts on the parameter itself or as {@code @Validated} on the declaring type. */
    private static boolean hasValidationTrigger(Parameter p, MethodDeclaration md) {
        if (p.getAnnotations().stream()
                .anyMatch(a -> VALIDATION_TRIGGER_ANNOTATIONS.contains(a.getNameAsString()))) return true;
        return md.findAncestor(TypeDeclaration.class)
                .map(t -> ((TypeDeclaration<?>) t).getAnnotations().stream()
                        .anyMatch(a -> VALIDATION_TRIGGER_ANNOTATIONS.contains(a.getNameAsString())))
                .orElse(false);
    }

    private static boolean parameterIsRead(BlockStmt body, String paramName) {
        return body.findAll(NameExpr.class).stream().anyMatch(n -> n.getNameAsString().equals(paramName));
    }

    /** Field names that some code assigns (covers {@code this.f = ...} and {@code f = ...}). */
    private static Set<String> assignedFieldNames(TypeDeclaration<?> type) {
        Set<String> assigned = new LinkedHashSet<>();
        for (AssignExpr a : type.findAll(AssignExpr.class)) {
            var target = a.getTarget();
            if (target.isNameExpr()) {
                assigned.add(target.asNameExpr().getNameAsString());
            } else if (target.isFieldAccessExpr()) {
                assigned.add(target.asFieldAccessExpr().getNameAsString());
            }
        }
        return assigned;
    }

    private static Set<String> collectCalledMethodNames(List<CompilationUnit> units) {
        Set<String> names = new LinkedHashSet<>();
        for (CompilationUnit u : units) {
            for (MethodCallExpr c : u.findAll(MethodCallExpr.class)) {
                names.add(c.getNameAsString());
            }
        }
        return names;
    }
}
