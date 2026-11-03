package com.walpro.autobuilder.core.init;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.AutobuilderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * What is available to start, and which modes each one supports.
 *
 * <p><b>Why a catalogue and not just a path.</b> Choosing a start mode is only half a decision; the
 * other half is what to start it ON. Targets sit side by side under the targets directory and are
 * not interchangeable: one carries a specification and can be begun fresh, another carries a
 * lineage and can be continued, a third is somebody else's code and can only be adopted. Until now
 * telling them apart meant listing directories by hand and knowing what each subdirectory implied.
 *
 * <p>The modes reported per target come from the same rules {@link StartModeService} enforces at
 * startup, so what this offers and what a run will accept cannot drift apart.
 */
@Slf4j
@Service
public class TargetCatalog {

    /**
     * One startable target.
     *
     * @param name           the directory name, which is what {@code PREFLIGHT_TARGET} takes
     * @param hasSeed        it carries {@code bootstrap/seed.txt}, so a project can be begun from it
     * @param seedSummary    the first meaningful line of that specification, to tell targets apart
     * @param hasYardstick   it declares how it is scored
     * @param situation      what is in its output tree
     * @param sourceFiles    how many source files that tree holds
     * @param priorRunState  leftovers from earlier runs, named — mutations, snapshots, checkpoints
     * @param supportedModes the modes this target can actually be started in
     * @param lastEnd        how the last run that wrote an end report ended, or null when none did
     * @param pendingImports T4-ED: what was brought in and waits for the operator to keep it or take it back out
     * @param imports        T4-ED: how many projects were brought in and not taken back out, kept or pending
     */
    public record Target(String name, boolean hasSeed, String seedSummary, boolean hasYardstick,
                         StartModeService.Situation situation, int sourceFiles,
                         List<String> priorRunState, List<StartMode> supportedModes, LastEnd lastEnd,
                         List<String> pendingImports, int imports) {}

    /**
     * T4-ED: the last end report's reason and time, from {@code meta/run-end-report.json}. A run stopped from
     * outside writes none, so this can be older than the latest run; the time says which run it was.
     *
     * @param reason     the terminal reason as the loop wrote it, e.g. {@code goal-fulfilled}
     * @param at         when the report was written
     * @param fulfilled  the goal was reached -- continuing such a project has nothing left to do
     */
    public record LastEnd(String reason, String at, boolean fulfilled) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Subdirectories that mean a run has already happened here. */
    private static final List<String> RUN_STATE_DIRS =
            List.of("meta", "mutations", "snapshots", "checkpoints", "checkpoint-archive", "branches", "logs");

    @Value("${file.base-path}")
    private String fileBasePath;

    /** Every target beside the configured one, newest name order, with what each supports. */
    public List<Target> targets() {
        Path here = Path.of(fileBasePath).toAbsolutePath().normalize();
        return targetsUnder(here.getParent());
    }

    /** Every target directly under {@code root} -- T4-ED: the home screen lists them before any is bound. */
    public List<Target> targetsUnder(Path root) {
        List<Target> out = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) return out;
        try (Stream<Path> children = Files.list(root)) {
            for (Path dir : children.filter(Files::isDirectory).sorted().toList()) {
                out.add(describe(dir));
            }
        } catch (IOException e) {
            log.warn("T4-DP: could not list targets under {}: {}", root, e.getMessage());
        }
        return out;
    }

    private Target describe(Path dir) {
        boolean hasSeed = Files.isRegularFile(dir.resolve("bootstrap/seed.txt"));
        boolean hasYardstick = Files.isRegularFile(dir.resolve("bootstrap/yardstick"));
        int sources = countSources(dir.resolve("output"));
        // The same judgement the startup check makes. These were two rules for one question and
        // they disagreed about the same tree, which is worse than having neither.
        StartModeService.Situation situation = StartModeService.Situation.of(dir);

        List<String> priorState = new ArrayList<>();
        for (String d : RUN_STATE_DIRS) {
            // A directory the loop created and never filled is not an earlier run.
            if (StartModeService.Situation.anyFileUnder(dir.resolve(d))) priorState.add(d);
        }

        // Kept in step with StartModeService.decide() on purpose: a target offered here that the
        // startup check would then refuse is worse than not offering it.
        List<StartMode> modes = new ArrayList<>();
        // NEW_PROJECT needs something to build FROM, which is why a seedless target cannot offer it.
        if (hasSeed && situation != StartModeService.Situation.FOREIGN) modes.add(StartMode.NEW_PROJECT);
        if (situation == StartModeService.Situation.LOOP_AUTHORED) modes.add(StartMode.CONTINUE_LINEAGE);
        if (situation != StartModeService.Situation.EMPTY) modes.add(StartMode.ADOPT_PROJECT);

        return new Target(dir.getFileName().toString(), hasSeed, firstMeaningfulLine(dir), hasYardstick,
                situation, sources, priorState, modes, lastEnd(dir), pendingImports(dir), imports(dir));
    }

    /**
     * The imports in {@code meta/inputs.json} still open (neither kept nor withdrawn), by what was brought in. Read from
     * the file the ledger writes beside the graph, so it is known before any run is up.
     */
    private List<String> pendingImports(Path dir) {
        Path ledger = dir.resolve(com.walpro.autobuilder.goal.purpose.PurposeService.PATH);
        if (!Files.isRegularFile(ledger)) return List.of();
        try {
            JsonNode events = MAPPER.readTree(ledger.toFile()).path("events");
            java.util.Set<String> withdrawn = new java.util.HashSet<>();
            for (JsonNode e : events) {
                if (com.walpro.autobuilder.goal.purpose.InputEvent.WITHDRAWAL.equals(e.path("kind").asText())) {
                    withdrawn.add(e.path("inputId").asText());
                }
            }
            List<String> out = new ArrayList<>();
            for (JsonNode e : events) {
                if (com.walpro.autobuilder.goal.purpose.InputEvent.IMPORT.equals(e.path("kind").asText())
                        && com.walpro.autobuilder.goal.purpose.InputEvent.OPEN.equals(e.path("status").asText())
                        && !withdrawn.contains(e.path("inputId").asText())) {
                    out.add(e.path("subject").asText());
                }
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Projects brought in (inputs of kind project) and not withdrawn, from {@code meta/inputs.json}. */
    private int imports(Path dir) {
        Path ledger = dir.resolve(com.walpro.autobuilder.goal.purpose.PurposeService.PATH);
        if (!Files.isRegularFile(ledger)) return 0;
        try {
            JsonNode doc = MAPPER.readTree(ledger.toFile());
            java.util.Set<String> withdrawn = new java.util.HashSet<>();
            for (JsonNode e : doc.path("events")) {
                if (com.walpro.autobuilder.goal.purpose.InputEvent.WITHDRAWAL.equals(e.path("kind").asText())) {
                    withdrawn.add(e.path("inputId").asText());
                }
            }
            int n = 0;
            for (JsonNode i : doc.path("inputs")) {
                if (com.walpro.autobuilder.goal.purpose.InputEntry.KIND_PROJECT.equals(i.path("kind").asText())
                        && !withdrawn.contains(i.path("id").asText())) n++;
            }
            return n;
        } catch (IOException e) {
            return 0;
        }
    }

    private LastEnd lastEnd(Path dir) {
        Path report = dir.resolve("meta/run-end-report.json");
        if (!Files.isRegularFile(report)) return null;
        try {
            JsonNode r = MAPPER.readTree(report.toFile());
            String reason = r.path("terminalReason").asText("");
            return new LastEnd(reason, r.path("writtenAt").asText(null), AutobuilderService.GOAL_FULFILLED_REASON.equals(reason));
        } catch (IOException e) {
            return null;
        }
    }

    /** Enough of the specification to tell one target from another, never the whole of it. */
    private String firstMeaningfulLine(Path dir) {
        Path seed = dir.resolve("bootstrap/seed.txt");
        if (!Files.isRegularFile(seed)) return null;
        try {
            for (String line : Files.readAllLines(seed)) {
                String t = line.strip();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    return t.length() > 140 ? t.substring(0, 139) + "…" : t;
                }
            }
        } catch (IOException e) {
            return "(seed present but unreadable: " + e.getMessage() + ")";
        }
        return "(seed present but empty)";
    }

    private int countSources(Path output) {
        if (!Files.isDirectory(output)) return 0;
        try (Stream<Path> walk = Files.walk(output)) {
            return (int) walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String s = p.toString();
                        return !s.contains("/target/") && (s.endsWith(".java") || s.endsWith(".xml")
                                || s.endsWith(".properties") || s.endsWith(".yml") || s.endsWith(".md"));
                    })
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** The catalogue as a person reads it. */
    public String render() {
        List<Target> targets = targets();
        if (targets.isEmpty()) return "No targets found beside " + fileBasePath + "\n";
        StringBuilder b = new StringBuilder("STARTABLE TARGETS beside ")
                .append(Path.of(fileBasePath).toAbsolutePath().normalize().getParent()).append("\n\n");
        for (Target t : targets) {
            b.append("  ").append(t.name()).append('\n');
            b.append("      tree          ").append(t.situation()).append(", ")
             .append(t.sourceFiles()).append(" source file(s)\n");
            b.append("      specification ")
             .append(t.hasSeed() ? "yes" : "none — cannot be begun fresh").append('\n');
            if (t.seedSummary() != null) b.append("        | ").append(t.seedSummary()).append('\n');
            b.append("      yardstick     ").append(t.hasYardstick() ? "declared" : "none").append('\n');
            if (!t.priorRunState().isEmpty()) {
                b.append("      earlier runs  ").append(String.join(", ", t.priorRunState())).append('\n');
            }
            b.append("      can start as  ").append(t.supportedModes().isEmpty()
                    ? "(nothing: no specification and no code)"
                    : t.supportedModes().toString()).append('\n');
        }
        return b.toString();
    }
}
