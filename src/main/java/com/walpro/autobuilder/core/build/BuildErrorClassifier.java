package com.walpro.autobuilder.core.build;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T2-CT: classifies a Maven build failure by matching well-known error patterns in stderr.
 * Returns a {@link Classification} carrying the error kind and concrete acceptance criteria
 * that name the actual missing artifact, so the evolver has an unambiguous repair target.
 */
public final class BuildErrorClassifier {

    public enum Kind {
        MISSING_POM,
        CANNOT_FIND_SYMBOL,
        PACKAGE_DOES_NOT_EXIST,
        UNKNOWN
    }

    /**
     * T4-AN-4 / T4-AN-7: technology-agnostic partition of build errors. Populated by the
     * adaptive {@link BuildErrorClassifierAgent} when enabled; otherwise derived from
     * {@link Kind} by the pattern fallback. The category — not {@code Kind} — is the
     * structural commitment future build-fix consumers (T4-AN-6 inner loop, T1-DT gate)
     * branch on.
     */
    public enum Category { CONFIG_FIXABLE, SOURCE_FIXABLE, MIXED, UNKNOWN }

    /**
     * T4-AN-4: extended classification carrier. {@code kind} stays load-bearing for the
     * existing deterministic resolver chain (T4-AX/AY/AZ); the new fields are populated by
     * the adaptive classifier when enabled and consumed by prompt blocks + future inner-loop
     * exit conditions. All new fields are nullable; pattern-fallback {@link #classify} leaves
     * them null and only sets {@code category} from {@link #defaultCategoryFor(Kind)}.
     */
    public record Classification(
            Kind kind,
            List<String> criteria,
            Category category,
            String summaryLine,
            String missingArtifact,
            String missingSymbol,
            String suggestedFix
    ) {
        public Classification(Kind kind, List<String> criteria) {
            this(kind, criteria, defaultCategoryFor(kind), null, null, null, null);
        }
    }

    public static Category defaultCategoryFor(Kind kind) {
        return switch (kind) {
            case MISSING_POM, PACKAGE_DOES_NOT_EXIST -> Category.CONFIG_FIXABLE;
            case CANNOT_FIND_SYMBOL -> Category.SOURCE_FIXABLE;
            case UNKNOWN -> Category.UNKNOWN;
        };
    }

    public static final Pattern ANSI_ESCAPE = Pattern.compile("\u001B\\[[0-9;]*m");

    private static final Pattern MISSING_POM_PATTERN =
            Pattern.compile("no POM in this directory|could not find a POM", Pattern.CASE_INSENSITIVE);

    private static final Pattern CANNOT_FIND_SYMBOL_PATTERN =
            Pattern.compile("symbol:\\s*(?:class|variable|method)\\s+(\\S+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern PACKAGE_DOES_NOT_EXIST_PATTERN =
            Pattern.compile("package\\s+(\\S+)\\s+does not exist", Pattern.CASE_INSENSITIVE);

    private BuildErrorClassifier() {}

    public static Classification classify(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return unknown();
        }
        String plain = ANSI_ESCAPE.matcher(stderr).replaceAll("");

        if (MISSING_POM_PATTERN.matcher(plain).find()) {
            return new Classification(Kind.MISSING_POM, List.of(
                    "A pom.xml file exists at the project root",
                    "mvn compile exits 0 with no errors"
            ));
        }

        // CAUSE BEFORE CASCADE. An unresolvable import emits BOTH markers for the same file:
        // "package p does not exist" on the import line, then "cannot find symbol: class T" on
        // every use of a type from p. Testing the symbol first returned the CASCADE, so the
        // criterion became "the missing symbol 'JpaRepository' is defined or imported correctly"
        // — pointing the loop at creating or importing a type that no import can reach, because
        // the package is absent from the classpath. Measured on run 2026-08-24_00-07-06: the
        // package marker was present in every failing build and PACKAGE_DOES_NOT_EXIST was
        // classified ZERO times, while the loop spent 20 cycles editing the file and halted on a
        // convergence stall. A missing package almost always produces cascade symbol errors, so
        // the symptom masked the cause essentially whenever the cause occurred.
        //
        // The two kinds route differently and that is the point: PACKAGE_DOES_NOT_EXIST is
        // CONFIG_FIXABLE (the classpath/dependency path), CANNOT_FIND_SYMBOL is SOURCE_FIXABLE.
        // Stderr carrying only symbol errors is unaffected — this reorder only decides which wins
        // when both are present, and when both are present the package is the one to fix.
        Matcher packageMatcher = PACKAGE_DOES_NOT_EXIST_PATTERN.matcher(plain);
        if (packageMatcher.find()) {
            String pkg = packageMatcher.group(1);
            return new Classification(Kind.PACKAGE_DOES_NOT_EXIST, List.of(
                    "The missing package '" + pkg + "' is available on the compile classpath",
                    "mvn compile exits 0 with no errors"
            ));
        }

        Matcher symbolMatcher = CANNOT_FIND_SYMBOL_PATTERN.matcher(plain);
        if (symbolMatcher.find()) {
            String symbol = symbolMatcher.group(1);
            return new Classification(Kind.CANNOT_FIND_SYMBOL, List.of(
                    "The missing symbol '" + symbol + "' is defined or imported correctly",
                    "mvn compile exits 0 with no errors"
            ));
        }

        return unknown();
    }

    private static Classification unknown() {
        return new Classification(Kind.UNKNOWN, List.of(
                "Project compiles without errors (mvn build exits 0)",
                "Compile error reported in the previous build is resolved"
        ));
    }

    /**
     * Extracts the offending token from stderr for kinds that name one (package or symbol).
     * Returns empty for {@link Kind#MISSING_POM} and {@link Kind#UNKNOWN} since they carry no token.
     * Used by deterministic fix resolvers to decide which dependency to add.
     */
    public static Optional<String> extractToken(String stderr, Kind kind) {
        if (stderr == null || stderr.isBlank() || kind == null) return Optional.empty();
        String plain = ANSI_ESCAPE.matcher(stderr).replaceAll("");
        return switch (kind) {
            case PACKAGE_DOES_NOT_EXIST -> firstMatch(PACKAGE_DOES_NOT_EXIST_PATTERN, plain);
            case CANNOT_FIND_SYMBOL -> firstMatch(CANNOT_FIND_SYMBOL_PATTERN, plain);
            case MISSING_POM, UNKNOWN -> Optional.empty();
        };
    }

    private static Optional<String> firstMatch(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }
}
