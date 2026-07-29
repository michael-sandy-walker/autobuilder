package com.walpro.autobuilder.core.build;

/**
 * T2-EW: whether a test source belongs in the artifact at all, given what the specification asked
 * for. One home, because there is more than one door into {@code output/}.
 *
 * <p><b>Why this is a shared policy and not a check inside one agent.</b> The first cut lived in
 * {@code MergeAgent}'s merge loop alone. Run {@code 2026-07-29_00-54-22} then failed at
 * {@code artifact-build-failed} with broken {@code BookControllerTest} / {@code BookServiceTest}
 * in the tree — and the log shows the filter had <b>skipped exactly those two files</b>. It worked;
 * it was simply not the only writer. {@code MutationApplier.applyMutations} writes every mutation
 * straight to {@code output/} from the evolution agent, and {@code CompileProbeService} stages
 * mutations there to compile them. Guarding one door of three let the same class of failure through
 * for a fifth run ([[project-resume-state]] records runs 2, 16 and 17 dying the same way).
 *
 * <p><b>What it decides.</b> On a goal whose specification never asks for tests, a self-authored
 * test can satisfy nothing and can break everything: nothing runs it in flight (the per-cycle probe
 * compiles main only — T4-BN-2's blanket flip was bisect-confirmed harmful and stays reverted), the
 * completion-edge test gate abstains when the spec asks for none, and yet {@code mvn package}
 * COMPILES tests, so one stale reference to a constructor a main change removed fails the build.
 *
 * <p>Self-gating in both directions: a specification that asks for tests keeps every one of them,
 * and a missing or unreadable specification yields no opinion rather than a deletion.
 */
public final class UnrequestedTestSourcePolicy {

    private UnrequestedTestSourcePolicy() {
    }

    /**
     * True when {@code path} is a test source and {@code specText} never asks for tests.
     *
     * <p>False — keep the file — whenever the answer is not clearly yes: not a test path, no
     * specification to judge against, or a specification that does mention tests.
     */
    public static boolean isUnrequested(String path, String specText) {
        if (path == null) return false;
        String normalized = path.replace('\\', '/');
        boolean isTestSource = normalized.contains("src/test/") || normalized.startsWith("src/test/");
        if (!isTestSource) return false;
        if (specText == null || specText.isBlank()) return false; // nothing to judge against
        return !TestRunGate.specRequiresTests(specText);
    }
}
