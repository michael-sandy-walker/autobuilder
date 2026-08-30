package com.walpro.autobuilder.core.build;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.walpro.autobuilder.analysis.structure.WiringFactsAnalyzer.UnregisteredInjectedType;

/**
 * Applies the repair for a type that must be supplied to a constructor but is registered nowhere.
 *
 * <p>WHY THIS EXISTS. The fact was already being detected, correctly and by name. Run
 * {@code 2026-08-30_18-44-37} reported it twenty times — <i>"BookRepository is a constructor
 * parameter of BookController, which is annotated, but BookRepository itself carries no annotation
 * at all — while all 2 other types in its package carry @Repository"</i> — and the artifact still
 * ended the run unstartable, because the only consumer of that sentence was a paragraph in a
 * prompt. The detector's own javadoc cites an EARLIER run killed by the same defect. A diagnosis
 * with no actuator is not a repair, however often it fires.
 *
 * <p>WHAT MAKES IT SAFE TO DO DETERMINISTICALLY. The finding already carries three independent
 * pieces of the project's own evidence (the injection site, the peers' unanimous habit, and the
 * type's empty annotation list), and the repair asks no further question: it gives the outlier the
 * annotation its peers share. Nothing here names a framework or an annotation
 * ([[feedback-no-ecosystem-jargon-in-framework-code]]) — the annotation NAME comes from the peers,
 * and its import is copied from a peer that declares one, so a project with different conventions
 * is repaired in its own vocabulary and a project that annotates nothing produces no finding and
 * no repair.
 */
public final class InjectedTypeRegistrationRepair {

    /**
     * One planned repair. Carries the annotation and the import SPELLING taken from the peers,
     * not the repaired text — the text is produced against whatever the file holds at write time,
     * so a plan made from a slightly stale source map can never clobber a just-merged file.
     */
    public record Repair(String path, String typeName, String annotation, String importLine) {}

    private static final Pattern JAVA_FILE = Pattern.compile(".*\\.java$");

    private InjectedTypeRegistrationRepair() {}

    /**
     * Plans a repair per finding. A finding whose declaring file cannot be located, or whose
     * annotation no peer imports, is skipped rather than guessed at — an invented import is a
     * compile error, which is strictly worse than the inert type the loop already has.
     */
    public static List<Repair> plan(Map<String, String> sources, List<UnregisteredInjectedType> findings) {
        if (sources == null || sources.isEmpty() || findings == null || findings.isEmpty()) return List.of();
        Map<String, Repair> byPath = new LinkedHashMap<>();
        for (UnregisteredInjectedType f : findings) {
            Optional<String> path = declaringFile(sources, f.typeName());
            if (path.isEmpty() || byPath.containsKey(path.get())) continue;
            Optional<String> importLine = peerImportFor(sources, f);
            if (importLine.isEmpty()) continue;
            byPath.put(path.get(), new Repair(path.get(), f.typeName(), f.missingAnnotation(), importLine.get()));
        }
        return List.copyOf(byPath.values());
    }

    /**
     * Produces the repaired text from the file's CURRENT content. Empty when the annotation is
     * already there (so re-running is inert) or the declaration is no longer present (so a file
     * rewritten since the plan was made is left alone).
     */
    public static Optional<String> apply(String currentSource, Repair r) {
        return annotate(currentSource, r.typeName(), r.annotation(), r.importLine());
    }

    /** The file whose content declares the type, by the source map's own keys. */
    private static Optional<String> declaringFile(Map<String, String> sources, String typeName) {
        Pattern declaration = Pattern.compile(
                "\\b(class|interface|record|enum)\\s+" + Pattern.quote(typeName) + "\\b");
        return sources.entrySet().stream()
                .filter(e -> e.getKey() != null && JAVA_FILE.matcher(e.getKey()).matches())
                .filter(e -> e.getValue() != null && declaration.matcher(e.getValue()).find())
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /**
     * The import a peer uses for the shared annotation. Taken from the peers named in the finding
     * — they are the types whose habit made this a finding at all, so they are the right authority
     * for how the annotation is spelled in this project.
     */
    private static Optional<String> peerImportFor(Map<String, String> sources, UnregisteredInjectedType f) {
        Pattern imp = Pattern.compile("^\\s*import\\s+[\\w.]+\\." + Pattern.quote(f.missingAnnotation()) + "\\s*;",
                Pattern.MULTILINE);
        for (String peer : f.peers()) {
            Optional<String> peerPath = declaringFile(sources, peer);
            if (peerPath.isEmpty()) continue;
            Matcher m = imp.matcher(sources.get(peerPath.get()));
            if (m.find()) return Optional.of(m.group().strip());
        }
        return Optional.empty();
    }

    /** Inserts the annotation above the type declaration and the import after the package line. */
    private static Optional<String> annotate(String source, String typeName, String annotation, String importLine) {
        if (source == null || source.isBlank()) return Optional.empty();
        if (source.contains("@" + annotation)) return Optional.empty();
        // Modifiers on the declaration's OWN line only. Letting the group span whitespace made it
        // start at the blank line above, so the annotation was inserted there and separated from
        // the type by an empty line — valid, but not what "annotate this declaration" means.
        Matcher decl = Pattern.compile(
                "(?m)^([ \\t]*)((?:(?:public|final|abstract|sealed|non-sealed)[ \\t]+)*)"
                        + "(class|interface|record|enum)[ \\t]+"
                        + Pattern.quote(typeName) + "\\b").matcher(source);
        if (!decl.find()) return Optional.empty();
        String indent = decl.group(1);
        String out = source.substring(0, decl.start())
                + indent + "@" + annotation + "\n"
                + source.substring(decl.start());
        if (!out.contains(importLine)) {
            Matcher pkg = Pattern.compile("(?m)^package\\s+[\\w.]+\\s*;\\s*$").matcher(out);
            if (pkg.find()) {
                out = out.substring(0, pkg.end()) + "\n\n" + importLine + out.substring(pkg.end());
            } else {
                out = importLine + "\n" + out;
            }
        }
        return Optional.of(out);
    }
}
