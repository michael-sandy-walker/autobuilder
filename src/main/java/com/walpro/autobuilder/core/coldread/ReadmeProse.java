package com.walpro.autobuilder.core.coldread;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T1-GK: what a README says about its project IN WORDS -- its headings and sentences, verbatim, with the markup that
 * says nothing in words left out: comments, tags (their text kept), images and badges, a link reduced to its text.
 * A line that is only markup is not a line of prose. Nothing is summarised or reworded.
 *
 * <p>Found on a public project whose README opens with a badge row in HTML: the first lines quoted into its seed were
 * {@code <a href=...><img src=...></a>}, {@code <br/><br/>} and {@code <!-- Badges Row 1 -->}.
 */
final class ReadmeProse {

    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    /** An image tag says nothing in words -- its alt text is a label for a picture, not a sentence. */
    private static final Pattern IMG_TAG = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** Any other tag, its text kept: an opening, closing or self-closing tag, possibly over several lines. */
    private static final Pattern TAG = Pattern.compile("</?[A-Za-z][A-Za-z0-9-]*(\\s[^<>]*)?/?>", Pattern.DOTALL);
    /** A markdown image, also inside a link (a badge): {@code ![alt](url)}. */
    private static final Pattern MD_IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)");
    /** A markdown link reduced to its text: {@code [text](url)}; an empty one leaves nothing. */
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    /** A reference-style link definition: {@code [label]: url}. */
    private static final Pattern MD_LINK_DEFINITION = Pattern.compile("^\\s*\\[[^\\]]+]:\\s*\\S+.*$");
    /** What is left of a line that held only markup: link brackets, separators, list marks, spaces. */
    private static final Pattern NOTHING_IN_WORDS = Pattern.compile("[\\s\\[\\]()|*_>#:.,;\\-=~`'\"!/\\\\]*");
    private static final Pattern ENTITY = Pattern.compile("&(nbsp|amp|lt|gt|quot|#39|middot|mdash|ndash);");

    private ReadmeProse() {
    }

    /** The README's lines of prose, in order, at most {@code max}. A fenced code block is kept as it is. */
    static List<String> of(String readme, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder outside = new StringBuilder();
        boolean fenced = false;
        for (String raw : readme.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("```")) {
                if (!fenced) flush(outside, out);
                out.add(line);
                fenced = !fenced;
            } else if (fenced) {
                if (!line.isEmpty()) out.add(line);
            } else {
                outside.append(raw).append('\n');
            }
        }
        flush(outside, out);
        return out.size() <= max ? out : List.copyOf(out.subList(0, max));
    }

    /** Prose outside code: markup that spans lines (a comment, a tag) is taken out before the text is split. */
    private static void flush(StringBuilder outside, List<String> out) {
        String text = COMMENT.matcher(outside).replaceAll("");
        outside.setLength(0);
        text = IMG_TAG.matcher(text).replaceAll("");
        text = TAG.matcher(text).replaceAll("");
        for (String raw : text.split("\\R")) {
            if (MD_LINK_DEFINITION.matcher(raw).matches()) continue;
            String line = MD_IMAGE.matcher(raw).replaceAll("");
            line = MD_LINK.matcher(line).replaceAll("$1");
            line = entities(line).replaceAll("\\s{2,}", " ").strip();
            if (line.isEmpty() || NOTHING_IN_WORDS.matcher(line).matches()) continue;
            out.add(line);
        }
    }

    private static String entities(String line) {
        Matcher m = ENTITY.matcher(line);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String r = switch (m.group(1)) {
                case "nbsp" -> " ";
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "#39" -> "'";
                case "middot" -> "-";
                default -> "-";
            };
            m.appendReplacement(b, Matcher.quoteReplacement(r));
        }
        m.appendTail(b);
        return b.toString();
    }
}
