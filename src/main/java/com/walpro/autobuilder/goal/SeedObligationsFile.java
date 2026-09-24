package com.walpro.autobuilder.goal;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * T6-S: the seed's obligations, one per line, as a file the operator can read and correct.
 *
 * <p><b>Why this exists.</b> Splitting a specification into obligations was done by rule —
 * bracket-aware scanning, a {@code " and "} separator gated on a determiner, {@code CLAUSE_BREAK}.
 * Every tightening was measured, and every one was measured against the four seeds that existed:
 * the comma separator fixed one target and broke another, because a comma is a list separator in
 * one seed and prose in the other. That is a rule fitted to four documents rather than to language,
 * and more rules make it worse.
 *
 * <p><b>Why a FILE and not a call.</b> The split feeds the yardstick — the clause counts that judge
 * whether the loop covered the specification. A fresh model call per run would make that
 * measurement vary between runs on one seed, and the replay harness exists precisely to answer
 * what was decided WITHOUT the model. Written down once, the split becomes an INPUT: deterministic
 * to replay, reviewable in a diff, and correctable by hand.
 *
 * <p><b>Two hashes, and they answer different questions.</b> The SEED's hash says whether the
 * source has moved. The BODY's hash says whose split this is — it is provenance, never a tamper
 * gate, because rejecting an edited file is the same as not supporting editing. Their combination
 * is what makes the fourth case safe:
 *
 * <table>
 *   <tr><th>seed</th><th>body</th><th>meaning</th><th>action</th></tr>
 *   <tr><td>same</td><td>same</td><td>pristine, model-written</td><td>use</td></tr>
 *   <tr><td>same</td><td>differs</td><td>hand-edited</td><td>use, and report it as the operator's</td></tr>
 *   <tr><td>differs</td><td>same</td><td>seed moved, no edits</td><td>regenerate</td></tr>
 *   <tr><td>differs</td><td>differs</td><td>seed moved AND hand-edited</td><td><b>keep and warn</b></td></tr>
 * </table>
 *
 * <p>That last row is the reason the body hash is worth having: without it, editing a seed silently
 * destroys the operator's corrections.
 *
 * <p>Deliberately NOT mtime. A {@code git checkout}, a clone or a copy resets modification times,
 * so "seed newer than derived" fires after any branch switch and stays silent when a seed is
 * restored from an older commit. A hash is right in every one of those cases.
 */
@Slf4j
public final class SeedObligationsFile {

    /** Relative to {@code file.base-path}. Tracked, because a hand correction must survive a clean. */
    public static final String PATH = "bootstrap/seed.obligations.txt";

    private static final String SEED_HASH = "# seed-sha256: ";
    private static final String BODY_HASH = "# body-sha256: ";

    private SeedObligationsFile() {
    }

    /** What a read of the file concluded, and why. */
    public enum State {
        /** No file yet — generate one. */
        ABSENT,
        /** Unparseable or empty — regenerate; nothing of value can be lost. */
        MALFORMED,
        /** The file matches its seed and its own body hash. */
        PRISTINE,
        /** Well formed, matches its seed, body differs — the operator edited it. */
        EDITED,
        /** The seed moved and nobody had edited the file — safe to regenerate. */
        SEED_MOVED,
        /** The seed moved AND the file was edited. Regenerating would destroy the edit. */
        SEED_MOVED_WITH_EDITS
    }

    public record Parsed(State state, List<String> obligations) {
        public boolean usable() {
            return state == State.PRISTINE || state == State.EDITED
                    || state == State.SEED_MOVED_WITH_EDITS;
        }
    }

    /** Render the file for {@code obligations} derived from {@code seedText}. */
    public static String render(String seedText, List<String> obligations) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Obligations derived from bootstrap/seed.txt, one per line.\n");
        sb.append("# Edit freely: a hand-edited file is kept, and reported as yours.\n");
        sb.append("# Prefix a line with 'rule:' when it says HOW the work is done rather than something the\n");
        sb.append("# result can be checked for: it still guides the work, but never blocks completion.\n");
        sb.append(SEED_HASH).append(sha256(seedText == null ? "" : seedText)).append('\n');
        StringBuilder body = new StringBuilder();
        for (String o : obligations) {
            if (o == null || o.isBlank()) continue;
            body.append(o.strip()).append('\n');
        }
        sb.append(BODY_HASH).append(sha256(body.toString())).append('\n');
        sb.append(body);
        return sb.toString();
    }

    /** Read a rendered file and decide what it means for {@code seedText}. */
    public static Parsed read(String fileText, String seedText) {
        if (fileText == null || fileText.isBlank()) return new Parsed(State.ABSENT, List.of());
        String recordedSeed = headerValue(fileText, SEED_HASH);
        String recordedBody = headerValue(fileText, BODY_HASH);
        List<String> obligations = new ArrayList<>();
        for (String line : fileText.split("\\R", -1)) {
            if (line.startsWith("#") || line.isBlank()) continue;
            obligations.add(line.strip());
        }
        if (recordedSeed == null || recordedBody == null || obligations.isEmpty()) {
            return new Parsed(State.MALFORMED, List.of());
        }
        StringBuilder body = new StringBuilder();
        for (String o : obligations) body.append(o).append('\n');

        boolean seedSame = recordedSeed.equals(sha256(seedText == null ? "" : seedText));
        boolean bodySame = recordedBody.equals(sha256(body.toString()));
        State state = seedSame
                ? (bodySame ? State.PRISTINE : State.EDITED)
                : (bodySame ? State.SEED_MOVED : State.SEED_MOVED_WITH_EDITS);
        return new Parsed(state, List.copyOf(obligations));
    }

    private static String headerValue(String text, String prefix) {
        for (String line : text.split("\\R", -1)) {
            if (line.startsWith(prefix)) return line.substring(prefix.length()).strip();
            if (!line.startsWith("#") && !line.isBlank()) return null;   // headers precede the body
        }
        return null;
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Whether an edited file should be normalised before use.
     *
     * <p><b>The operator's concern, and it is the right one:</b> a hand edit may not follow the
     * shape the generator writes. The answer is to normalise from the EDITED FILE — never from the
     * seed, which would discard the edit that prompted it.
     *
     * <p><b>And the guard that makes that safe.</b> Handing prose to a model and asking for
     * formatting invites it to rewrite the content instead, so a normalisation is accepted only
     * when it preserves what the operator wrote: same number of obligations, and each one still
     * recognisably the same text. Anything else is refused and the operator's version stands —
     * a badly-formatted obligation the operator meant beats a well-formatted one they did not.
     */
    public static boolean preservesContent(List<String> edited, List<String> normalised) {
        if (edited == null || normalised == null || edited.size() != normalised.size()) return false;
        for (int i = 0; i < edited.size(); i++) {
            String a = squash(edited.get(i));
            String b = squash(normalised.get(i));
            // Formatting may add or drop surrounding punctuation and case, never the words.
            if (!a.equals(b) && !a.contains(b) && !b.contains(a)) return false;
        }
        return true;
    }

    private static String squash(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9<>/{}\\\\\\-]", "");
    }

    /** The obligations to use, or empty when the caller must generate them. */
    public static Optional<List<String>> obligationsFrom(Parsed parsed) {
        if (parsed.state() == State.SEED_MOVED_WITH_EDITS) {
            log.warn("T6-S: {} was hand-edited AND its seed has changed since. Keeping your version "
                    + "rather than overwriting it — delete the file to regenerate from the new seed.", PATH);
        }
        return parsed.usable() ? Optional.of(parsed.obligations()) : Optional.empty();
    }
}
