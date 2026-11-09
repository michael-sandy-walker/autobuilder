package com.walpro.autobuilder.observability.narration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T4-EM: one cycle's events, written out as the plain record the narrator model reads. Pure: envelopes in, text
 * out. Every line says something the stream says; nothing is inferred here, so whatever the narrator tells can be
 * checked against this record.
 *
 * <p>Events that are bookkeeping (signal snapshots, rankings, checkpoints, single llm calls) are left out: a record
 * of everything is a record the model has to summarise, and a summary is where it starts inventing.
 */
final class CycleRecord {

    private static final int MAX_QUOTE = 600;
    // Where the file lists go, in the order they happened; filled in by render() once every file is known.
    private static final String PROPOSED = "\u0000proposed";
    private static final String MERGED = "\u0000merged";

    private final List<String> lines = new ArrayList<>();
    private final Set<String> proposed = new LinkedHashSet<>();
    private final Set<String> merged = new LinkedHashSet<>();
    private int failedFixes;
    private int greenFixes;
    private boolean ended;
    private boolean fulfilled;
    /** Lines that only set the scene (the subgoal); a cycle with nothing besides them is not narrated. */
    private int context;

    /** The cycle part of an event's id: helpers tag theirs ("cycle-123-eval-tests"), and they belong to cycle-123. */
    private static final java.util.regex.Pattern CYCLE = java.util.regex.Pattern.compile("^cycle-\\d+");
    // "src/main/java/a/b/Foo.java" -> "Foo.java": the narrator said a test file's path as a class that does not exist.
    private static final java.util.regex.Pattern PATH = java.util.regex.Pattern.compile("(?:[\\w.-]+/)+([\\w.-]+)");

    /** The cycle an event belongs to, or null when it belongs to none. */
    static String cycleOf(Object cycleId) {
        if (!(cycleId instanceof String s)) return null;
        java.util.regex.Matcher m = CYCLE.matcher(s);
        return m.find() ? m.group() : null;
    }

    private String evaluatorState;

    void add(Map<String, Object> envelope, String goalText) {
        add(envelope, goalText, false);
    }

    /**
     * Adds one envelope ({@code event}, {@code payload}); {@code goalText} is the goal's description when the event
     * names one, and {@code root} says that goal is the run's whole goal (whose description is the seed).
     */
    @SuppressWarnings("unchecked")
    void add(Map<String, Object> envelope, String goalText, boolean root) {
        String event = String.valueOf(envelope.get("event"));
        Object raw = envelope.get("payload");
        Map<String, Object> p = raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        switch (event) {
            case "cycle_start" -> {
                String sub = str(p, "subGoalDescription");
                if (sub != null) { lines.add("Working on the subgoal: " + crop(sub)); context++; }
            }
            case "planning_run" -> {
                int injected = num(p, "injected");
                if (injected > 0) lines.add("The planner added " + injected + " subgoal" + (injected == 1 ? "" : "s") + ".");
                String why = str(p, "rationale");
                if (why != null) lines.add("The planner's reason for its plan, in its own words: " + crop(why));
            }
            case "goal_status_changed" -> {
                String to = str(p, "to");
                if (to == null) return;
                if (root) {
                    if ("COMPLETED".equals(to)) lines.add("The whole goal of the run was completed.");
                    return;
                }
                String what = goalText != null ? "The subgoal \"" + crop(goalText) + "\"" : "A subgoal";
                String verb = switch (to) {
                    case "COMPLETED" -> "was completed";
                    case "GIVEN_UP" -> "was given up";
                    case "SUBSUMED" -> "was replaced by narrower work";
                    case "ACTIVE" -> "was opened";
                    default -> "changed to " + to.toLowerCase().replace('_', ' ');
                };
                lines.add(what + " " + verb + ".");
            }
            case "mutation_proposed" -> {
                if (proposed.isEmpty()) lines.add(PROPOSED);
                files(p).forEach(proposed::add);
            }
            case "mutation_scored" -> {
                String state = str(p, "llmState");
                evaluatorState = state;
                if (state != null) lines.add("The evaluator's verdict: " + state.toLowerCase().replace('_', ' ') + ".");
                String msg = str(p, "message");
                if (msg != null) lines.add("The evaluator's reason, in its own words: " + crop(msg));
            }
            case "mutation_negotiated" -> {
                Object o = p.get("overrides");
                if (o instanceof List<?> l && !l.isEmpty()) {
                    String fin = str(p, "finalState");
                    String said = fin == null ? "decided" : fin.toLowerCase().replace('_', ' ');
                    if (evaluatorState == null) lines.add("Automatic checks decided the change: " + said + ".");
                    else if (evaluatorState.equals(fin)) lines.add("Automatic checks agreed with the evaluator: " + said + ".");
                    else lines.add("Automatic checks overruled the evaluator; the change ended up " + said + ".");
                }
            }
            case "test_delta" -> {
                int newlyFailing = num(p, "newlyFailing");
                int newlyPassing = num(p, "newlyPassing");
                if (newlyFailing > 0) lines.add("With the change, " + newlyFailing + " test" + (newlyFailing == 1 ? "" : "s") + " that passed before now fail.");
                if (newlyPassing > 0) lines.add("With the change, " + newlyPassing + " test" + (newlyPassing == 1 ? "" : "s") + " that failed before now pass.");
            }
            case "mutation_merged" -> {
                if (merged.isEmpty()) lines.add(MERGED);
                files(p).forEach(merged::add);
            }
            case "mutation_rejected" -> {
                String reason = str(p, "reason");
                lines.add("The change was rejected" + (reason != null ? ": " + crop(reason) : "."));
            }
            case "build_fix_attempt" -> {
                String source = str(p, "source");
                if ("package-gate".equals(source) || "test-gate".equals(source)) return;
                String outcome = String.valueOf(p.get("outcome"));
                if (outcome.contains("ERROR") || outcome.contains("FAILED")) failedFixes++; else greenFixes++;
            }
            case "llm_response_discarded" -> lines.add("A model answer was thrown away because it was not in the expected form.");
            case "log_question_asked", "stuck_question_asked" -> {
                String q = str(p, "question");
                if (q != null) lines.add("The loop asked itself: " + crop(q));
            }
            case "grind_detected" -> lines.add("The same failure has now happened " + num(p, "occurrences") + " times.");
            case "rewind_performed" -> lines.add("The run was rewound to an earlier cycle.");
            case "cycle_end" -> {
                ended = true;
                fulfilled = "GOAL_FULFILLED".equals(str(p, "resultState"));
            }
            default -> { }
        }
    }

    boolean ended() {
        return ended;
    }

    /** True when the cycle did something worth telling: an ended cycle with nothing in it is not narrated. */
    boolean worthTelling() {
        return lines.size() > context || failedFixes > 0 || fulfilled;
    }

    String render() {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (PROPOSED.equals(line)) out.add("Files the loop changed: " + String.join(", ", proposed) + ".");
            else if (MERGED.equals(line)) out.add("Kept in the project: " + String.join(", ", merged) + ".");
            else out.add(line);
        }
        if (failedFixes > 0) out.add("The build failed " + failedFixes + " time" + (failedFixes == 1 ? "" : "s")
                + (greenFixes > 0 ? " before it built again." : "."));
        if (merged.isEmpty() && !proposed.isEmpty()) out.add("Nothing was kept in the project this cycle.");
        if (fulfilled) out.add("With this cycle, the whole goal is fulfilled.");
        return String.join("\n", out);
    }

    private static List<String> files(Map<String, Object> p) {
        Object o = p.get("files");
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object f : l) {
                String s = String.valueOf(f);
                int i = s.lastIndexOf('/');
                out.add(i >= 0 ? s.substring(i + 1) : s);
            }
        }
        return out;
    }

    private static String str(Map<String, Object> p, String key) {
        Object o = p.get(key);
        if (o == null) return null;
        String s = String.valueOf(o).replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? null : s;
    }

    private static int num(Map<String, Object> p, String key) {
        return p.get(key) instanceof Number n ? n.intValue() : 0;
    }

    private static String crop(String s) {
        s = PATH.matcher(s).replaceAll("$1");
        return s.length() <= MAX_QUOTE ? s : s.substring(0, MAX_QUOTE) + " ...";
    }
}
