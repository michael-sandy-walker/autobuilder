package com.walpro.autobuilder.core.mutation;

import com.walpro.autobuilder.core.build.BuildErrorClassifier;
import com.walpro.autobuilder.core.build.MissingMemberExtractor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-BE: walks compile-probe stderr into a typed list of {@link MutationBlocker}s the
 * deferred-mutation ledger can index. Returns {@link Optional#empty()} when stderr is not
 * fully attributable to extractable blockers — those rejections are *not* eligible for the
 * ledger because re-staging can never resolve what we can't fingerprint.
 *
 * <p>Sources, in order of precedence per error line:
 * <ol>
 *   <li>{@link MissingMemberExtractor} — {@code no suitable constructor} +
 *       {@code symbol: method m(args)} / {@code location: class T} shapes.</li>
 *   <li>{@link BuildErrorClassifier} {@code CANNOT_FIND_SYMBOL} — bare missing-type tokens
 *       (filtered: tokens that the member extractor already covered are skipped).</li>
 * </ol>
 *
 * <p>The "fully attributable" check: every {@code error:} or {@code [ERROR]} line in stderr
 * must be touched by at least one extractor pattern. Banners, summary lines, and trailing
 * Maven cruft are ignored. If any diagnostic-shaped line remains unexplained, the whole
 * extraction returns empty — partial deferral risks re-staging into a permanently-broken
 * compile.
 */
public final class BlockerExtractor {

    private static final Pattern ERROR_DIAGNOSTIC_LINE = Pattern.compile(
            "(?m)^.*?(?:\\[ERROR]|error:).*?$");

    // Lines we count as "attributable" when an extractor pattern matched somewhere on them.
    private static final Pattern CONSTRUCTOR_DIAGNOSTIC = Pattern.compile(
            "no suitable constructor found for|constructor\\s+\\S+\\s+is not applicable",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern METHOD_SYMBOL_DIAGNOSTIC = Pattern.compile(
            "symbol:\\s*method\\s+\\w+", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLASS_SYMBOL_DIAGNOSTIC = Pattern.compile(
            "symbol:\\s*(?:class|variable)\\s+\\w+", Pattern.CASE_INSENSITIVE);
    private static final Pattern CANNOT_FIND_SYMBOL_HEADER = Pattern.compile(
            "cannot find symbol", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCATION_LINE = Pattern.compile(
            "location:\\s*(?:class|interface|variable)\\s+", Pattern.CASE_INSENSITIVE);

    // Banners that count as "explained" without an extractor match (Maven framing, blank
    // error lines emitted between diagnostics).
    private static final Pattern BANNER_LINE = Pattern.compile(
            "(?i)compilation error|build failure|to see the full stack trace|re-run maven|" +
                    "failed to execute goal|-> \\[help|^\\[error]\\s*$|^\\[error]\\s*->\\s*$");

    private BlockerExtractor() {}

    public static Optional<List<MutationBlocker>> extract(String stderr) {
        if (stderr == null || stderr.isBlank()) return Optional.empty();
        String plain = BuildErrorClassifier.ANSI_ESCAPE.matcher(stderr).replaceAll("");

        Set<MutationBlocker> blockers = new LinkedHashSet<>();
        Set<String> memberTargets = new LinkedHashSet<>();

        for (MissingMemberExtractor.Entry e : MissingMemberExtractor.extract(plain)) {
            blockers.add(MutationBlocker.missingMember(e.targetType(), e.memberName(), e.argTypes()));
            memberTargets.add(e.targetType());
        }

        // CANNOT_FIND_SYMBOL tokens that aren't already covered as missing members.
        // BuildErrorClassifier.extractToken only returns the first; walk the stderr ourselves
        // for the bare-symbol case (class / variable shapes that don't pair with a location).
        Matcher symbolM = Pattern.compile(
                "symbol:\\s*(?:class|variable)\\s+(\\S+)", Pattern.CASE_INSENSITIVE).matcher(plain);
        while (symbolM.find()) {
            String token = stripTrailingPunctuation(symbolM.group(1));
            if (token.isBlank() || memberTargets.contains(token)) continue;
            blockers.add(MutationBlocker.missingType(token));
        }

        if (blockers.isEmpty()) return Optional.empty();

        // Attribution check: every diagnostic-shaped line must be touched by at least one of
        // the extractor patterns (or be a known banner). Lines that aren't are unexplained,
        // and we refuse to defer rather than re-stage into a partial fix.
        Matcher diag = ERROR_DIAGNOSTIC_LINE.matcher(plain);
        while (diag.find()) {
            String line = diag.group().trim();
            if (line.isBlank()) continue;
            if (BANNER_LINE.matcher(line).find()) continue;
            if (CONSTRUCTOR_DIAGNOSTIC.matcher(line).find()) continue;
            if (METHOD_SYMBOL_DIAGNOSTIC.matcher(line).find()) continue;
            if (CLASS_SYMBOL_DIAGNOSTIC.matcher(line).find()) continue;
            if (CANNOT_FIND_SYMBOL_HEADER.matcher(line).find()) continue;
            if (LOCATION_LINE.matcher(line).find()) continue;
            // Unexplained diagnostic — abort the extraction.
            return Optional.empty();
        }

        return Optional.of(new ArrayList<>(blockers));
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
