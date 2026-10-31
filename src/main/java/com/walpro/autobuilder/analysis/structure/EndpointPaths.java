package com.walpro.autobuilder.analysis.structure;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How an endpoint is written in prose, and how two mentions of one are compared: the pure half of
 * {@link EndpointSurfaceQuery}.
 *
 * <p>T5-BR: extracted so the readers that only ever needed these few static rules -- the criterion
 * parser, the specification's per-endpoint statuses -- stop depending on a Spring component that
 * also parses Java sources. The rules are unchanged; only their home moved.
 */
public final class EndpointPaths {

    /**
     * A verb-qualified path mention in prose, e.g. {@code GET /loans?memberId=} or {@code POST
     * /books}. Group 1 is the verb, group 2 the raw path. The one home for the grammar: the
     * criterion parser, the specification's status reader and the surface query each carried an
     * identical private copy of it.
     */
    public static final Pattern MENTION = Pattern.compile(
            "\\b(GET|POST|PUT|PATCH|DELETE)\\s+(/(?:[A-Za-z0-9_\\-{}/.?=&]*[A-Za-z0-9_\\-{}/?=&])?)");

    private EndpointPaths() {
    }

    /** Verb-qualified endpoint mentions in the text, normalized, in document order. */
    public static Set<Endpoint> declaredIn(String text) {
        Set<Endpoint> declared = new LinkedHashSet<>();
        if (text == null) return declared;
        Matcher m = MENTION.matcher(text);
        while (m.find()) {
            String path = normalizePath(m.group(2));
            if (path != null) declared.add(new Endpoint(m.group(1).toUpperCase(), path));
        }
        return declared;
    }

    /**
     * The part of a stated input that {@link #normalizePath} discards — everything it drops in
     * order to compare two mentions of the same endpoint for IDENTITY.
     *
     * <p>T1-FW: normalization for comparison is lossy BY DESIGN, and six callers want exactly that.
     * The one caller that builds a REQUEST wants the opposite, and for want of this method it had
     * no way to tell that it had been handed less than the requirement stated — a criterion about
     * {@code /loans?memberId={id}} was exercised as bare {@code /loans}, the application correctly
     * refused a missing required parameter, and the artifact was recorded as failing. Asking what
     * was discarded is what lets a builder decline rather than exercise a different input.
     *
     * <p>Returns empty when nothing was discarded, so the common case stays silent.
     */
    public static Optional<String> discardedInput(String raw) {
        if (raw == null) return Optional.empty();
        int q = raw.indexOf('?');
        if (q < 0 || q == raw.length() - 1) return Optional.empty();
        String discarded = raw.substring(q + 1).trim();
        return discarded.isBlank() ? Optional.empty() : Optional.of(discarded);
    }

    /**
     * Normalizes a path for comparison: strips any query string, collapses duplicate slashes,
     * lower-cases, replaces every path variable with {@code {}}, drops the trailing slash.
     * Returns null for blank/unusable input. Also consumed by T4-BT.
     */
    public static String normalizePath(String raw) {
        if (raw == null) return null;
        String p = raw.trim();
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.isBlank()) return null;
        if (!p.startsWith("/")) p = "/" + p;
        p = p.replaceAll("/{2,}", "/");
        p = p.replaceAll("\\{[^}]*}", "{}");
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        String lower = p.toLowerCase();
        return lower.equals("/") ? null : lower;
    }
}
