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

        Matcher symbolMatcher = CANNOT_FIND_SYMBOL_PATTERN.matcher(plain);
        if (symbolMatcher.find()) {
            String symbol = symbolMatcher.group(1);
            return new Classification(Kind.CANNOT_FIND_SYMBOL, List.of(
                    "The missing symbol '" + symbol + "' is defined or imported correctly",
                    "mvn compile exits 0 with no errors"
            ));
        }

        Matcher packageMatcher = PACKAGE_DOES_NOT_EXIST_PATTERN.matcher(plain);
        if (packageMatcher.find()) {
            String pkg = packageMatcher.group(1);
            return new Classification(Kind.PACKAGE_DOES_NOT_EXIST, List.of(
                    "The missing package '" + pkg + "' is available on the compile classpath",
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
            case CANNOT_FIND_SYMBOL -> firstMatch(CANNOT_FIND_SYMBOL_PATTERN, plain)
                    .map(BuildErrorClassifier::refineMissingSymbol);
            case MISSING_POM, UNKNOWN -> Optional.empty();
        };
    }

    /**
     * T1-EZ: when the captured symbol is a single-argument {@code method NAME(ArgType)} form, the
     * missing thing is usually the <em>argument type</em>, not the method — e.g.
     * {@code ResponseEntity.body(new ErrorResponse(...))} with a missing {@code ErrorResponse}
     * makes javac report {@code symbol: method body(com.example.demo.LoanController.ErrorResponse)}
     * (it can't resolve the {@code body(...)} overload). Return the arg type's simple name so the
     * work item is "create ErrorResponse", not the unactionable "create body(...)". Conservative:
     * only single-arg method calls are refined; plain {@code class}/{@code variable} symbols,
     * no-arg and multi-arg forms pass through unchanged (no regression to existing extraction).
     */
    private static String refineMissingSymbol(String token) {
        if (token == null) return null;
        int open = token.indexOf('(');
        if (open <= 0 || !token.endsWith(")")) return token;        // plain class/variable
        String arg = token.substring(open + 1, token.length() - 1).trim();
        if (arg.isEmpty() || arg.contains(",")) return token;        // no-arg or multi-arg: leave the method
        int lt = arg.indexOf('<');
        if (lt >= 0) arg = arg.substring(0, lt).trim();              // strip generics
        int dot = arg.lastIndexOf('.');
        String simple = dot >= 0 ? arg.substring(dot + 1) : arg;     // qualified -> simple name
        return simple.isEmpty() ? token : simple;
    }

    private static Optional<String> firstMatch(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }
}
