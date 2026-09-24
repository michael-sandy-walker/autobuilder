package com.walpro.autobuilder.core.mutation;

import com.walpro.autobuilder.core.build.BuildErrorClassifier;
import com.walpro.autobuilder.core.build.MissingMemberExtractor;
import com.walpro.autobuilder.core.build.inject.WellKnownPackageRegistry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-BE / T4-BF: walks compile-probe stderr into a structured {@link Outcome} the deferred-
 * mutation ledger and the evolution-steering loop can both consume. Replaces the binary
 * "attribute every diagnostic or refuse the whole extraction" rule from the original T4-BE
 * shipment with a per-line {@link DiagnosticClass} classifier.
 *
 * <p>Classification flow:
 * <ol>
 *   <li>Strip ANSI escapes and split stderr into {@code [ERROR]}/{@code error:} lines.</li>
 *   <li>Skip {@link #BANNER_LINE} entries (Maven framing, javac continuation lines).</li>
 *   <li>Classify each remaining line into {@link DiagnosticClass#DEFERRABLE},
 *       {@link DiagnosticClass#AUTHOR_ERROR}, or {@link DiagnosticClass#UNKNOWN}.</li>
 *   <li>Build {@link MutationBlocker}s from the deferrable shapes (missing member, missing
 *       type) via {@link MissingMemberExtractor}.</li>
 *   <li>Decide the {@link Outcome.Result}: defer iff blockers exist AND no AUTHOR_ERROR AND
 *       no UNKNOWN. AUTHOR_ERROR refuses (re-staging cannot resolve a programming bug)
 *       but the classified diagnostics are returned for T2-DT steering. UNKNOWN refuses
 *       and captures the first unmatched line for post-hoc analysis.</li>
 * </ol>
 *
 * <p>The {@code Outcome.errorShapes} list is a deduplicated, prefix-normalised view of every
 * non-banner diagnostic (deferrable + author + unknown). It collapses concrete tokens so
 * cross-cycle aggregation in {@code behaviour.ndjson} groups related errors:
 * {@code incompatible types: int → String} and {@code incompatible types: long → Integer}
 * both reduce to {@code incompatible types: ... cannot be converted to ...}.
 */
public final class BlockerExtractor {

    private static final Pattern ERROR_DIAGNOSTIC_LINE = Pattern.compile(
            "(?m)^.*?(?:\\[ERROR]|error:).*?$");

    // ----- Deferrable patterns ------------------------------------------------------------
    private static final Pattern CANNOT_FIND_SYMBOL_HEADER = Pattern.compile(
            "cannot find symbol", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONSTRUCTOR_DIAGNOSTIC = Pattern.compile(
            "no suitable constructor found for|constructor\\s+\\S+\\s+is not applicable",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern METHOD_SYMBOL_DIAGNOSTIC = Pattern.compile(
            "symbol:\\s*method\\s+\\w+", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLASS_SYMBOL_DIAGNOSTIC = Pattern.compile(
            "symbol:\\s*(?:class|variable)\\s+\\w+", Pattern.CASE_INSENSITIVE);
    // `location: package X` appears when a file references a missing type via a package-qualified
    // name (e.g. `com.example.demo.BookService` from a sub-package). Observed 2026-06-10: this was
    // the ONLY unclassified shape in the 14:04 run — 15 REFUSED_UNKNOWN outcomes, each vetoing
    // deferral of 5-9 legitimate blockers extracted from the same stderr.
    private static final Pattern LOCATION_LINE = Pattern.compile(
            "location:\\s*(?:class|interface|variable|package)\\s+", Pattern.CASE_INSENSITIVE);
    // T1-ET: `package X.Y.Z does not exist`. Same shape as BuildErrorClassifier's
    // PACKAGE_DOES_NOT_EXIST_PATTERN; classifier-side reclassification of this diagnostic from
    // UNKNOWN to DEFERRABLE was promised by T4-BF (via T4-BG hand-off) and never wired — see
    // T1-ET Part A roadmap entry. Capture group 1 = package name.
    private static final Pattern PACKAGE_DOES_NOT_EXIST = Pattern.compile(
            "package\\s+(\\S+)\\s+does\\s+not\\s+exist", Pattern.CASE_INSENSITIVE);

    // ----- Author-error patterns ----------------------------------------------------------
    // Order within the alternation is irrelevant — `find()` matches anywhere on the line.
    private static final Pattern AUTHOR_ERROR_LINE = Pattern.compile(
            "(?i)cannot be referenced from a static context|"
                    + "incompatible types:|"
                    + "cannot be applied to|"
                    + "is not abstract and does not override|"
                    + "unreachable statement|"
                    + "might not have been initialized");

    // ----- Banner / continuation lines (silently ignored for classification) --------------
    // Maven framing (BUILD FAILURE, [Help 1], Re-run Maven, MojoFailureException, https://…),
    // blank [ERROR] separators, and javac diagnostic continuation lines (`required:`,
    // `found:`, `reason:`, caret-only). Without these the per-line classifier would tag
    // every "[ERROR] -> [Help 1]" footer as UNKNOWN and refuse otherwise-clean extractions.
    private static final Pattern BANNER_LINE = Pattern.compile(
            "(?i)compilation error|build failure|to see the full stack trace|re-run maven|"
                    + "failed to execute goal|-> \\[help|^\\[error]\\s*$|^\\[error]\\s*->\\s*$|"
                    + "for more information about the errors|please read the following articles|"
                    + "\\[help\\s+\\d+]|https?://|mojofailureexception|"
                    + "(?:\\[error]\\s*)?\\s*required:\\s|"
                    + "(?:\\[error]\\s*)?\\s*found:\\s|"
                    + "(?:\\[error]\\s*)?\\s*reason:\\s|"
                    + "^\\s*\\[error]\\s*\\^\\s*$");

    // Parses the location header on a Maven-style javac error line:
    //   [ERROR] /src/main/java/Foo.java:[42,18] message text
    // Group 1 = path, group 2 = line, group 3 = column, group 4 = message.
    private static final Pattern LOCATION_HEADER = Pattern.compile(
            "(?:\\[ERROR]\\s*)?(\\S+\\.java):\\[(\\d+),(\\d+)]\\s*(.*)");

    private BlockerExtractor() {}

    // -------------------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------------------

    /** Per-line classification of a single diagnostic; surfaced for tests + downstream
     *  steering that wants the file/line/column. */
    public record ClassifiedDiagnostic(String file, int line, int column, String message) {}

    /**
     * Structured extractor result. {@link #blockers} is populated whenever any
     * {@link DiagnosticClass#DEFERRABLE} shape produced a {@link MutationBlocker}, even
     * if the overall {@link #result} refuses deferral — callers may want to inspect the
     * partial structure (e.g. T2-DT logs both halves). {@link #authorErrors} is populated
     * on {@link Result#REFUSED_AUTHOR_ERROR}. {@link #firstUnknownLine} is populated only
     * on {@link Result#REFUSED_UNKNOWN}.
     */
    public record Outcome(
            Result result,
            List<MutationBlocker> blockers,
            List<ClassifiedDiagnostic> authorErrors,
            String firstUnknownLine,
            int unknownLineCount,
            List<String> errorShapes
    ) {
        public enum Result {
            /** Deferrable blockers present, no author errors, no unknown lines → deferred. */
            DEFERRED,
            /** At least one author-error line; re-stage cannot resolve it. */
            REFUSED_AUTHOR_ERROR,
            /** At least one diagnostic-shaped line the classifier doesn't recognise. */
            REFUSED_UNKNOWN,
            /** Stderr was blank / banner-only / produced no extractable blockers. */
            NO_DEFERRABLE
        }

        public int deferrableCount() { return blockers == null ? 0 : blockers.size(); }
        public int authorErrorCount() { return authorErrors == null ? 0 : authorErrors.size(); }
        public int unknownCount() { return unknownLineCount; }
    }

    public static Outcome extract(String stderr) {
        return extract(stderr, null, null);
    }

    /**
     * T1-ET: registry-aware overload. When {@code registry} is non-null and
     * {@link WellKnownPackageRegistry#replacementFor(String, String)} returns a replacement for an
     * extracted missing package, the emitted blocker is upgraded from
     * {@link MutationBlocker#missingPackage(String) missingPackage} to
     * {@link MutationBlocker#packageRewrite(String, String, String) packageRewrite} so the
     * deferred-mutation ledger persists the modern-GAV target alongside the deprecated prefix
     * (lets downstream restage / inspection dispatch straight to the registry without re-deriving
     * the mapping). Honours T4-BG's "Pairs with T4-BF" claim. Pass {@code null}/{@code null} to
     * preserve the original {@link #extract(String)} shape.
     */
    public static Outcome extract(String stderr,
                                  WellKnownPackageRegistry registry,
                                  String manifest) {
        if (stderr == null || stderr.isBlank()) {
            return new Outcome(Outcome.Result.NO_DEFERRABLE, List.of(), List.of(), null, 0, List.of());
        }
        String plain = BuildErrorClassifier.ANSI_ESCAPE.matcher(stderr).replaceAll("");

        // 1) Extract MutationBlockers from the deferrable shapes — unchanged from T4-BE.
        Set<MutationBlocker> blockers = new LinkedHashSet<>();
        Set<String> memberTargets = new LinkedHashSet<>();
        for (MissingMemberExtractor.Entry e : MissingMemberExtractor.extract(plain)) {
            blockers.add(MutationBlocker.missingMember(e.targetType(), e.memberName(), e.argTypes()));
            memberTargets.add(e.targetType());
        }
        Matcher symbolM = Pattern.compile(
                "symbol:\\s*(?:class|variable)\\s+(\\S+)", Pattern.CASE_INSENSITIVE).matcher(plain);
        while (symbolM.find()) {
            String token = stripTrailingPunctuation(symbolM.group(1));
            if (token.isBlank() || memberTargets.contains(token)) continue;
            blockers.add(MutationBlocker.missingType(token));
        }
        // T1-ET: pre-extract `package X.Y.Z does not exist` packages, upgrade to packageRewrite
        // when the registry has a deterministic modern replacement.
        Matcher packageM = PACKAGE_DOES_NOT_EXIST.matcher(plain);
        Set<String> seenPackages = new LinkedHashSet<>();
        while (packageM.find()) {
            String pkg = stripTrailingPunctuation(packageM.group(1));
            if (pkg.isBlank() || !seenPackages.add(pkg)) continue;
            blockers.add(buildPackageBlocker(pkg, registry, manifest));
        }

        // 2) Per-line classification.
        List<ClassifiedDiagnostic> authorErrors = new ArrayList<>();
        Set<String> shapes = new LinkedHashSet<>();
        int unknownCount = 0;
        String firstUnknownLine = null;

        Matcher diag = ERROR_DIAGNOSTIC_LINE.matcher(plain);
        while (diag.find()) {
            String line = diag.group().trim();
            if (line.isBlank()) continue;
            if (BANNER_LINE.matcher(line).find()) continue;

            DiagnosticClass cls = classify(line);
            switch (cls) {
                case DEFERRABLE -> shapes.add(normaliseShape(line));
                case AUTHOR_ERROR -> {
                    authorErrors.add(withContinuation(parseDiagnostic(line), plain, diag.end()));
                    shapes.add(normaliseShape(line));
                }
                case UNKNOWN -> {
                    unknownCount++;
                    if (firstUnknownLine == null) firstUnknownLine = line;
                    shapes.add(normaliseShape(line));
                }
            }
        }

        // 3) Decide the Outcome.
        List<MutationBlocker> blockerList = List.copyOf(blockers);
        List<ClassifiedDiagnostic> authorList = List.copyOf(authorErrors);
        List<String> shapeList = List.copyOf(shapes);

        if (authorList.size() > 0) {
            return new Outcome(Outcome.Result.REFUSED_AUTHOR_ERROR,
                    blockerList, authorList, null, unknownCount, shapeList);
        }
        if (unknownCount > 0) {
            return new Outcome(Outcome.Result.REFUSED_UNKNOWN,
                    blockerList, authorList, firstUnknownLine, unknownCount, shapeList);
        }
        if (blockerList.isEmpty()) {
            return new Outcome(Outcome.Result.NO_DEFERRABLE,
                    blockerList, authorList, null, 0, shapeList);
        }
        return new Outcome(Outcome.Result.DEFERRED,
                blockerList, authorList, null, 0, shapeList);
    }

    // -------------------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------------------

    private static DiagnosticClass classify(String line) {
        // AUTHOR_ERROR wins over DEFERRABLE — a line that mentions both `cannot find symbol`
        // and `incompatible types` (unlikely but possible in synthetic stderr) is still an
        // author bug; the ledger should not try to defer it.
        if (AUTHOR_ERROR_LINE.matcher(line).find()) return DiagnosticClass.AUTHOR_ERROR;
        if (CANNOT_FIND_SYMBOL_HEADER.matcher(line).find()) return DiagnosticClass.DEFERRABLE;
        if (CONSTRUCTOR_DIAGNOSTIC.matcher(line).find()) return DiagnosticClass.DEFERRABLE;
        if (METHOD_SYMBOL_DIAGNOSTIC.matcher(line).find()) return DiagnosticClass.DEFERRABLE;
        if (CLASS_SYMBOL_DIAGNOSTIC.matcher(line).find()) return DiagnosticClass.DEFERRABLE;
        if (LOCATION_LINE.matcher(line).find()) return DiagnosticClass.DEFERRABLE;
        // T1-ET: closes the classifier-side hand-off T4-BF deferred to T4-BG. Without this
        // every `package X.Y.Z does not exist` line refused as REFUSED_UNKNOWN and the
        // deferred-mutation ledger never saw the rejection (verified zero ledger entries
        // for this shape across runs 00:31 and 01:10 on 2026-05-25).
        if (PACKAGE_DOES_NOT_EXIST.matcher(line).find()) return DiagnosticClass.DEFERRABLE;
        return DiagnosticClass.UNKNOWN;
    }

    private static MutationBlocker buildPackageBlocker(String pkg,
                                                       WellKnownPackageRegistry registry,
                                                       String manifest) {
        if (registry == null) return MutationBlocker.missingPackage(pkg);
        Optional<WellKnownPackageRegistry.PackageReplacement> replacement = registry.replacementFor(pkg, manifest);
        if (replacement.isEmpty()) return MutationBlocker.missingPackage(pkg);
        WellKnownPackageRegistry.PackageReplacement rep = replacement.get();
        // The Gav lookup uses the missing prefix (so longest-prefix matching against the TABLE
        // resolves the same registry entry the replacement came from).
        Optional<WellKnownPackageRegistry.Gav> gav = registry.lookup(pkg, manifest);
        String coords = gav.map(g -> g.groupId() + ":" + g.artifactId()
                        + ":" + (g.version() == null ? "" : g.version()))
                .orElse("");
        return MutationBlocker.packageRewrite(rep.oldPrefix(), rep.newPrefix(), coords);
    }

    /**
     * An indented line directly under a diagnostic, optionally behind the tool's level marker:
     * the compiler's explanation of the error above it.
     */
    private static final Pattern CONTINUATION_LINE = Pattern.compile("^(?:\\[ERROR]\\s*)?\\s{2,}(\\S.*)$");
    private static final int MAX_CONTINUATION_LINES = 4;

    /**
     * T2-FF (filed first as T2-FM): keeps the compiler's own explanation with the error it explains.
     *
     * <p>A diagnostic's first line often names only the KIND of error, and the lines indented under
     * it name the particulars: which two types an inference could not reconcile, what a call
     * required and what it found. Those lines were read one at a time, skipped as framing or left
     * unclassified, and never attached, so the steering block told the model "inference variable T
     * has incompatible bounds" without either bound. Over 111 runs that one error was in 605 of
     * 1233 probe failures, and the model repeated it. The rule is layout only (indented lines under
     * the error), so it holds no opinion on any message's wording.
     */
    private static ClassifiedDiagnostic withContinuation(ClassifiedDiagnostic d, String plain, int from) {
        if (d == null || plain == null || from >= plain.length()) return d;
        List<String> details = new ArrayList<>();
        int pos = plain.indexOf('\n', from);
        while (pos >= 0 && pos + 1 < plain.length() && details.size() < MAX_CONTINUATION_LINES) {
            int next = plain.indexOf('\n', pos + 1);
            String candidate = plain.substring(pos + 1, next < 0 ? plain.length() : next).stripTrailing();
            if (LOCATION_HEADER.matcher(candidate).find()) break;
            Matcher c = CONTINUATION_LINE.matcher(candidate);
            if (!c.matches()) break;
            // The caret under a quoted source line points, it does not explain; step over it.
            if (!c.group(1).trim().equals("^")) details.add(c.group(1).trim());
            pos = next;
        }
        if (details.isEmpty()) return d;
        return new ClassifiedDiagnostic(d.file(), d.line(), d.column(),
                d.message() + " (" + String.join("; ", details) + ")");
    }

    private static ClassifiedDiagnostic parseDiagnostic(String line) {
        Matcher m = LOCATION_HEADER.matcher(line);
        if (m.find()) {
            return new ClassifiedDiagnostic(
                    m.group(1),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)),
                    m.group(4).trim());
        }
        return new ClassifiedDiagnostic(null, 0, 0, line);
    }

    /**
     * Reduces a concrete diagnostic line to a stable shape for cross-cycle aggregation.
     * Concrete tokens (file paths, type names, member signatures) collapse to {@code ...};
     * the surrounding regex skeleton survives. Lines we don't recognise return their
     * trimmed text — better than nothing for novel shapes the team needs to spot in the
     * behaviour log.
     */
    public static String normaliseShape(String line) {
        String lower = line.toLowerCase();
        if (lower.contains("cannot find symbol")) return "cannot find symbol";
        // T1-ET: collapse `package javax.validation does not exist`,
        // `package javax.validation.constraints does not exist`, etc. into one shape so the
        // cascade is visible as a single distinct error in `behaviour-*.ndjson` aggregation.
        if (PACKAGE_DOES_NOT_EXIST.matcher(line).find()) return "package ... does not exist";
        if (lower.contains("no suitable constructor found for")) return "no suitable constructor found for ...";
        if (lower.contains("cannot be referenced from a static context"))
            return "non-static method ... cannot be referenced from a static context";
        if (lower.contains("incompatible types") && lower.contains("cannot be converted to"))
            return "incompatible types: ... cannot be converted to ...";
        if (lower.contains("cannot be applied to given types"))
            return "method ... cannot be applied to given types";
        if (lower.contains("cannot be applied to"))
            return "... cannot be applied to ...";
        if (lower.contains("is not abstract and does not override"))
            return "... is not abstract and does not override ...";
        if (lower.contains("unreachable statement")) return "unreachable statement";
        if (lower.contains("might not have been initialized"))
            return "variable ... might not have been initialized";
        if (lower.contains("symbol:") && lower.contains("method"))
            return "symbol: method ...";
        if (lower.contains("symbol:") && lower.contains("class"))
            return "symbol: class ...";
        if (lower.contains("symbol:") && lower.contains("variable"))
            return "symbol: variable ...";
        if (lower.contains("location:")) return "location: ...";
        return line.trim();
    }

    private static String stripTrailingPunctuation(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == ',' || c == '.' || c == ';' || c == ')') { end--; } else break;
        }
        return s.substring(0, end);
    }
}
