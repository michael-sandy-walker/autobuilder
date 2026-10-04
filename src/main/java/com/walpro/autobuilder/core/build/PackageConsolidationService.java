package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-BL (lighter fix): consolidates a fragmenting write to the package its type already lives in,
 * instead of letting the {@link PackageFragmentationCheck} gate reject it at probe time.
 *
 * <p>The recurring failure this resolves: evolution proposes re-writing an existing type into a
 * layer sub-package (e.g. {@code Loan.java} → {@code model/Loan.java}). The T4-BH gate rejects the
 * duplicate, the mutation never merges, the subgoal stays open, and later mutations reference a
 * package that never came to exist. Consolidation keeps the LLM's <i>content</i> but places it
 * where the type already lives: the path is rewritten to the existing location, the
 * {@code package} declaration is rewritten to the existing package, and cross-references to the
 * attempted package (imports and fully-qualified usages) are rewritten in every mutation of the
 * same set.
 *
 * <p>Applied to the evolution outcome <b>upstream of the probe</b> (from
 * {@link com.walpro.autobuilder.agent.evolution.EvolutionLLMAdapter}) because
 * {@code CycleContextData} is immutable and a probe-local rewrite would not reach the later merge.
 * The T4-BH gate stays in place as a backstop for everything this deliberately skips:
 * <ul>
 *   <li>conflicts whose existing location is the default package (no declaration to rewrite to),</li>
 *   <li>mutation sets that also write the existing path directly (ambiguous intent),</li>
 *   <li>the deferred-mutation restage path (not routed through here),</li>
 *   <li>everything, when {@code autobuilder.evolution.package-consolidation.enabled} is false.</li>
 * </ul>
 *
 * <p>Known residual: a moved file that references a <i>new</i> same-mutation type by simple name
 * (one that stays in the attempted package because it exists nowhere yet) ends up in a different
 * package than that type without an import. The probe catches this; the mutation is rejected
 * exactly as it would have been without consolidation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PackageConsolidationService {

    private final PackageFragmentationCheck packageFragmentationCheck;

    /** Optional behaviour trace logger — emits {@code package_consolidation_applied} per moved file. */
    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    private static final Pattern PACKAGE_DECLARATION = Pattern.compile("(?m)^\\s*package\\s+[\\w.]+\\s*;");

    /**
     * Returns the outcome with every consolidatable fragmenting write rewritten to its type's
     * existing location, or the outcome unchanged when disabled or nothing fragments.
     */
    public EvolutionOutcome consolidate(EvolutionOutcome outcome, String outputPath, String cycleName) {
        if (outcome == null) return outcome;
        List<FileMutation> mutations = outcome.addedOrChangedFiles();
        if (mutations == null || mutations.isEmpty()) return outcome;

        Map<String, String> proposed = new LinkedHashMap<>();
        for (FileMutation m : mutations) {
            if (m != null && m.path() != null && m.content() != null) proposed.put(m.path(), m.content());
        }
        List<PackageFragmentationCheck.Conflict> conflicts =
                packageFragmentationCheck.findAll(proposed, outputPath);
        if (conflicts.isEmpty()) return outcome;

        Map<String, PackageFragmentationCheck.Conflict> byCandidatePath = new LinkedHashMap<>();
        for (PackageFragmentationCheck.Conflict c : conflicts) {
            if (packageOf(c.existingPath()).isEmpty()) continue;      // default-package target: leave to the gate
            if (proposed.containsKey(c.existingPath())) continue;     // set also writes the existing path: leave to the gate
            byCandidatePath.put(c.candidatePath(), c);
        }
        if (byCandidatePath.isEmpty()) return outcome;

        List<FileMutation> rewritten = new ArrayList<>(mutations.size());
        for (FileMutation m : mutations) {
            if (m == null || m.path() == null || m.content() == null) {
                rewritten.add(m);
                continue;
            }
            String path = m.path();
            String content = m.content();
            PackageFragmentationCheck.Conflict conflict = byCandidatePath.get(path);
            if (conflict != null) {
                path = conflict.existingPath();
                content = rewritePackageDeclaration(content, packageOf(conflict.existingPath()));
            }
            for (PackageFragmentationCheck.Conflict c : byCandidatePath.values()) {
                String attempted = packageOf(c.candidatePath());
                if (attempted.isEmpty()) continue;
                String from = attempted + "." + c.simpleName();
                String to = packageOf(c.existingPath()) + "." + c.simpleName();
                if (!from.equals(to)) content = content.replace(from, to);
            }
            rewritten.add(new FileMutation(path, content));
        }

        for (PackageFragmentationCheck.Conflict c : byCandidatePath.values()) {
            log.info("T4-BL: consolidated fragmenting write — type {} stays in {} (attempted {})",
                    c.simpleName(), packageOf(c.existingPath()), c.candidatePath());
            if (behaviourLogger != null) {
                behaviourLogger.packageConsolidationApplied(
                        cycleName, c.simpleName(), c.candidatePath(), c.existingPath());
            }
        }
        return outcome.withAddedOrChangedFiles(List.copyOf(rewritten));
    }

    /**
     * Replaces the first {@code package …;} declaration with {@code package <target>;}, or
     * prepends one when the content has none.
     */
    static String rewritePackageDeclaration(String content, String targetPackage) {
        Matcher m = PACKAGE_DECLARATION.matcher(content);
        if (m.find()) {
            return content.substring(0, m.start()) + "package " + targetPackage + ";" + content.substring(m.end());
        }
        return "package " + targetPackage + ";\n\n" + content;
    }

    /**
     * Derives the dotted package name from a source-file path, stripping a leading
     * {@code src/main/java/} or {@code src/test/java/} root when present. Returns {@code ""}
     * for the default package or an unrecognisable path.
     */
    static String packageOf(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        String dir = slash >= 0 ? path.substring(0, slash) : "";
        for (String root : List.of("src/main/java/", "src/test/java/")) {
            int idx = dir.indexOf(root);
            if (idx >= 0) {
                dir = dir.substring(idx + root.length());
                break;
            }
        }
        // A path with no recognisable source root and no package-like directory structure
        // (e.g. "model/Loan.java" relative to a source root we can't see) still maps cleanly;
        // only a bare filename is the default package.
        return dir.replace('/', '.');
    }
}
