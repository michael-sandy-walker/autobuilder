package com.walpro.autobuilder.protocol.evolution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-EB: a change to an existing file written as EDITS -- a piece of the file as it is now, and what
 * replaces it -- applied exactly, instead of the whole file written out again.
 *
 * <p>A file could only be changed by re-emitting all of it, and a local model re-emitting 430 lines to
 * change one drifts: receiver-questions run 2026-09-29_18-09-05's rejected attempts changed 200-303
 * lines of a 433-line file in 7-18 places, for a fix of about one line that the dossier had named -- and
 * the drift (invented enum constants, a duplicated method) is what broke the build.
 *
 * <p>An edit block:
 * <pre>
 * ```edit [src/main/java/org/ink/Quill.java]
 * &lt;&lt;&lt;&lt;&lt;&lt;&lt; SEARCH
 * the lines as they are now
 * =======
 * the lines that replace them
 * &gt;&gt;&gt;&gt;&gt;&gt;&gt; REPLACE
 * ```
 * </pre>
 * Several hunks may follow one another in a block; each applies to the file as the hunks before it
 * left it. A SEARCH text must occur EXACTLY ONCE, matched as written; zero or several matches is a
 * refusal with the reason, never a guess about which place was meant, and nothing outside the matched
 * text is touched. An edit names an existing file; a new file is a whole-file block.
 *
 * <p>A block is an edit by what it SAYS, not only by its tag: one whose body opens with a SEARCH marker
 * is an edit whatever language it is labelled with. participation-totals run 2026-09-30_12-48-02 sent
 * both of its hunks in a {@code ```java [path]} fence -- the tag the prompt's own source listings use --
 * and each was written as the whole file, markers and all, and rejected at the compile probe. No source
 * file opens with that marker, so reading the body cannot mistake a file for an edit.
 */
public final class EditHunks {

    /** The language tag of an edit block. */
    public static final String TAG = "edit";

    private static final Pattern BLOCK = Pattern.compile("(?ms)```(\\w*) \\[([^\\]\\n]+)\\][ \\t]*\\r?\\n(.*?)\\r?\\n?"
            + com.walpro.autobuilder.protocol.FencedBlocks.CLOSING_FENCE);

    private static final String SEARCH_MARKER = "<<<<<<< SEARCH";
    private static final Pattern HUNK = Pattern.compile(
            "(?s)<<<<<<< SEARCH\\r?\\n(.*?)\\r?\\n?=======\\r?\\n(.*?)\\r?\\n?>>>>>>> REPLACE");

    private EditHunks() {
    }

    /** One edit block: the file it names and its hunks, in order. */
    public record Edit(String path, List<Hunk> hunks) {}

    public record Hunk(String search, String replace) {}

    /** What applying a response's edits to the tree gives: the new contents, and every refusal with its reason. */
    public record Applied(Map<String, String> files, List<String> problems) {}

    /** The edit blocks of a response, in order. */
    public static List<Edit> parse(String response) {
        List<Edit> out = new ArrayList<>();
        if (response == null) return out;
        Matcher b = BLOCK.matcher(response);
        while (b.find()) {
            if (!isEdit(b.group(1), b.group(3))) continue;
            List<Hunk> hunks = new ArrayList<>();
            Matcher h = HUNK.matcher(b.group(3));
            while (h.find()) hunks.add(new Hunk(h.group(1), h.group(2)));
            out.add(new Edit(b.group(2).strip(), hunks));
        }
        return out;
    }

    /** Whether a fenced block is an edit: tagged {@code edit}, or, under any tag, its body opens with a SEARCH marker. */
    public static boolean isEdit(String tag, String body) {
        return TAG.equalsIgnoreCase(tag == null ? "" : tag.strip())
                || body != null && body.stripLeading().startsWith(SEARCH_MARKER);
    }

    /**
     * Applies every edit block of {@code response} to {@code tree} (path -> current content). A file
     * whose edits all apply is in {@link Applied#files}; any refusal is named in {@link Applied#problems}
     * and that file is left out whole -- half an edit is not applied.
     */
    public static Applied apply(String response, Map<String, String> tree) {
        Map<String, String> files = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (Edit e : parse(response)) {
            String current = files.containsKey(e.path()) ? files.get(e.path()) : tree == null ? null : tree.get(e.path());
            if (current == null) {
                problems.add(e.path() + ": there is no such file to edit -- write a new file as a whole-file block");
                continue;
            }
            if (e.hunks().isEmpty()) {
                problems.add(e.path() + ": the edit block has no <<<<<<< SEARCH / ======= / >>>>>>> REPLACE hunk");
                continue;
            }
            String content = current;
            boolean ok = true;
            for (int i = 0; i < e.hunks().size(); i++) {
                Hunk h = e.hunks().get(i);
                String next = replaceOnce(content, h);
                if (next == null) {
                    int n = count(content, h.search());
                    problems.add(e.path() + ": hunk " + (i + 1) + "'s SEARCH text "
                            + (n == 0 ? "is not in the file as it is now -- copy it exactly from the current file"
                            : "occurs " + n + " times -- include more surrounding lines so it occurs once"));
                    ok = false;
                    break;
                }
                content = next;
            }
            if (ok) files.put(e.path(), content);
        }
        return new Applied(files, problems);
    }

    /**
     * T6-T: a whole-file block for an EXISTING file that keeps less than {@link #KEPT_SHARE} of its lines -- written
     * from the parts a prompt showed, not from the file. ladder-grind field50 run 1: a 1,079-line class shown as its
     * parts came back as a fraction of itself and no longer parsed ("class, interface, enum, or record expected");
     * whole rewrites duplicated declarations elsewhere. Such a block is refused with the reason, like an edit whose
     * SEARCH text is not in the file, so the model is asked again for edit blocks. Measured against the file's own
     * size, never a fixed number of lines.
     */
    public static List<String> partialRewrites(String response, Map<String, String> tree) {
        List<String> problems = new ArrayList<>();
        if (response == null || tree == null) return problems;
        Matcher b = BLOCK.matcher(response);
        while (b.find()) {
            if (isEdit(b.group(1), b.group(3))) continue;
            String path = b.group(2).strip();
            String current = tree.get(path);
            if (current == null || !path.endsWith(".java")) continue;
            long had = current.lines().count(), wrote = b.group(3).lines().count();
            // a block that applies member by member (MemberMerge) changes only what it wrote: nothing is dropped
            if (had >= MIN_LINES_FOR_SHARE && wrote < had * KEPT_SHARE && !MemberMerge.applies(current, b.group(3))) {
                problems.add(path + ": written whole with " + wrote + " of its " + had + " lines -- change an existing file"
                        + " with edit blocks whose SEARCH parts are copied from it; a whole file written from the parts"
                        + " you were shown drops everything else in it");
            }
        }
        return problems;
    }

    /** The share of an existing file's lines a whole-file rewrite must keep (see {@link #partialRewrites}). */
    static final double KEPT_SHARE = 0.5;
    /** Below this, a file is small enough that rewriting it whole is ordinary. */
    static final int MIN_LINES_FOR_SHARE = 40;

    /** The content with the hunk's SEARCH replaced, or null when it does not occur exactly once. */
    private static String replaceOnce(String content, Hunk h) {
        if (h.search().isEmpty() || count(content, h.search()) != 1) return null;
        int at = content.indexOf(h.search());
        return content.substring(0, at) + h.replace() + content.substring(at + h.search().length());
    }

    private static int count(String haystack, String needle) {
        if (needle.isEmpty()) return 0;
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }
}
