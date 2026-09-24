package com.walpro.autobuilder.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T5-BO: text as it should be heard rather than as it is written.
 *
 * <p>Narration reached the voice verbatim, so a path was spoken "slash … slash …" and a file under
 * a deep source tree cost a sentence of noise before the one word that mattered. The rule is
 * about the SHAPE of a token, never about which paths a target happens to use:
 * <ul>
 *   <li>a file path or a URL — a scheme, a file extension on its last segment, or four or more
 *       segments — is spoken as its last segment, without the extension;</li>
 *   <li>a short route ({@code /items/{id}/archive}) is spoken as its words, because there the
 *       whole route is the meaning and its last segment alone would be ambiguous.</li>
 * </ul>
 */
public final class SpeakableText {

    /** A whitespace-free token containing at least one slash between word characters or braces. */
    private static final Pattern SLASHED = Pattern.compile("[\\w.:{}\\-~]*(?:/[\\w.:{}\\-~]*)+");
    private static final Pattern SCHEME = Pattern.compile("^[a-z][a-z0-9+.-]*://.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTENSION = Pattern.compile("^(.+)\\.[A-Za-z0-9]{1,6}$");

    private SpeakableText() {}

    public static String of(String text) {
        if (text == null || text.indexOf('/') < 0) return text;
        Matcher m = SLASHED.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String token = m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(speak(token)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String speak(String token) {
        List<String> segments = new ArrayList<>();
        String body = SCHEME.matcher(token).matches() ? token.substring(token.indexOf("://") + 3) : token;
        for (String s : body.split("/")) if (!s.isBlank()) segments.add(s);
        if (segments.isEmpty()) return token;
        if (segments.size() == 2 && segments.stream().allMatch(x -> x.matches("\\d+"))) {
            return segments.get(0) + " of " + segments.get(1); // a count of a total: "3 of 5"
        }
        String last = segments.get(segments.size() - 1);
        boolean fileLike = SCHEME.matcher(token).matches()
                || EXTENSION.matcher(last).matches() && !last.startsWith("{")
                || segments.size() >= 4;
        if (fileLike) {
            Matcher ext = EXTENSION.matcher(last);
            return ext.matches() ? ext.group(1) : last;
        }
        List<String> words = new ArrayList<>();
        for (String s : segments) words.add(s.replace("{", "").replace("}", ""));
        return String.join(" ", words);
    }
}
