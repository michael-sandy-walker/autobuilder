package com.walpro.autobuilder.core.mutation;

/**
 * T4-BE: structured fingerprint of a single compile dependency that caused a probe to reject
 * a mutation. Pairs with {@link BlockerExtractor} (extraction from probe stderr) and the
 * graph-backed deferred-mutation ledger (persistence + clearance detection).
 *
 * <p>The fingerprint shape is the minimum the {@link BlockerClearanceDetector} needs to ask
 * "did a later merge declare this?" — receiver type for the lookup, member name +
 * normalised argument-type list to disambiguate overloads.
 *
 * @param kind            MISSING_MEMBER (method/constructor missing on an existing type) or
 *                        MISSING_TYPE (type referenced but not declared anywhere)
 * @param receiverType    simple name of the type expected to declare the symbol; for
 *                        MISSING_TYPE the receiver and symbol are the same name
 * @param symbol          member name (constructor name = type name); type name for MISSING_TYPE
 * @param signature       normalised argument-type list ("Long,Long") for member kinds;
 *                        empty string for MISSING_TYPE
 */
public record MutationBlocker(
        Kind kind,
        String receiverType,
        String symbol,
        String signature
) {
    public enum Kind { MISSING_MEMBER, MISSING_TYPE }

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
