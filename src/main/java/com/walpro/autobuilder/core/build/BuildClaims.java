package com.walpro.autobuilder.core.build;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * T4-EC: whether a criterion's WHOLE claim is that the code compiles -- and whether a test run of this tree proves it.
 *
 * <p>Found by checking the participation pair's 34 criteria against the record before merging their trees
 * (2026-10-20): the build-fix subgoals carry "mvn compile succeeds with no errors" and "The type mismatch at line 57
 * (List assigned to int) is resolved", and nothing structural answered either -- both went to a reading, though a
 * tree whose tests compiled and ran has compiled, and a compile error it once had is gone.
 *
 * <p><b>Confirm-only, like {@link TestRunClaims}.</b> A test run of this tree means its sources compiled; the
 * absence of a run says nothing either way, so this never contradicts. Only these shapes, whole:
 * <ul>
 *   <li>"(mvn compile | the project | the code | the build) (succeeds | compiles) (with no / without) (compile) errors"</li>
 *   <li>"the (type mismatch | compile error | compilation error) (at line N) (...) (in X) is resolved / fixed"</li>
 * </ul>
 */
public final class BuildClaims {

    private static final Pattern COMPILES = Pattern.compile(
            "(?:`?mvn compile`?|(?:the )?(?:project|code|build|application)(?: compilation)?)"
                    + " (?:succeeds|compiles|completes)(?: successfully)?(?: (?:with no|without) (?:compil(?:e|ation) )?errors)?");

    private static final Pattern ERROR_RESOLVED = Pattern.compile(
            "(?:the )?(?:type mismatch|compile error|compilation error|compiler error)(?: at line \\d+)?(?: \\([^()]*\\))?"
                    + "(?: in \\S+)? is (?:resolved|fixed)");

    private BuildClaims() {
    }

    /** Whether {@code criterion} is, as a whole, a claim that the code compiles. Shape only. */
    public static boolean isClaim(String criterion) {
        if (criterion == null || criterion.isBlank()) return false;
        String claim = criterion.strip().replaceAll("[.\\s]+$", "").toLowerCase(Locale.ROOT);
        return COMPILES.matcher(claim).matches() || ERROR_RESOLVED.matcher(claim).matches();
    }

    /** TRUE when {@code criterion} claims only that the code compiles and {@code report} is a run of this tree; else null. */
    public static Boolean answer(String criterion, TestRunReport report) {
        if (report == null || report.run() <= 0 || !isClaim(criterion)) return null;
        return Boolean.TRUE;
    }
}
