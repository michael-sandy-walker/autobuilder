package com.walpro.autobuilder.protocol.evolution;

import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.MissingTypeExtractor;
import com.walpro.autobuilder.core.build.inject.InjectorFixRecord;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T1-FR: formatter for the {@code --- MISSING TYPE — CREATE IN THIS PROPOSAL ---} evolution prompt
 * block. The class-side counterpart of {@link MissingMemberPromptBlock}.
 *
 * <p>Why this exists. A missing member is classified as an author error and relayed to the model;
 * a missing TYPE is classified as deferrable and routed to the compile-fix injector, which stubs it
 * silently. So the model is never told that it referenced a type nothing defines — it re-proposes
 * the caller, the injector stubs the callee again, and the pair never converges. Measured over the
 * run corpora on 2026-08-02: this is the larger half, 2346 missing-type diagnostics against 905
 * missing-member ones, and it is concentrated on whichever resource the plan reaches last.
 *
 * <p><b>Deciding which tokens are ours to write.</b> The compiler says "cannot find symbol"; it does
 * not say whose symbol it is. Four filters, each measured against the corpora, and each removing a
 * class of token that would otherwise produce a wrong instruction:
 *
 * <ol>
 *   <li><b>A file already defines it</b> (974 of 2346) — then it is not missing; some other error
 *       is in play and {@link MissingMemberPromptBlock} or the compile-error relay owns it.</li>
 *   <li><b>An import declares it from OUTSIDE the project's own package</b> (436) — the source
 *       says which library the type comes from, so the gap is a dependency, not a file to write.
 *       An import pointing back into the tree's own root does NOT count: the model routinely
 *       writes {@code import com.example.demo.model.Loan;} for a type it has not created yet
 *       (field-observed, run 2026-08-01_01-10-45, where javac answered
 *       {@code location: package com.example.demo.model}), and treating that as library evidence
 *       would filter out exactly the case this block exists for.</li>
 *   <li><b>The injector resolved it as an import</b> (239) — the mechanism that decides
 *       importable-vs-not has already ruled on this token; asking it is better than re-deciding.
 *       This is what removes {@code Valid}, {@code Pattern}, {@code NotBlank}.</li>
 *   <li><b>It is used as an annotation</b> (50) — {@code @Column}, {@code @GeneratedValue}. A
 *       project may define its own annotations, so this filter can cost a true positive; the
 *       failure mode is silence, which is where this block started.</li>
 * </ol>
 *
 * <p>What survives is 647 diagnostics naming the project's own exception types, services and
 * repositories — the types the model referenced and never wrote.
 */
public final class MissingTypePromptBlock {

    private static final Pattern IMPORT_LINE = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?([\\w.]*?)\\.(\\w+)\\s*;", Pattern.MULTILINE);
    private static final Pattern PACKAGE_LINE = Pattern.compile(
            "^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern ANNOTATION_USE = Pattern.compile("@([A-Z]\\w*)");

    private MissingTypePromptBlock() {}

    /**
     * javac reports absolute host paths. The prompt should carry the path the model uses when it
     * writes a file, and nothing about where this machine keeps its working tree — field-observed
     * 2026-08-02, where the first firing rendered
     * {@code /home/…/firestarter/output/src/main/java/…/LoanService.java}.
     */
    private static String projectRelative(String path) {
        int src = path.indexOf("src/main/java/");
        if (src < 0) src = path.indexOf("src/test/java/");
        return src < 0 ? path : path.substring(src);
    }

    /**
     * The tree's own namespace root, as the first two segments shared by every package it declares.
     * Two is not a tuning knob: a Java package root is a reversed domain name, so the organisation
     * lives in segments one and two ({@code com.example}, {@code org.acme}). Taking the whole
     * common prefix instead would fail the single-file case — one file declaring
     * {@code com.example.demo.controller} would make a sibling {@code …demo.model} import look
     * external, which is the bug this method was extracted to fix.
     */
    private static String reverseDomainRoot(Set<String> packages) {
        String common = null;
        for (String pkg : packages) {
            if (common == null) {
                common = pkg;
                continue;
            }
            String[] a = common.split("\\."), b = pkg.split("\\.");
            int i = 0;
            while (i < a.length && i < b.length && a[i].equals(b[i])) i++;
            common = String.join(".", java.util.Arrays.copyOfRange(a, 0, i));
        }
        if (common == null || common.isBlank()) return null;
        String[] parts = common.split("\\.");
        return parts.length <= 2 ? common : parts[0] + "." + parts[1];
    }

    public static String render(CycleContextData ctx) {
        if (ctx == null) return null;

        BuildOutcome probe = ctx.lastProbeOutcome();
        if (probe == null
                || probe.result() != BuildResult.COMPILE_ERROR
                || probe.stderrTail() == null) {
            return null;
        }

        List<MissingTypeExtractor.Entry> entries =
                MissingTypeExtractor.extract(probe.stderrTail());
        if (entries.isEmpty()) return null;

        Map<String, String> previousOutput = ctx.previousOutput();
        if (previousOutput == null || previousOutput.isEmpty()) return null;

        Set<String> defined = new HashSet<>();
        Map<String, String> importedFrom = new java.util.HashMap<>();
        Set<String> annotations = new HashSet<>();
        Set<String> packages = new HashSet<>();
        for (Map.Entry<String, String> file : previousOutput.entrySet()) {
            String path = file.getKey();
            if (path != null && path.endsWith(".java")) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                defined.add(name.substring(0, name.length() - ".java".length()));
            }
            String body = file.getValue();
            if (body == null) continue;
            Matcher pm = PACKAGE_LINE.matcher(body);
            if (pm.find()) packages.add(pm.group(1));
            Matcher im = IMPORT_LINE.matcher(body);
            while (im.find()) importedFrom.putIfAbsent(im.group(2), im.group(1));
            Matcher am = ANNOTATION_USE.matcher(body);
            while (am.find()) annotations.add(am.group(1));
        }

        String ownRoot = reverseDomainRoot(packages);

        Set<String> injectorImported = new HashSet<>();
        List<InjectorFixRecord> fixes = ctx.injectorFixes();
        if (fixes != null) {
            for (InjectorFixRecord fix : fixes) {
                if (fix == null || fix.rule() == null || fix.missingToken() == null) continue;
                if (fix.rule().startsWith("symbol-import")) injectorImported.add(fix.missingToken());
            }
        }

        List<MissingTypeExtractor.Entry> ours = new ArrayList<>();
        for (MissingTypeExtractor.Entry e : entries) {
            String type = e.typeName();
            if (defined.contains(type)) continue;
            // An import is evidence a LIBRARY supplies the type — unless it points back into the
            // project's own tree. The model routinely writes `import com.example.demo.model.Loan;`
            // for a type it has not created yet (field-observed, run 2026-08-01_01-10-45, where
            // javac answered `location: package com.example.demo.model`). Treating that as library
            // evidence would filter out exactly the case this block exists for.
            String from = importedFrom.get(type);
            if (from != null && !(ownRoot != null && from.startsWith(ownRoot))) continue;
            if (injectorImported.contains(type)) continue;
            if (annotations.contains(type)) continue;
            ours.add(e);
        }
        if (ours.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("--- MISSING TYPE — CREATE IN THIS PROPOSAL ---\n");
        sb.append("The previous compile probe failed because the following ")
          .append(ours.size() == 1 ? "type is" : "types are")
          .append(" referenced by the code but no file in the project defines ")
          .append(ours.size() == 1 ? "it" : "them")
          .append(". ")
          .append("No library supplies ")
          .append(ours.size() == 1 ? "it" : "them")
          .append(" — ")
          .append(ours.size() == 1 ? "it belongs" : "they belong")
          .append(" to this project. Create ")
          .append(ours.size() == 1 ? "it" : "them")
          .append(" in THIS proposal, in the same package as the file that references ")
          .append(ours.size() == 1 ? "it" : "them")
          .append(", alongside whatever else the subgoal needs. Do NOT remove the reference to ")
          .append("work around the gap.\n");
        for (MissingTypeExtractor.Entry e : ours) {
            sb.append("  - ").append(e.typeName());
            if (e.referencedBy() != null) {
                sb.append(" — referenced by ").append(projectRelative(e.referencedBy()));
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }
}
