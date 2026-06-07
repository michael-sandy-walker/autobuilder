package com.walpro.autobuilder.core.build;

import java.util.List;

/**
 * T2-DZ: a capability gap surfaced when a subgoal keeps driving a write that would relocate an
 * already-located type into a different package. The package-fragmentation gate
 * ({@link PackageFragmentationCheck}) rejects such a write because one simple type name maps to
 * exactly one package, and the loop has no way to relocate a type that already exists without
 * refactoring its original declaration.
 *
 * <p>This is a <i>revisable</i> record, deliberately not a prohibition. It states a fact about
 * the present — "the current capability set cannot relocate {@code typeName} from
 * {@code locatedPackage} to {@code attemptedPackage}" — and names the capability
 * ({@link #MISSING_CAPABILITY}) that would clear it. A future in-place-relocation capability can
 * read this record and reactivate the parked subgoal; nothing here declares the relocation
 * impossible in principle. The gap is scoped to a single type (and its exact package, so nested
 * packages are distinct), never to a package as a whole.
 *
 * @param typeName         the simple type name already located elsewhere on disk
 * @param locatedPackage   the package the type is currently located in (derived from its path)
 * @param attemptedPackage the package the rejected write tried to relocate it to
 */
public record RelocationCapabilityGap(String typeName, String locatedPackage, String attemptedPackage) {

    /** Stable identifier for the capability whose absence opens this gap. */
    public static final String MISSING_CAPABILITY = "in-place-type-relocation";

    /** Goal-metadata keys under which a parked subgoal records the gap for later readers. */
    public static final String META_CAPABILITY_GAP = "capabilityGap";
    public static final String META_TYPE = "capabilityGapType";
    public static final String META_LOCATED_PACKAGE = "capabilityGapLocatedPackage";
    public static final String META_ATTEMPTED_PACKAGE = "capabilityGapAttemptedPackage";

    /** Builds a gap from a fragmentation conflict, deriving both packages from the file paths. */
    public static RelocationCapabilityGap from(PackageFragmentationCheck.Conflict conflict) {
        if (conflict == null) return null;
        return new RelocationCapabilityGap(
                conflict.simpleName(),
                packageOf(conflict.existingPath()),
                packageOf(conflict.candidatePath()));
    }

    /** Revisable, present-tense summary — names the missing capability, claims no permanence. */
    public String summary() {
        return "blocked by capability gap (" + MISSING_CAPABILITY + "): type " + typeName
                + " is located in " + locatedPackage + "; relocating an already-located type to "
                + attemptedPackage + " needs a capability the loop does not have yet.";
    }

    /**
     * Derives the dotted package name from a source-file path, stripping a leading
     * {@code src/main/java/} or {@code src/test/java/} root when present (so nested packages stay
     * distinct). Best-effort: a path with no recognisable source root falls back to the
     * slash-to-dot directory.
     */
    static String packageOf(String path) {
        if (path == null) return "(unknown)";
        int slash = path.lastIndexOf('/');
        String dir = slash >= 0 ? path.substring(0, slash) : "";
        for (String root : List.of("src/main/java/", "src/test/java/")) {
            int idx = dir.indexOf(root);
            if (idx >= 0) {
                dir = dir.substring(idx + root.length());
                break;
            }
        }
        return dir.isEmpty() ? "(default package)" : dir.replace('/', '.');
    }
}
