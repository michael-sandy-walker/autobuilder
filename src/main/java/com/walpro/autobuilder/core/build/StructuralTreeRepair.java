package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.analysis.structure.WiringFactsAnalyzer;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalPlannerService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The repairs the loop owns for properties of the TREE, in one place, so that every moment at
 * which the tree can be wrong may act on them.
 *
 * <p><b>Why this class exists (T4-DR).</b> Both repairs were reachable only from a MERGE. That is
 * the right trigger for a defect a merge INTRODUCES, and it is no trigger at all for a defect a
 * run INHERITS: a merge is an event a run may simply never have. Run {@code 2026-09-03_20-25-22}
 * opened on a tree the previous run had left unstartable, said so in its own goal text, rendered
 * the diagnosis eight times, and ran 44 cycles over 35 minutes with zero merges — so the repair
 * for that exact tree, already computed by the analyzer whose sentence the prompt was carrying,
 * was never once entered.
 *
 * <p><b>These assert a property of the tree, not of a diff.</b> "A type that is asked for must be
 * obtainable" and "a type sits with the peers that name it" are true or false whether or not this
 * cycle wrote anything, which is what makes a start-of-run pass meaningful and what made the merge
 * hook insufficient on its own.
 *
 * <p><b>Measured before it was built</b> ({@code scripts/inherited_finding_report.py}, over the
 * whole 39-run corpus at or after the detector's introduction): one run inherited a finding, and
 * exactly two runs carried a finding the merge hook could never act on again — one on each side of
 * {@code T1-FX}. That fix taught the hook to read the merged tree, closing the "arrived on the
 * last merge" end; this closes the "was already there before the first merge" end. Between them
 * there is nothing left, which is the argument for the cheap pass rather than a per-cycle sweep:
 * two whole-tree reads on every cycle of every run would be paid to catch a fact that changed once
 * in thirty-nine runs.
 *
 * <p><b>Order is load-bearing and preserved from the merge path.</b> Registration runs first so a
 * type this pass just repaired is relocated with the repair already in it.
 */
@Component
public class StructuralTreeRepair {

    private static final Logger log = LoggerFactory.getLogger(StructuralTreeRepair.class);

    private final FileService fileService;
    private final GoalPlannerService goalPlannerService;

    /** Absent in slices that do not build the analyzer; both repairs then decline. */
    @Autowired(required = false)
    private WiringFactsAnalyzer wiringFactsAnalyzer;

    public StructuralTreeRepair(FileService fileService, GoalPlannerService goalPlannerService) {
        this.fileService = fileService;
        this.goalPlannerService = goalPlannerService;
    }

    /** Test seam for the analyzer, which is optional at injection. */
    public void setWiringFactsAnalyzer(WiringFactsAnalyzer analyzer) {
        this.wiringFactsAnalyzer = analyzer;
    }

    /**
     * Applies every tree-property repair to the tree at {@code outputPath}, in dependency order.
     *
     * <p>Self-gating throughout: each repair needs the project's own annotated peers to derive
     * what it would add or where it would move, so a project that annotates nothing produces
     * nothing and a tree that is empty produces nothing.
     */
    public void repairTree(String outputPath) {
        applyInjectedTypeRegistrationRepair(outputPath);
        applyPeerPlacementRelocation(outputPath);
    }

    /**
     * Gives a type that must be supplied to a constructor the registration its package peers
     * carry, when it has none.
     *
     * <p>Reads the tree from DISK rather than a cycle's source map, for the same reason the
     * relocation does: the map is a cycle-start snapshot, and on the merge path a project only
     * ever acquires an unregistered injected type from a merge, which lands after that snapshot is
     * taken (T1-FX). On the start-of-run path there is no snapshot to be stale in the first place.
     */
    public void applyInjectedTypeRegistrationRepair(String outputPath) {
        if (wiringFactsAnalyzer == null || outputPath == null) return;
        try {
            Path root = Path.of(outputPath);
            Map<String, String> sources = fileService.readFolderContentsResolved(root);
            if (sources == null || sources.isEmpty()) return;
            var findings = wiringFactsAnalyzer.unregisteredInjectedTypes(sources);
            if (findings.isEmpty()) return;
            for (var repair : InjectedTypeRegistrationRepair.plan(sources, findings)) {
                Path file = root.resolve(repair.path());
                if (!Files.isRegularFile(file)) continue;
                String current = Files.readString(file);
                var repaired = InjectedTypeRegistrationRepair.apply(current, repair);
                if (repaired.isEmpty()) continue;
                Files.writeString(file, repaired.get());
                log.warn("T3-CA: {} is injected but registered nowhere — added @{}, the registration "
                                + "its package peers all carry. {}",
                        repair.typeName(), repair.annotation(), repair.path());
            }
        } catch (Exception e) {
            log.warn("T3-CA: registration repair could not be applied — {}", e.toString());
        }
    }

    /** Moves a type that sits apart from the peers that name it, and re-points what named it. */
    public void applyPeerPlacementRelocation(String outputPath) {
        if (wiringFactsAnalyzer == null || outputPath == null) return;
        try {
            Path root = Path.of(outputPath);
            Map<String, String> sources = fileService.readFolderContentsResolved(root);
            if (sources == null || sources.isEmpty()) return;
            var findings = wiringFactsAnalyzer.peerPlacementFindings(sources);
            if (findings.isEmpty()) return;
            var plan = PeerPlacementRelocation.plan(sources, findings);
            // A refusal must be as visible as a move. The structured path does not go through the
            // analyzer's own announce line (that one serves the prompt), so without this a finding
            // that is made and then declined leaves no trace at all — indistinguishable from a
            // rule that never fired, which is precisely what cost slice 1 its first watch reading.
            for (var finding : findings) {
                boolean planned = plan.stream().anyMatch(r -> r.typeName().equals(finding.typeName()));
                if (!planned) {
                    log.info("T3-BZ: {} sits in {} while its peers sit in {}, but the move was refused "
                                    + "(no entry point declared, a destination outside the entry point's own "
                                    + "subtree, an occupied destination, or no declaring file) — reporting only.",
                            finding.typeName(),
                            finding.actualPackage().isEmpty() ? "the default package" : finding.actualPackage(),
                            finding.majorityPackage());
                }
            }
            for (var relocation : plan) {
                var applied = PeerPlacementRelocation.apply(sources, relocation);
                if (applied.isEmpty()) continue;
                for (var write : applied.get().writes().entrySet()) {
                    Path file = root.resolve(write.getKey());
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, write.getValue());
                }
                Files.deleteIfExists(root.resolve(applied.get().vacatedPath()));
                // The tree is now consistent, but the loop's own bookkeeping still points at the
                // file's old home. Measured in the field: a banked criterion reading "A Java class
                // `Book` exists at .../domain/Book.java" outlived the move that falsified it, and
                // the three runs in which the mover acted all scored below the ceiling while ten
                // of eleven without a move were perfect. Repairing the tree is only half a move.
                goalPlannerService.rewriteRelocatedFileReferences(relocation.fromPath(), relocation.toPath());
                sources = fileService.readFolderContentsResolved(root);
                log.warn("T3-BZ: {} sat in {} while the peers that name it sit in {} — moved it there, "
                                + "rewriting {} file(s). {} -> {}",
                        relocation.typeName(), relocation.fromPackage().isEmpty()
                                ? "the default package" : relocation.fromPackage(),
                        relocation.toPackage(), applied.get().writes().size(),
                        relocation.fromPath(), relocation.toPath());
            }
        } catch (Exception e) {
            log.warn("T3-BZ: placement relocation could not be applied — {}", e.toString());
        }
    }
}
