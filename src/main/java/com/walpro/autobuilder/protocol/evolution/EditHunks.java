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
 * <p>One exception to "as written", and only one: the same whole lines indented by one constant amount
 * more or less than the file has them. Rule-extraction runs 2026-10-18_19-33-28 and 2026-10-19_00-39-56
 * refused seven answers whose SEARCH text was the file's own lines at 12 spaces where the file has 8, and
 * the file left out of the candidate broke the build every time. When nothing matches exactly, the lines
 * are compared without their leading whitespace; a place counts only when every non-blank line is off by
 * the SAME amount (a hunk whose nesting differs from the file's is not the same lines), and only a single
 * such place is applied -- the replacement shifted by that amount, so it lands at the file's indentation.
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EditHunks.class);

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

    /** The content with the hunk's SEARCH replaced, or null when it does not occur exactly once. */
    private static String replaceOnce(String content, Hunk h) {
        if (h.search().isEmpty()) return null;
        int n = count(content, h.search());
        if (n == 0) return shiftedOnce(content, h);
        if (n != 1) return null;
        int at = content.indexOf(h.search());
        return content.substring(0, at) + h.replace() + content.substring(at + h.search().length());
    }

    /**
     * The content with the hunk applied where the file has the SEARCH lines indented by one constant amount
     * more or less, the replacement shifted by the same amount; null unless exactly one such place exists.
     */
    static String shiftedOnce(String content, Hunk h) {
        String[] lines = content.split("\n", -1);
        String[] search = h.search().split("\n", -1);
        if (search.length == 0 || java.util.Arrays.stream(search).allMatch(String::isBlank)) return null;
        int at = -1;
        String shift = null;
        int delta = 0;
        for (int i = 0; i + search.length <= lines.length; i++) {
            Integer d = null;
            boolean same = true;
            for (int j = 0; j < search.length && same; j++) {
                String f = strip(lines[i + j]), q = strip(search[j]);
                if (f.isBlank() || q.isBlank()) { same = f.isBlank() && q.isBlank(); continue; }
                if (!f.stripLeading().equals(q.stripLeading())) { same = false; continue; }
                int dj = indent(f).length() - indent(q).length();
                if (d == null) d = dj; else same = d == dj;
            }
            if (!same || d == null || d == 0) continue;
            if (at >= 0) return null; // a second place: never a guess about which one was meant
            at = i;
            delta = d;
            // What a line gains: the file's own indentation beyond the SEARCH's, taken from the first non-blank line.
            for (int j = 0; j < search.length; j++) {
                if (strip(search[j]).isBlank()) continue;
                String fi = indent(strip(lines[i + j]));
                shift = d > 0 ? fi.substring(0, d) : "";
                break;
            }
        }
        if (at < 0) return null;
        List<String> out = new ArrayList<>(java.util.Arrays.asList(lines).subList(0, at));
        for (String r : h.replace().split("\n", -1)) {
            if (r.isBlank()) { out.add(r); continue; }
            if (delta > 0) out.add(shift + r);
            else if (indent(r).length() >= -delta) out.add(r.substring(-delta));
            else return null; // the replacement is less indented than the shift takes away: not a uniform shift
        }
        out.addAll(java.util.Arrays.asList(lines).subList(at + search.length, lines.length));
        log.info("T6-T: an edit's SEARCH lines matched at line {} with their indentation shifted by {} -- applied at the"
                + " file's indentation", at + 1, delta);
        return String.join("\n", out);
    }

    private static String strip(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static String indent(String line) {
        int k = 0;
        while (k < line.length() && (line.charAt(k) == ' ' || line.charAt(k) == '\t')) k++;
        return line.substring(0, k);
    }

    private static int count(String haystack, String needle) {
        if (needle.isEmpty()) return 0;
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }
}
