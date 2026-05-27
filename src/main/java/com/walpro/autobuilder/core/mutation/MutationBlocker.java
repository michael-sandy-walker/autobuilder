package com.walpro.autobuilder.core.mutation;

/**
 * T4-BE: structured fingerprint of a single compile dependency that caused a probe to reject
 * a mutation. Pairs with {@link BlockerExtractor} (extraction from probe stderr) and the
 * graph-backed deferred-mutation ledger (persistence + clearance detection).
 *
 * <p>The fingerprint shape is the minimum the {@link BlockerClearanceDetector} needs to ask
 * "did a later merge declare this?" — receiver type for the lookup, member name +
 * normalised argument-type list to disambiguate overloads. T1-ET adds two package-level
 * kinds: MISSING_PACKAGE (no registry replacement known — straight pom dep would suffice if
 * a later merge supplies one) and PACKAGE_REWRITE (registry-known deprecated-prefix → modern
 * prefix migration; signature carries the {@code groupId:artifactId:version} of the modern
 * artifact).
 *
 * @param kind            MISSING_MEMBER (method/constructor missing on an existing type),
 *                        MISSING_TYPE (type referenced but not declared anywhere),
 *                        MISSING_PACKAGE (unresolved dotted package — T1-ET), or
 *                        PACKAGE_REWRITE (deprecated package with known modern replacement — T1-ET)
 * @param receiverType    simple name of the type expected to declare the symbol; for
 *                        MISSING_TYPE the receiver and symbol are the same name; for the package
 *                        kinds the receiver carries the (old/missing) package name
 * @param symbol          member name (constructor name = type name); type name for MISSING_TYPE;
 *                        package name for MISSING_PACKAGE; modern-prefix replacement for PACKAGE_REWRITE
 * @param signature       normalised argument-type list ("Long,Long") for member kinds;
 *                        empty string for MISSING_TYPE / MISSING_PACKAGE;
 *                        {@code groupId:artifactId:version} for PACKAGE_REWRITE (version may be blank)
 */
public record MutationBlocker(
        Kind kind,
        String receiverType,
        String symbol,
        String signature
) {
    public enum Kind { MISSING_MEMBER, MISSING_TYPE, MISSING_PACKAGE, PACKAGE_REWRITE }

    /** Stable identifier used for graph edges and ledger lookups. */
    public String fingerprint() {
        return kind.name() + "|" + receiverType + "|" + symbol + "|" + signature;
    }

    public static MutationBlocker missingMember(String receiverType, String memberName, String signature) {
        return new MutationBlocker(Kind.MISSING_MEMBER, receiverType, memberName, normaliseSignature(signature));
    }

    public static MutationBlocker missingType(String typeName) {
        return new MutationBlocker(Kind.MISSING_TYPE, typeName, typeName, "");
    }

    /** T1-ET: unresolved dotted package (e.g. {@code javax.validation}). Receiver == symbol
     *  so fingerprint dedup mirrors {@link #missingType}. Signature stays empty. */
    public static MutationBlocker missingPackage(String packageName) {
        return new MutationBlocker(Kind.MISSING_PACKAGE, packageName, packageName, "");
    }

    /** T1-ET: registry-known deprecated-prefix migration (e.g. {@code javax.validation} →
     *  {@code jakarta.validation}). {@code gavCoords} is {@code groupId:artifactId:version}
     *  (version may be blank for BOM-managed deps). */
    public static MutationBlocker packageRewrite(String oldPrefix, String newPrefix, String gavCoords) {
        return new MutationBlocker(Kind.PACKAGE_REWRITE, oldPrefix, newPrefix,
                gavCoords == null ? "" : gavCoords);
    }

    /**
     * Canonicalise an argument-type list so the extractor and detector agree on a fingerprint
     * regardless of which side wrote it. Strips whitespace and the {@code java.lang.} prefix
     * that javac stderr uses while JavaParser keeps simple names.
     */
    public static String normaliseSignature(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String collapsed = raw.replaceAll("\\s+", "");
        // Strip java.lang. prefix from any segment, but not from nested types like
        // java.util.List or user-package types we want to disambiguate.
        return collapsed.replace("java.lang.", "");
    }
}
