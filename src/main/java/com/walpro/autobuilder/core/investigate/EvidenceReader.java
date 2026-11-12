package com.walpro.autobuilder.core.investigate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-EU: reads the lines of the project a check's statement is about. A check that keeps refusing states
 * what it found ("X is called only in T, which asserts nothing"); the fact that settles whether the work or
 * the check is wrong is the lines themselves, beside that statement.
 *
 * <p>A model finds the places, by probing the project's files ({@link LogInvestigator}); what is handed on is
 * only the LINES at the places it cites, read here from the files as they are now. Its own reading of them is
 * kept apart, labelled, for the operator -- it never reaches a prompt (the user's decision 2026-10-04: the
 * stuck layer does not act on a model's reading of a stuck case). A place the files do not have is left out,
 * so a citation cannot put a line into a prompt that no file holds.
 */
@Slf4j
@Component
public class EvidenceReader {

    static final int MAX_PLACES = 4;
    static final int MAX_LINES_PER_PLACE = 30;
    static final int PROBES = 8;

    /** {@code path:first-last} or {@code path:line}; a path has a file extension, so prose with a colon is not one. */
    private static final Pattern PLACE = Pattern.compile("([\\w./$-]+\\.[A-Za-z]\\w*):(\\d+)(?:\\s*-\\s*(\\d+))?");

    private final LogInvestigator investigator;
    private final boolean enabled;

    public EvidenceReader(LogInvestigator investigator,
                          // Whether a refusal that comes back after its first answer has the lines it is about read from the project's files and set beside it (T4-EU).
                          @Value("${autobuilder.stuck.evidence-read.enabled:true}") boolean enabled) {
        this.investigator = investigator;
        this.enabled = enabled;
    }

    /** Some lines of one file, as they are now. */
    public record Place(String path, int first, int last, List<String> lines) {
        public String render() {
            StringBuilder sb = new StringBuilder(path).append(':').append(first).append('-').append(last).append('\n');
            for (int i = 0; i < lines.size(); i++) sb.append("  ").append(first + i).append(": ").append(lines.get(i)).append('\n');
            return sb.toString();
        }
    }

    /**
     * @param places       the lines the statement is about, read from the files; empty when none was found
     * @param modelReading the investigating model's own answer, whole -- a model's reading, for the operator only
     * @param probes       the probes it took, in order
     */
    public record Reading(List<Place> places, String modelReading, List<String> probes) {
        public boolean found() {
            return !places.isEmpty();
        }
    }

    static String question(String statement) {
        return "A check of this project keeps refusing with this statement:\n" + statement + "\n\n"
                + "Find the places in the project's files that this statement is about -- the code it names, as it is now. "
                + "Answer with one line per place, at most " + MAX_PLACES + ", each in the form\n"
                + "<path>:<first line>-<last line>\n"
                + "with at most " + MAX_LINES_PER_PLACE + " lines per place, the narrowest range that shows it. "
                + "After the places, one sentence: do those lines show what the statement says, or something else?";
    }

    /** The lines {@code statement} is about in {@code project}; nothing found when it is off, or no place holds. */
    public Reading read(String statement, Path project, String cycleId) {
        if (!enabled || statement == null || statement.isBlank() || project == null || !Files.isDirectory(project)) {
            return new Reading(List.of(), null, List.of());
        }
        LogInvestigator.Investigation inv;
        try {
            inv = investigator.investigate(question(statement), Map.of(), project, PROBES, cycleId);
        } catch (RuntimeException e) {
            log.info("T4-EU: the evidence could not be looked for -- {}", e.getMessage());
            return new Reading(List.of(), null, List.of());
        }
        List<String> probes = inv.steps().stream().map(LogInvestigator.Step::probe).toList();
        if (!inv.answered()) return new Reading(List.of(), null, probes);
        return new Reading(placesIn(inv.answer(), new ProjectTree(project)), inv.answer(), probes);
    }

    /** The places an answer cites that the tree holds, each once, in the answer's order. */
    static List<Place> placesIn(String answer, ProjectTree tree) {
        List<Place> out = new ArrayList<>();
        if (answer == null) return out;
        Matcher m = PLACE.matcher(answer);
        while (m.find() && out.size() < MAX_PLACES) {
            String path = m.group(1).replace('\\', '/');
            while (path.startsWith("./")) path = path.substring(2);
            List<String> lines = tree.lines(path);
            if (lines == null || lines.isEmpty()) continue;
            int first, last;
            try {
                first = Integer.parseInt(m.group(2));
                last = m.group(3) == null ? first : Integer.parseInt(m.group(3));
            } catch (NumberFormatException e) {
                continue;
            }
            first = Math.max(1, first);
            last = Math.min(lines.size(), Math.min(last, first + MAX_LINES_PER_PLACE - 1));
            if (first > last) continue;
            final String p = path;
            final int f = first, l = last;
            if (out.stream().anyMatch(x -> x.path().equals(p) && x.first() == f && x.last() == l)) continue;
            out.add(new Place(path, first, last, List.copyOf(lines.subList(first - 1, last))));
        }
        return out;
    }
}
