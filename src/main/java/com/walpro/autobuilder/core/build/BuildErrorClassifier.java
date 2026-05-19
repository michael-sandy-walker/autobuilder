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

    public record Classification(Kind kind, List<String> criteria) {}

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
            case CANNOT_FIND_SYMBOL -> firstMatch(CANNOT_FIND_SYMBOL_PATTERN, plain);
            case MISSING_POM, UNKNOWN -> Optional.empty();
        };
    }

    private static Optional<String> firstMatch(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }
}
