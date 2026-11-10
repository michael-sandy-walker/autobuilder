package com.walpro.autobuilder.core.coldread;

import com.walpro.autobuilder.core.build.CriterionExercises;
import com.walpro.autobuilder.core.coldread.ClaimProposer.Claim;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Account;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Feature;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.JudgedClaim;
import com.walpro.autobuilder.core.coldread.entry.EntryKind;
import com.walpro.autobuilder.core.coldread.entry.LibraryStyleAdapter;
import com.walpro.autobuilder.core.merge.ProjectMerge;
import com.walpro.autobuilder.goal.GoalPlannerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T4-EK slice (b): an account, and the seed the operator confirmed from it, made into a project the loop can carry on:
 * ledger entry 1, a root goal, one COMPLETED goal per feature carrying the criteria that earned it, and an ACTIVE
 * requirement for every line the operator added that no claim supports.
 *
 * <p>The root stays open: the loop closes it through its own closure edge, which exercises the criteria -- each one by
 * the exercise that earned it ({@link CriterionExercises}; a library's claims as tests in the project's own suite). A
 * project the loop did not build is thereby checked by the loop exactly as one it built, and reaches GOAL_FULFILLED
 * only if the tree still bears out what was found.
 */
@Slf4j
@Service
public class ReverseEngineeringConfirmation {

    static final String TEST_PREFIX = "Earned";
    /** Who gave a seed confirmed from a reverse-engineered account, as ledger entry 1 records it. */
    public static final String ADDED_BY = "reverse-engineered";

    private final GoalPlannerService goals;
    private final CriterionExercises exercises;
    private final LibraryStyleAdapter library;
    private final Path base;

    public ReverseEngineeringConfirmation(GoalPlannerService goals, CriterionExercises exercises, LibraryStyleAdapter library,
                                          @Value("${file.base-path}") String fileBasePath) {
        this.goals = goals;
        this.exercises = exercises;
        this.library = library;
        this.base = Path.of(fileBasePath);
    }

    /** What confirming made: the root, the earned goals in order, the operator's open requirements. */
    public record Made(String root, List<String> earned, List<String> open, int criteria) {
    }

    public Made confirm(Account account, String confirmed) throws IOException {
        if (goals.getRootGoal() != null) {
            throw new IllegalStateException("this run already has a root goal; a project's first purpose is set once");
        }
        Set<String> featureNames = new LinkedHashSet<>();
        account.features().forEach(f -> featureNames.add(f.name()));
        List<SeedComposer.Line> lines = SeedComposer.lines(confirmed, featureNames);
        Set<String> kept = SeedComposer.keptIds(account);
        for (SeedComposer.Line l : lines) {
            List<String> unknown = l.cites().stream().filter(c -> !kept.contains(c)).toList();
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("\"" + l.text() + "\" cites " + String.join(", ", unknown)
                        + ", which is not a kept claim");
            }
        }
        if (lines.stream().noneMatch(l -> l.kind().equals(SeedComposer.Line.CITED))) {
            throw new IllegalArgumentException("the seed has no line resting on a kept claim");
        }

        Map<String, List<Claim>> criteriaByFeature = criteria(account);
        List<CriterionExercises.Stored> stored = new ArrayList<>();
        Map<Claim, String> textOf = new LinkedHashMap<>();
        Set<String> used = new LinkedHashSet<>();
        for (List<Claim> claims : criteriaByFeature.values()) {
            for (Claim k : claims) {
                // The text keys the stored exercise, so two claims worded alike must not share it.
                String text = used.add(k.text()) ? k.text() : k.text() + " (" + k.id() + ")";
                used.add(text);
                textOf.put(k, text);
                if (k.kind() == EntryKind.SERVICE_STYLE) {
                    stored.add(new CriterionExercises.Stored(text, k.kind().label(), k.sequence(), k.expected(),
                            Map.of(), List.of(), null));
                } else if (k.kind() == EntryKind.COMMAND_STYLE) {
                    stored.add(new CriterionExercises.Stored(text, k.kind().label(), List.of(), k.expected(),
                            k.run().files(), k.run().args(), k.run().outputContains()));
                }
            }
        }
        // Written before any goal: completing a goal may already exercise its criteria.
        exercises.write(stored);
        writeClaimTests(criteriaByFeature);

        String seed = seedText(lines);
        Files.createDirectories(base.resolve("bootstrap"));
        Files.writeString(base.resolve("bootstrap/seed.txt"), seed);
        String root = "goal-" + UUID.randomUUID();
        goals.addInitialGoal(root, seed, ADDED_BY);

        List<String> earned = new ArrayList<>();
        int criteria = 0;
        for (Feature f : SeedComposer.ordered(account.features())) {
            List<Claim> claims = criteriaByFeature.getOrDefault(f.name(), List.of());
            if (claims.isEmpty()) continue;
            List<String> featureLines = lines.stream()
                    .filter(l -> l.kind().equals(SeedComposer.Line.CITED) && f.name().equals(l.feature()))
                    .map(SeedComposer.Line::text).toList();
            String description = f.name() + " -- " + (featureLines.isEmpty() ? String.join(", ", f.entryPoints())
                    : String.join("; ", featureLines));
            String id = "goal-" + UUID.randomUUID();
            goals.addEarnedGoal(id, description, root, claims.stream().map(textOf::get).toList(),
                    "reverse-engineered: " + claims.size() + " criteria held on the tree as it was imported and stopped "
                            + "holding with " + f.name() + "'s ways in taken out");
            earned.add(id);
            criteria += claims.size();
        }
        List<String> open = new ArrayList<>();
        for (SeedComposer.Line l : lines) {
            if (!l.kind().equals(SeedComposer.Line.UNCITED)) continue;
            String id = "goal-" + UUID.randomUUID();
            goals.addSubGoal(id, l.text(), false, root, "operator", null);
            open.add(id);
        }
        log.warn("T4-EK: confirmed -- root {}, {} earned goal(s) with {} criteria, {} open requirement(s) from the operator",
                root, earned.size(), criteria, open.size());
        return new Made(root, earned, open, criteria);
    }

    /**
     * The kept claims by feature, without repeats: two claims with the same exercise are one criterion (the values the
     * proposer made unique to each claim -- {@code 100000n} -- do not make them different).
     */
    static Map<String, List<Claim>> criteria(Account account) {
        Map<String, List<Claim>> out = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JudgedClaim j : account.claims()) {
            if (j.byFeature() == null || !"anchored".equals(j.byFeature().verdict())) continue;
            Claim k = j.claim();
            if (!seen.add(signature(k))) continue;
            out.computeIfAbsent(j.byFeature().feature(), f -> new ArrayList<>()).add(k);
        }
        return out;
    }

    static String signature(Claim k) {
        String exercise = switch (k.kind()) {
            case SERVICE_STYLE -> k.sequence() + " -> " + k.expected();
            case COMMAND_STYLE -> k.run().files() + " " + k.run().args() + " -> " + k.expected() + " " + k.run().outputContains();
            case LIBRARY_STYLE -> k.test();
        };
        return k.kind() + ":" + exercise.replaceAll("100\\d{4}", "#");
    }

    /** The seed as it is kept: the confirmed text without the claim ids (they live with the goals' criteria). */
    static String seedText(List<SeedComposer.Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (SeedComposer.Line l : lines) {
            switch (l.kind()) {
                case SeedComposer.Line.HEADER -> sb.append('\n').append(l.text()).append(":\n");
                case SeedComposer.Line.QUOTE -> sb.append(l.text()).append('\n');
                case SeedComposer.Line.PURPOSE -> sb.append("What it is: ").append(l.text()).append('\n');
                default -> sb.append("- ").append(l.text()).append('\n');
            }
        }
        return sb.toString().strip() + "\n";
    }

    /** A library's claims join the project's own tests, so the test gate runs them as the project's suite. */
    private void writeClaimTests(Map<String, List<Claim>> criteriaByFeature) throws IOException {
        List<Claim> tests = criteriaByFeature.values().stream().flatMap(List::stream)
                .filter(k -> k.kind() == EntryKind.LIBRARY_STYLE).toList();
        if (tests.isEmpty()) return;
        Path output = base.resolve("output");
        var points = library.find(ProjectMerge.files(output), null);
        String pkg = LibraryStyleAdapter.homePackage(points);
        String imports = LibraryStyleAdapter.imports(points, pkg);
        for (Claim k : tests) {
            String cls = TEST_PREFIX + Character.toUpperCase(k.id().charAt(0)) + k.id().substring(1) + "Test";
            Path file = output.resolve("src/test/java").resolve(pkg.replace('.', '/')).resolve(cls + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, library.source(pkg, imports, cls, k.test()));
        }
    }
}
