package com.walpro.autobuilder.core.briefing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-EA: what a developer would read before working on a target -- the reasons behind the goal, the design
 * it belongs to, where the code being changed is used, the conventions it must keep -- as background, never
 * as requirements.
 *
 * <p>The user, 2026-09-27: the loop is information-starved. Its prompts carried the seed and the state of the
 * run, and nothing of why the change is wanted or how the code around it works; a developer handed the same
 * ticket reads all of that first.
 *
 * <p>A briefing is a set of documents split into SECTIONS (a markdown heading, or a top-level list item of
 * the form {@code - **...**}). A prompt never carries it whole (T4-AP: lean prompts, selected content):
 * {@link #select} picks the sections that share the most distinctive words with what the prompt is about,
 * within a token budget, each section whole or not at all. Sections marked {@code always} (the conventions)
 * come first. Pure and deterministic.
 */
public final class Briefing {

    /** One section: which document it came from, its heading, its text. */
    public record Section(String document, String heading, String text, boolean always) {
        int tokens() {
            return (heading.length() + text.length()) / 4 + 1;
        }
    }

    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9_]{3,}");
    private static final Set<String> COMMON = Set.of("this", "that", "with", "from", "have", "what", "when", "which",
            "they", "their", "there", "into", "only", "than", "then", "them", "been", "does", "each", "every", "also",
            "more", "most", "must", "should", "would", "could", "will", "were", "where", "while", "after", "before",
            "about", "because", "same", "other", "some", "such", "these", "those", "under", "over", "never", "still");

    private final List<Section> sections;
    private final Map<String, Integer> documentFrequency = new HashMap<>();

    public Briefing(List<Section> sections) {
        this.sections = List.copyOf(sections);
        for (Section s : this.sections) {
            for (String w : words(s.heading() + " " + s.text())) documentFrequency.merge(w, 1, Integer::sum);
        }
    }

    public boolean isEmpty() {
        return sections.isEmpty();
    }

    public List<Section> sections() {
        return sections;
    }

    /**
     * Splits one document into sections. {@code always} marks every section of it as always shown (within
     * the budget) -- the conventions.
     */
    public static List<Section> parse(String document, String text, boolean always) {
        List<Section> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        String heading = document;
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\\R")) {
            boolean head = line.startsWith("#");
            boolean item = line.startsWith("- **");
            if (head || item) {
                flush(out, document, heading, body, always);
                if (head) {
                    heading = line.replaceFirst("^#+\\s*", "").strip();
                    continue;
                }
                heading = headingOfItem(line);
            }
            body.append(line).append('\n');
        }
        flush(out, document, heading, body, always);
        return out;
    }

    private static String headingOfItem(String line) {
        Matcher m = Pattern.compile("^- \\*\\*(.+?)\\*\\*").matcher(line);
        String h = m.find() ? m.group(1) : line.substring(2);
        return h.length() > 120 ? h.substring(0, 120) + "..." : h;
    }

    private static void flush(List<Section> out, String document, String heading, StringBuilder body, boolean always) {
        String text = body.toString().strip();
        body.setLength(0);
        if (!text.isEmpty()) out.add(new Section(document, heading, text, always));
    }

    /**
     * The sections most about {@code about}, within {@code tokenBudget}: the always-shown ones first, then by
     * the rarity-weighted count of words they share with {@code about}; a section that shares nothing is
     * never shown; each is taken whole or not at all.
     */
    public List<Section> select(String about, int tokenBudget) {
        Set<String> query = words(about == null ? "" : about);
        List<Section> ranked = new ArrayList<>();
        Map<Section, Double> score = new HashMap<>();
        for (Section s : sections) {
            double sc = 0;
            Set<String> own = words(s.heading() + " " + s.text());
            for (String w : query) {
                if (own.contains(w)) sc += 1.0 / documentFrequency.getOrDefault(w, 1);
            }
            if (s.always() || sc > 0) {
                ranked.add(s);
                score.put(s, s.always() ? Double.MAX_VALUE : sc);
            }
        }
        ranked.sort(Comparator.comparingDouble((Section s) -> score.get(s)).reversed());
        List<Section> out = new ArrayList<>();
        int used = 0;
        for (Section s : ranked) {
            if (used + s.tokens() > tokenBudget) continue;
            out.add(s);
            used += s.tokens();
        }
        return out;
    }

    /** The selected sections as one prompt block, or null when none was selected. */
    public String promptBlock(String about, int tokenBudget) {
        List<Section> chosen = select(about, tokenBudget - 60);
        if (chosen.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("--- BRIEFING (background a developer would read; it explains, it does not add requirements) ---\n");
        for (Section s : chosen) {
            sb.append("[").append(s.document()).append(": ").append(s.heading()).append("]\n").append(s.text()).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    static Set<String> words(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            String w = m.group().toLowerCase(Locale.ROOT);
            if (!COMMON.contains(w)) out.add(w);
        }
        return out;
    }
}
